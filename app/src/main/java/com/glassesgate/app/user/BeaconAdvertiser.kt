package com.glassesgate.app.user

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
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

/**
 * Broadcasts the rotating proof from [BeaconProtocol] over BLE.
 *
 * Built on [AdvertisingSet] rather than the older `startAdvertising`. The token has to change
 * every rotation window, and the old API can only do that by stopping and restarting the
 * advertisement -- a gap on the air at every boundary, plus a known race when the same callback
 * is reused. An advertising set keeps transmitting and swaps its data in place.
 *
 * What goes on the air is exactly one Service Data structure keyed by our 128-bit UUID: 30 of the
 * 31 bytes a legacy advertisement allows. `BeaconProtocolTest` pins that arithmetic, because the
 * previous version also sent the UUID as a service-UUID list, came to 48 bytes, and was rejected
 * by Android on every attempt.
 */
class BeaconAdvertiser(context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object Off : State
        data object Starting : State
        data object OnAir : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val serviceUuid = ParcelUuid(UUID.fromString(BeaconProtocol.SERVICE_UUID_STRING))

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private class Wanted(val secret: ByteArray, val credentialId: ByteArray)

    /** What the caller asked to broadcast. Null means stopped; retries consult it. */
    @Volatile private var wanted: Wanted? = null

    /** A fresh callback per start, so a late callback from a previous set is recognisably stale. */
    @Volatile private var callback: AdvertisingSetCallback? = null

    @Volatile private var activeSet: AdvertisingSet? = null

    private var rotationJob: Job? = null
    private var retryJob: Job? = null

    /** Not every phone can act as a BLE peripheral. Check before promising the user it can. */
    val isSupported: Boolean get() = adapter?.isMultipleAdvertisementSupported == true

    /**
     * Starts broadcasting this credential. A no-op if it is already the one being broadcast, so
     * the service can call this on every state change without restarting the radio.
     */
    fun start(secret: ByteArray, credentialId: ByteArray) {
        val current = wanted
        if (current != null && current.credentialId.contentEquals(credentialId) &&
            current.secret.contentEquals(secret)
        ) {
            return
        }
        stop()
        wanted = Wanted(secret, credentialId)
        launchSet()
    }

    @SuppressLint("MissingPermission") // guarded by canAdvertise()
    fun stop() {
        wanted = null
        rotationJob?.cancel()
        retryJob?.cancel()
        rotationJob = null
        retryJob = null
        val stale = callback
        callback = null
        activeSet = null
        if (stale != null && canAdvertise()) {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(stale) }
                .onFailure { Log.w(TAG, "stopAdvertisingSet failed", it) }
        }
        _state.value = State.Off
    }

    @SuppressLint("MissingPermission") // guarded by canAdvertise()
    private fun launchSet() {
        val target = wanted ?: return
        if (!canAdvertise()) return fail("Bluetooth advertise permission not granted.", retry = false)
        if (!isSupported) return fail("This phone cannot broadcast Bluetooth advertisements.", retry = false)
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return fail("Bluetooth is off.")

        _state.value = State.Starting

        val parameters = AdvertisingSetParameters.Builder()
            // Legacy, so every phone's scanner can hear it -- extended advertising would allow a
            // bigger payload, but older gates would simply not see it.
            .setLegacyMode(true)
            // Nothing ever connects or asks for a scan response; it is a broadcast. Staying
            // non-connectable is also what keeps Android from adding a flags structure the
            // payload has no room for.
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        val setCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (callback !== this) return // superseded by a later start or a stop
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS && set != null) {
                    activeSet = set
                    _state.value = State.OnAir
                    startRotation()
                } else {
                    fail(describe(status))
                }
            }

            override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
                if (callback !== this) return
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) fail("Could not rotate the beacon (code $status).")
            }
        }
        callback = setCallback

        runCatching {
            advertiser.startAdvertisingSet(
                parameters,
                advertisement(target, System.currentTimeMillis()),
                null,
                null,
                null,
                setCallback,
            )
        }.onFailure {
            // Size and parameter problems are thrown here rather than reported to the callback.
            Log.e(TAG, "startAdvertisingSet failed", it)
            fail("Could not start the beacon: ${it.message}")
        }
    }

    /**
     * Republishes the token at each window boundary. Sleeping to the exact boundary, rather than
     * polling, means exactly one live token at a time and no radio work in between.
     */
    @SuppressLint("MissingPermission") // guarded by canAdvertise()
    private fun startRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                // A small overshoot, so the new window is unambiguously current when built.
                delay(BeaconProtocol.nextWindowBoundaryMillis(now) - now + BOUNDARY_SLACK_MILLIS)
                val target = wanted ?: return@launch
                val set = activeSet ?: return@launch
                if (!canAdvertise()) return@launch fail("Bluetooth advertise permission revoked.", retry = false)
                runCatching { set.setAdvertisingData(advertisement(target, System.currentTimeMillis())) }
                    .onFailure { fail("Could not rotate the beacon: ${it.message}") }
            }
        }
    }

    /**
     * Records a failure and, unless it cannot help, tries again shortly. Transient conditions --
     * too many advertisers, a stack hiccup -- are common on real phones, and a beacon that gives
     * up on the first one is a beacon that is off when someone reaches the door.
     */
    private fun fail(message: String, retry: Boolean = true) {
        Log.w(TAG, message)
        _state.value = State.Failed(message)
        activeSet = null
        rotationJob?.cancel()
        if (!retry) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_DELAY_MILLIS)
            if (wanted != null) relaunch()
        }
    }

    @SuppressLint("MissingPermission") // guarded by canAdvertise()
    private fun relaunch() {
        val stale = callback
        callback = null
        if (stale != null && canAdvertise()) {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(stale) }
        }
        launchSet()
    }

    private fun advertisement(target: Wanted, nowMillis: Long): AdvertiseData =
        AdvertiseData.Builder()
            // Service data only. See the class comment for why the UUID list is not here.
            .addServiceData(
                serviceUuid,
                BeaconProtocol.buildPayload(target.secret, target.credentialId, nowMillis),
            )
            // The name would not fit, and it identifies the wearer.
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

    private fun canAdvertise() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    private fun describe(status: Int): String = when (status) {
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Beacon payload too large for this phone's Bluetooth stack."
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "Too many apps are advertising over Bluetooth right now."
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Beacon was already running."
        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
            "Bluetooth reported an internal error."
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "This phone does not support Bluetooth advertising."
        else -> "Beacon failed to start (code $status)."
    }

    private companion object {
        const val TAG = "BeaconAdvertiser"
        const val BOUNDARY_SLACK_MILLIS = 50L
        const val RETRY_DELAY_MILLIS = 3_000L
    }
}
