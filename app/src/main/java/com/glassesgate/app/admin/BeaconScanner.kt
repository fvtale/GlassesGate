package com.glassesgate.app.admin

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.glassesgate.app.data.SecurePrefs
import com.glassesgate.core.BeaconProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the gate is showing. */
sealed interface GateState {
    /** Nothing approved in range. The screen is red. */
    data object Closed : GateState

    /** An approved credential verified within the last moment. The screen is green. */
    data class Open(val credential: ApprovedCredential) : GateState

    data class Unavailable(val reason: String) : GateState
}

/** One admission, for the running list on the gate screen. */
data class Admission(val label: String, val serial: String, val atEpochMillis: Long)

/**
 * Watches for [BeaconProtocol] advertisements and checks them against the local allowlist.
 *
 * Entirely offline: every check is an HMAC against a secret already on this device. No network,
 * no shared backend, nothing to be down when the doors open.
 */
class BeaconScanner(
    context: Context,
    private val store: ApprovedCredentialStore,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Checked here rather than declared with `@RequiresPermission`, so that callers cannot end
     * up holding an annotation they satisfy by inspection but not by construction. A gate that
     * lacks the permission says so on screen instead of throwing.
     */
    private fun canScan() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private val serviceUuid = ParcelUuid(UUID.fromString(BeaconProtocol.SERVICE_UUID_STRING))

    private val _state = MutableStateFlow<GateState>(GateState.Closed)
    val state: StateFlow<GateState> = _state.asStateFlow()

    private val _admissions = MutableStateFlow<List<Admission>>(emptyList())

    /** Most recent admissions first. In memory only -- nothing about arrivals is persisted. */
    val admissions: StateFlow<List<Admission>> = _admissions.asStateFlow()

    /**
     * Tag -> credential, rebuilt whenever the allowlist changes. Decrypting the store inside
     * the scan callback would mean doing it on every advertisement packet, several times a
     * second per nearby phone.
     */
    @Volatile
    private var byTag: Map<String, ApprovedCredential> = emptyMap()

    @Volatile
    private var lastMatchAtMillis = 0L

    private val jobs = mutableListOf<Job>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            // Without this the gate would sit on a confident red forever while the radio was
            // never actually listening.
            _state.value = GateState.Unavailable("Bluetooth scan failed (code $errorCode).")
        }
    }

    private fun handle(result: ScanResult) {
        val (tag, token) =
            BeaconProtocol.parsePayload(result.scanRecord?.getServiceData(serviceUuid)) ?: return

        val candidate = byTag[SecurePrefs.hex(tag)] ?: return
        val now = System.currentTimeMillis()

        if (!BeaconProtocol.matches(
                tag,
                token,
                candidate.credentialId,
                candidate.secret,
                now,
            )
        ) {
            return
        }

        lastMatchAtMillis = now
        val previous = _state.value
        _state.value = GateState.Open(candidate)

        // Log an arrival, not every packet from someone standing still.
        val isNewArrival =
            previous !is GateState.Open || previous.credential.shortId != candidate.shortId
        if (isNewArrival) {
            _admissions.value =
                (listOf(Admission(candidate.label, candidate.serial, now)) + _admissions.value)
                    .take(MAX_ADMISSIONS)
            // Off this thread: markSeen decrypts and rewrites the whole store, and this is the
            // Bluetooth stack's callback thread, not somewhere to do disk work.
            scope.launch { store.markSeen(candidate, now / 1000) }
        }
    }

    @SuppressLint("MissingPermission") // guarded by canScan()
    fun start() {
        stop()

        if (!canScan()) {
            _state.value = GateState.Unavailable("Bluetooth scan permission not granted.")
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = GateState.Unavailable("Bluetooth is off.")
            return
        }

        _state.value = GateState.Closed
        lastMatchAtMillis = 0L

        jobs += scope.launch {
            store.credentials.collect { list -> byTag = list.associateBy { it.tagKey } }
        }

        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Deliver immediately; batching would add latency to a screen someone is
            // standing in front of.
            .setReportDelay(0)
            .build()

        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure {
                Log.e(TAG, "startScan failed", it)
                _state.value = GateState.Unavailable("Could not start scanning: ${it.message}")
            }

        // A ScanCallback only fires on a hit, so without a watchdog the gate would stay green
        // after the wearer walked away. LOW_LATENCY advertising repeats every few hundred
        // milliseconds, so a short silence genuinely means gone rather than merely unlucky.
        jobs += scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MILLIS)
                val stale = System.currentTimeMillis() - lastMatchAtMillis > SIGHTING_TIMEOUT_MILLIS
                if (stale && _state.value is GateState.Open) {
                    _state.value = GateState.Closed
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by canScan()
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (canScan()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
                .onFailure { Log.w(TAG, "stopScan failed", it) }
        }
        _state.value = GateState.Closed
    }

    private companion object {
        const val TAG = "BeaconScanner"
        const val WATCHDOG_TICK_MILLIS = 250L
        const val SIGHTING_TIMEOUT_MILLIS = 3_000L
        const val MAX_ADMISSIONS = 50
    }
}
