package com.glassesgate.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassesgate.app.ui.AdminEnrollScreen
import com.glassesgate.app.ui.AdminHomeScreen
import com.glassesgate.app.ui.AdminShowCodeScreen
import com.glassesgate.app.ui.AppViewModel
import com.glassesgate.app.ui.GateScreen
import com.glassesgate.app.ui.GlassesGateTheme
import com.glassesgate.app.ui.RoleSelectScreen
import com.glassesgate.app.ui.UserClaimScreen
import com.glassesgate.app.ui.UserHomeScreen

/**
 * One app, one activity. Which role a device plays is a choice made at launch and kept in
 * [Screen], not a build flavour -- the same APK is installed on the door phone and the wearer's
 * phone, and the code paths are small enough that splitting them would cost more than it saves.
 */
class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Each screen re-checks the specific permission it needs before acting, so a denial
            // here degrades that one screen rather than the whole app.
            permissionsChanged++
        }

    /** Bumped after a permission dialog so composables re-read the grant state. */
    private var permissionsChanged by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GlassesGateTheme {
                val model: AppViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf(Screen.RoleSelect.name) }
                val current = remember(screen) { Screen.valueOf(screen) }

                // A payload lives only as long as it is on screen; it is a live credential and
                // has no business surviving into saved instance state.
                var pendingCode by remember { mutableStateOf<String?>(null) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    when (current) {
                        Screen.RoleSelect -> RoleSelectScreen(
                            onUser = {
                                ensure(userPermissions())
                                screen = Screen.User.name
                            },
                            onAdmin = {
                                ensure(adminPermissions())
                                screen = Screen.Admin.name
                            },
                        )

                        Screen.User -> UserHomeScreen(
                            model = model,
                            activity = this@MainActivity,
                            hasPermission = { granted(it) },
                            onRequestPermissions = { ensure(userPermissions()) },
                            onScanCode = { screen = Screen.UserClaim.name },
                            onBack = { screen = Screen.RoleSelect.name },
                        )

                        Screen.UserClaim -> UserClaimScreen(
                            model = model,
                            hasCameraPermission = granted(Manifest.permission.CAMERA),
                            onRequestPermissions = { ensure(userPermissions()) },
                            onDone = { screen = Screen.User.name },
                        )

                        Screen.Admin -> AdminHomeScreen(
                            model = model,
                            hasScanPermission = granted(Manifest.permission.BLUETOOTH_SCAN),
                            onRequestPermissions = { ensure(adminPermissions()) },
                            onNewEnrollment = { screen = Screen.AdminEnroll.name },
                            onStartGate = { screen = Screen.Gate.name },
                            onBack = { screen = Screen.RoleSelect.name },
                        )

                        Screen.AdminEnroll -> AdminEnrollScreen(
                            model = model,
                            onIssued = { encoded ->
                                pendingCode = encoded
                                screen = Screen.AdminShowCode.name
                            },
                            onCancel = { screen = Screen.Admin.name },
                        )

                        Screen.AdminShowCode -> AdminShowCodeScreen(
                            encoded = pendingCode,
                            onDone = {
                                pendingCode = null
                                screen = Screen.Admin.name
                            },
                        )

                        Screen.Gate -> GateScreen(
                            model = model,
                            onExit = { screen = Screen.Admin.name },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun granted(permission: String): Boolean {
        // Keying on the counter is what makes returning from the permission dialog re-render
        // the screen; checkSelfPermission on its own is not observable by Compose.
        val generation = permissionsChanged
        return remember(permission, generation) {
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensure(permissions: Array<String>) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    /** Advertise the beacon, talk to the glasses, scan an enrollment QR, show the notification. */
    private fun userPermissions() = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    /** The gate only ever listens. It has no reason to hold the camera or advertise. */
    private fun adminPermissions() = arrayOf(Manifest.permission.BLUETOOTH_SCAN)

    private enum class Screen {
        RoleSelect,
        User,
        UserClaim,
        Admin,
        AdminEnroll,
        AdminShowCode,
        Gate,
    }
}
