package com.glassesgate.app.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the Bluetooth radio is on, as a flow.
 *
 * Both roles need this. Turning Bluetooth off does not fail an advertisement or a scan with any
 * callback -- they simply stop. Without watching the radio itself, the wearer's phone would keep
 * claiming it was broadcasting, and the gate would show a confident red to someone holding a
 * perfectly good credential.
 */
fun bluetoothEnabled(context: Context): Flow<Boolean> = callbackFlow {
    val appContext = context.applicationContext
    val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    trySend(adapter?.isEnabled == true)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            trySend(state == BluetoothAdapter.STATE_ON)
        }
    }
    ContextCompat.registerReceiver(
        appContext,
        receiver,
        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    awaitClose { appContext.unregisterReceiver(receiver) }
}.distinctUntilChanged()
