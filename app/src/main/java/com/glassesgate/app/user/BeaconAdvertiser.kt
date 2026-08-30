package com.glassesgate.app.user

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Broadcasts the rotating proof from [BeaconProtocol] over BLE.
 *
 * The advertised bytes are fixed once `startAdvertising` is called, so the token has to be
 * republished whenever the rotation window turns over. Rather than re-advertising on a fixed
 * interval and hoping it lines up, this sleeps until the exact boundary
 * ([BeaconProtocol.nextWindowBoundaryMillis]) and republishes there -- one restart per window,
 * always the freshest token.
 */
class BeaconAdvertiser(
    context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {

    private val appContext = context.applicationContext

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Checked here rather than declared with `@RequiresPermission`, so a caller cannot satisfy
     * the annotation by inspection and then lose the grant while the beacon is running.
     */
    private fun canAdvertise() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    private val serviceUuid = ParcelUuid(UUID.fromString(BeaconProtocol.SERVICE_UUID_STRING))

    private var job: Job? = null

    private val callback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            // Silence here used to look identical to a working beacon from the UI, which is the
            // worst possible failure mode for something whose whole job is to be trusted.
            onError(describeFailure(errorCode))
        }
    }

    /**
     * Not every phone can act as a BLE peripheral. Callers must check this before promising the
     * user their phone can hold a credential at all.
     */
    val isSupported: Boolean get() = adapter?.isMultipleAdvertisementSupported == true

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun start(secret: ByteArray, credentialId: ByteArray) {
        stop()
        if (!canAdvertise()) {
            onError("Bluetooth advertise permission not granted.")
            return
        }
        if (!isSupported) {
            onError("This phone cannot broadcast Bluetooth advertisements.")
            return
        }
        job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                publish(secret, credentialId, now)
                // A small overshoot past the boundary, so the new window is unambiguously
                // current by the time the payload is rebuilt.
                delay((BeaconProtocol.nextWindowBoundaryMillis(now) - now).coerceAtLeast(1L) + 50L)
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by canAdvertise()
    fun stop() {
        job?.cancel()
        job = null
        if (canAdvertise()) {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
                .onFailure { Log.w(TAG, "stopAdvertising failed", it) }
        }
    }

    @SuppressLint("MissingPermission") // guarded by canAdvertise() in start()
    private fun publish(secret: ByteArray, credentialId: ByteArray, nowMillis: Long) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            onError("Bluetooth is off.")
            return
        }

        // Stop first: otherwise the previous window's token keeps broadcasting alongside the
        // new one, and the effective replay window quietly doubles.
        runCatching { advertiser.stopAdvertising(callback) }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // Nothing ever connects to this; it is a broadcast beacon.
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addServiceData(
                serviceUuid,
                BeaconProtocol.buildPayload(secret, credentialId, nowMillis),
            )
            // The device name would blow the 31-byte budget and identifies the wearer.
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        runCatching { advertiser.startAdvertising(settings, data, callback) }
            .onFailure { onError("Could not start the beacon: ${it.message}") }
    }

    private fun describeFailure(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Beacon payload too large for this phone's Bluetooth stack."
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "Too many apps are advertising over Bluetooth right now."
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Beacon was already running."
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth reported an internal error."
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "This phone does not support Bluetooth advertising."
        else -> "Beacon failed to start (code $errorCode)."
    }

    private companion object {
        const val TAG = "BeaconAdvertiser"
    }
}
