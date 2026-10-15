package com.glassesgate.app.admin

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.glassesgate.app.data.bluetoothEnabled
import com.glassesgate.core.BeaconProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** The gate cannot currently see anything, approved or not. Not a denial. */
    data class Unavailable(val reason: String) : GateState
}

/** One arrival, for the running list on the gate screen. */
data class Admission(val label: String, val serial: String, val atEpochMillis: Long)

/**
 * Listens for [BeaconProtocol] advertisements and checks them against the local allowlist.
 * Entirely offline: every check is an HMAC against a secret already on this device.
 *
 * Must run on the main thread -- the scope is the view model's, and Android delivers scan
 * callbacks there -- which is what lets the bookkeeping below go unlocked.
 */
class BeaconScanner(
    context: Context,
    private val store: ApprovedCredentialStore,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val serviceUuid = ParcelUuid(UUID.fromString(BeaconProtocol.SERVICE_UUID_STRING))

    private val _state = MutableStateFlow<GateState>(GateState.Closed)
    val state: StateFlow<GateState> = _state.asStateFlow()

    private val _admissions = MutableStateFlow<List<Admission>>(emptyList())

    /** Most recent first. In memory only -- nothing about who arrived is persisted. */
    val admissions: StateFlow<List<Admission>> = _admissions.asStateFlow()

    /** Tag -> credential, rebuilt whenever the allowlist changes. Keyed by `Int` to avoid allocating per packet. */
    private var byTag: Map<Int, ApprovedCredential> = emptyMap()

    /** When each credential was last verified, by tag key. Decides what counts as an arrival. */
    private val lastSeenAt = HashMap<Int, Long>()

    private var lastMatchAtMillis = 0L
    private var scanning = false
    private val jobs = mutableListOf<Job>()
    private var retryJob: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            // Without this the gate would sit on a confident red while the radio was never
            // listening. The usual cause is starting scans too often, which clears by itself.
            _state.value = GateState.Unavailable("Bluetooth scan failed (code $errorCode). Retrying…")
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(SCAN_RETRY_MILLIS)
                beginScan()
            }
        }
    }

    private fun handle(result: ScanResult) {
        // Matched in software, not by a hardware ScanFilter. A service-data filter on a 128-bit
        // UUID is offloaded to the Bluetooth controller on most phones, and how faithfully a given
        // chipset implements that is not something to discover at a door. Checking here costs a
        // map lookup per packet and behaves the same on every phone.
        val (tag, token) =
            BeaconProtocol.parsePayload(result.scanRecord?.getServiceData(serviceUuid)) ?: return

        val key = BeaconProtocol.tagKey(tag)
        val candidate = byTag[key] ?: return
        val now = System.currentTimeMillis()
        if (!BeaconProtocol.matches(tag, token, candidate.credentialId, candidate.secret, now)) return

        val previous = lastSeenAt.put(key, now) ?: 0L
        lastMatchAtMillis = now
        _state.value = GateState.Open(candidate)

        // An arrival is a credential not seen for a while, not a change in who was seen last.
        // Keying on "last shown" logged a fresh arrival every time two approved wearers standing
        // near the gate alternated.
        if (now - previous > SIGHTING_TIMEOUT_MILLIS) {
            _admissions.value =
                (listOf(Admission(candidate.displayName, candidate.serial, now)) + _admissions.value)
                    .take(MAX_ADMISSIONS)
            // Off the callback: markSeen decrypts and rewrites the store.
            scope.launch(Dispatchers.IO) { store.markSeen(candidate, now / 1000) }
        }
    }

    fun start() {
        stop()
        if (!canScan()) {
            _state.value = GateState.Unavailable("Bluetooth scan permission not granted.")
            return
        }

        _state.value = GateState.Closed
        lastMatchAtMillis = 0L
        lastSeenAt.clear()

        jobs += scope.launch {
            store.credentials.collect { list -> byTag = list.associateBy { it.tagKey } }
        }

        jobs += scope.launch {
            // Turning Bluetooth off produces no scan callback at all; the scan just stops.
            bluetoothEnabled(appContext).collect { on ->
                if (on) {
                    beginScan()
                } else {
                    endScan()
                    _state.value = GateState.Unavailable("Bluetooth is off.")
                }
            }
        }

        // A ScanCallback only fires on a hit, so without a watchdog the gate would stay green
        // after the wearer walked away. The beacon transmits about ten times a second, so a few
        // seconds of silence genuinely means gone.
        jobs += scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MILLIS)
                val stale = System.currentTimeMillis() - lastMatchAtMillis > SIGHTING_TIMEOUT_MILLIS
                if (stale && _state.value is GateState.Open) _state.value = GateState.Closed
            }
        }

        // Android downgrades a scan that has run for about thirty minutes to opportunistic mode,
        // where it only sees what other apps happen to be scanning for. A gate open for an
        // evening would go quietly deaf. Restarting well inside that window resets the clock,
        // and once every twenty minutes is nowhere near the rate limit on starting scans.
        jobs += scope.launch {
            while (isActive) {
                delay(SCAN_RESTART_MILLIS)
                if (scanning) {
                    endScan()
                    beginScan()
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        retryJob?.cancel()
        retryJob = null
        endScan()
        _state.value = GateState.Closed
    }

    @SuppressLint("MissingPermission") // guarded by canScan()
    private fun beginScan() {
        if (scanning || !canScan()) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = GateState.Unavailable("Bluetooth is off.")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Deliver immediately; batching would add latency to a screen someone is facing.
            .setReportDelay(0)
            .build()

        runCatching { scanner.startScan(emptyList(), settings, callback) }
            .onSuccess {
                scanning = true
                if (_state.value is GateState.Unavailable) _state.value = GateState.Closed
            }
            .onFailure {
                Log.e(TAG, "startScan failed", it)
                _state.value = GateState.Unavailable("Could not start scanning: ${it.message}")
            }
    }

    @SuppressLint("MissingPermission") // guarded by canScan()
    private fun endScan() {
        if (!scanning) return
        scanning = false
        if (canScan()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
                .onFailure { Log.w(TAG, "stopScan failed", it) }
        }
    }

    /**
     * Checked here rather than declared with `@RequiresPermission`, so a gate missing the
     * permission says so on screen instead of throwing.
     */
    private fun canScan() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BeaconScanner"
        const val WATCHDOG_TICK_MILLIS = 250L
        const val SIGHTING_TIMEOUT_MILLIS = 3_000L
        const val SCAN_RESTART_MILLIS = 20L * 60L * 1000L
        const val SCAN_RETRY_MILLIS = 5_000L
        const val MAX_ADMISSIONS = 50
    }
}
