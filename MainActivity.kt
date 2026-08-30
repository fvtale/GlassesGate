package com.glassesgate.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.glassesgate.app.admin.ApprovedDevice
import com.glassesgate.app.admin.ApprovedDeviceStore
import com.glassesgate.app.admin.BeaconScanner
import com.glassesgate.app.admin.BeaconState
import com.glassesgate.app.enrollment.EnrollmentPayload
import com.glassesgate.app.enrollment.QrCodeImage
import com.glassesgate.app.enrollment.QrScannerView
import com.glassesgate.app.user.BeaconAdvertiser
import com.glassesgate.app.user.EnrollmentSecretStore
import com.glassesgate.app.user.GlassesSessionManager

private sealed class Screen {
    data object RoleSelect : Screen()
    data object User : Screen()
    data class UserEnroll(val deviceId: String) : Screen()
    data object Admin : Screen()
    data object AdminEnroll : Screen()
    data object AdminBeacon : Screen()
}

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Individual screens re-check the specific permission they need before acting.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions.launch(runtimePermissions())

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.RoleSelect) }
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val current = screen) {
                        Screen.RoleSelect -> RoleSelectScreen(
                            onUser = { screen = Screen.User },
                            onAdmin = { screen = Screen.Admin },
                        )
                        Screen.User -> UserScreen(
                            activity = this,
                            onEnroll = { id -> screen = Screen.UserEnroll(id) },
                            onBack = { screen = Screen.RoleSelect },
                        )
                        is Screen.UserEnroll -> UserEnrollScreen(
                            deviceId = current.deviceId,
                            onDone = { screen = Screen.User },
                        )
                        Screen.Admin -> AdminScreen(
                            onEnroll = { screen = Screen.AdminEnroll },
                            onStartBeacon = { screen = Screen.AdminBeacon },
                            onBack = { screen = Screen.RoleSelect },
                        )
                        Screen.AdminEnroll -> AdminEnrollScreen(onDone = { screen = Screen.Admin })
                        Screen.AdminBeacon -> AdminBeaconScreen(onStop = { screen = Screen.Admin })
                    }
                }
            }
        }
    }

    private fun runtimePermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Pre-12, BLE scanning is gated behind location permission.
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms.toTypedArray()
    }
}

@Composable
private fun RoleSelectScreen(onUser: () -> Unit, onAdmin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GlassesGate", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose how this device will be used.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onUser, modifier = Modifier.fillMaxWidth()) {
            Text("Continue as User")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAdmin, modifier = Modifier.fillMaxWidth()) {
            Text("Continue as Admin")
        }
    }
}

// ---------- User role ----------

@Composable
private fun UserScreen(activity: Activity, onEnroll: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var deviceId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Not connected") }
    var beaconOn by remember { mutableStateOf(false) }

    val advertiser = remember(deviceId) {
        deviceId?.let { id ->
            BeaconAdvertiser(context, EnrollmentSecretStore(context).secretFor(id), id)
        }
    }

    DisposableEffect(advertiser) {
        onDispose {
            if (hasPermission(context, advertisePermission())) {
                advertiser?.stop()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("User mode", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { GlassesSessionManager.registerWithMetaAi(activity) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Register with Meta AI app")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                status = "Connecting..."
                GlassesSessionManager.startSession().fold(
                    onSuccess = { id ->
                        deviceId = id
                        status = "Session active\n$id"
                    },
                    onFailure = { e -> status = "Failed: ${e.message}" },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect glasses")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        val id = deviceId
        if (id == null) {
            Text(
                "Connect your glasses to enable the approval beacon.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (advertiser?.isSupported == false) {
            Text(
                "This phone can't broadcast BLE advertisements, so the beacon is unavailable. " +
                    "Use the enrollment QR for verification instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onEnroll(id) }) { Text("Show enrollment QR") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Broadcast approval beacon")
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = beaconOn,
                    onCheckedChange = { checked ->
                        if (!hasPermission(context, advertisePermission())) {
                            status = "Bluetooth advertise permission not granted"
                            return@Switch
                        }
                        beaconOn = checked
                        if (checked) advertiser?.start() else advertiser?.stop()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onEnroll(id) }) { Text("Show enrollment QR for an admin") }
        }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun UserEnrollScreen(deviceId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("My glasses") }
    val secret = remember(deviceId) { EnrollmentSecretStore(context).secretFor(deviceId) }
    val payload = remember(label, secret) {
        EnrollmentPayload(label, deviceId, secret).encode()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Enrollment QR", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label shown to the admin") },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        QrCodeImage(payload, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(
            "Have an admin scan this once to approve your glasses. " +
                "Treat it like a password -- anyone who photographs it can spoof your beacon.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDone) { Text("Done") }
    }
}

// ---------- Admin role ----------

@Composable
private fun AdminScreen(onEnroll: () -> Unit, onStartBeacon: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ApprovedDeviceStore(context) }
    var devices by remember { mutableStateOf(store.all()) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Admin mode", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Button(onClick = onEnroll, modifier = Modifier.fillMaxWidth()) {
            Text("Enroll new glasses (scan QR)")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStartBeacon,
            enabled = devices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start beacon session")
        }
        if (devices.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Enroll at least one pair of glasses before starting a session.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Approved devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices, key = { it.deviceIdentifier }) { device ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(device.label)
                    TextButton(onClick = {
                        store.remove(device.deviceIdentifier)
                        devices = store.all()
                    }) {
                        Text("Remove")
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun AdminEnrollScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ApprovedDeviceStore(context) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (hasPermission(context, Manifest.permission.CAMERA)) {
                QrScannerView(modifier = Modifier.fillMaxSize()) { raw ->
                    val payload = EnrollmentPayload.decode(raw)
                    result = if (payload != null) {
                        store.add(
                            ApprovedDevice(
                                payload.label,
                                payload.deviceIdentifier,
                                payload.secret,
                            ),
                        )
                        "Added \"${payload.label}\""
                    } else {
                        "Not a GlassesGate enrollment code"
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Camera permission is required to scan enrollment codes.")
                }
            }
        }
        Column(modifier = Modifier.padding(24.dp)) {
            result?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun AdminBeaconScreen(onStop: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ApprovedDeviceStore(context) }
    val scanner = remember { BeaconScanner(context, store) }
    val state by scanner.state.collectAsState()
    val canScan = hasPermission(context, scanPermission())

    DisposableEffect(canScan) {
        if (canScan) scanner.start()
        onDispose { if (canScan) scanner.stop() }
    }

    val (background, label) = when (val s = state) {
        is BeaconState.Green -> Color(0xFF2E7D32) to "ACCESS GRANTED\n${s.label}"
        BeaconState.Red -> Color(0xFFC62828) to "ACCESS DENIED"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (canScan) label else "BLUETOOTH SCAN\nPERMISSION REQUIRED",
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onStop) { Text("End session", color = Color.White) }
    }
}

// ---------- helpers ----------

private fun scanPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_SCAN
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

private fun advertisePermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_ADVERTISE
    } else {
        Manifest.permission.BLUETOOTH
    }

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
