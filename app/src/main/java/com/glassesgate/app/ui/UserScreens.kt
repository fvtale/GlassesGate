package com.glassesgate.app.ui

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassesgate.app.R
import com.glassesgate.app.enrollment.QrScannerView
import com.glassesgate.app.user.BeaconService
import com.glassesgate.app.user.BeaconStatus
import com.glassesgate.app.user.BoundCredential
import com.glassesgate.app.user.GlassesDevice
import com.glassesgate.app.user.SdkState
import com.glassesgate.core.BeaconPolicy

/**
 * The wearer's screen: connect to Meta AI, hold one or more enrollments, turn the beacon on.
 *
 * The three status rows are in dependency order -- Meta AI, then glasses, then beacon -- so when
 * something is not working it is obvious which link in the chain to go fix.
 */
@Composable
fun UserHomeScreen(
    model: AppViewModel,
    activity: Activity,
    hasPermission: @Composable (String) -> Boolean,
    onRequestPermissions: () -> Unit,
    onScanCode: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sdk by model.link.sdk.collectAsStateWithLifecycle()
    val registered by model.link.registered.collectAsStateWithLifecycle()
    val devices by model.link.devices.collectAsStateWithLifecycle()
    val credentials by model.vault.credentials.collectAsStateWithLifecycle()
    val activeId by model.vault.activeId.collectAsStateWithLifecycle()
    val beacon by BeaconService.status.collectAsStateWithLifecycle()
    val running by BeaconService.running.collectAsStateWithLifecycle()

    val canConnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    val canAdvertise = hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    var forgetting by remember { mutableStateOf<BoundCredential?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(stringResource(R.string.user_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        val sdkFailure = (sdk as? SdkState.Failed)?.message
        StatusRow(
            title = stringResource(R.string.status_meta_ai),
            detail = when {
                !canConnect -> stringResource(R.string.status_meta_ai_needs_permission)
                sdkFailure != null -> stringResource(R.string.status_meta_ai_failed, sdkFailure)
                registered -> stringResource(R.string.status_meta_ai_registered)
                else -> stringResource(R.string.status_meta_ai_not_registered)
            },
            ok = canConnect && registered,
        )
        StatusRow(
            title = stringResource(R.string.status_glasses),
            detail = glassesSummary(devices),
            ok = devices.any { it.isLinked == true },
        )
        StatusRow(
            title = stringResource(R.string.status_beacon),
            detail = when (val current = beacon) {
                is BeaconStatus.Broadcasting ->
                    stringResource(R.string.status_beacon_live, current.gate.ifEmpty { current.label })
                is BeaconStatus.Waiting -> current.reason
                is BeaconStatus.Failed -> current.message
                BeaconStatus.Stopped -> stringResource(R.string.status_beacon_off)
            },
            ok = beacon is BeaconStatus.Broadcasting,
        )

        Spacer(Modifier.height(16.dp))

        when {
            !canConnect -> {
                Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_grant_permission))
                }
            }
            !registered -> {
                Button(
                    onClick = { model.link.startRegistration(activity) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_register))
                }
                Spacer(Modifier.height(8.dp))
                NoticeCard(stringResource(R.string.user_registration_help))
            }
            else -> TextButton(onClick = { model.link.startUnregistration(activity) }) {
                Text(stringResource(R.string.action_unregister))
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        SectionHeader(stringResource(R.string.section_enrollments))

        if (credentials.isEmpty()) {
            EmptyState(stringResource(R.string.user_no_enrollments))
        } else {
            credentials.forEach { credential ->
                CredentialRow(
                    credential = credential,
                    selected = credential.shortId == activeId,
                    // Selecting only means something once there is more than one to choose.
                    selectable = credentials.size > 1,
                    onSelect = { model.vault.setActive(credential) },
                    onForget = { forgetting = credential },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onScanCode, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_scan_enrollment))
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        SectionHeader(stringResource(R.string.section_beacon))

        when {
            credentials.isEmpty() ->
                EmptyState(stringResource(R.string.user_beacon_needs_enrollment))

            !canAdvertise -> {
                EmptyState(stringResource(R.string.error_advertise_permission))
                TextButton(onClick = onRequestPermissions) {
                    Text(stringResource(R.string.action_grant_permission))
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.beacon_toggle))
                    Switch(
                        // Whether the service is up, not whether it is transmitting: a running
                        // beacon waiting for its glasses is still on.
                        checked = running,
                        onCheckedChange = { on ->
                            if (on) BeaconService.start(context) else BeaconService.stop(context)
                        },
                    )
                }
                NoticeCard(stringResource(R.string.user_beacon_explainer))
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }

    forgetting?.let { credential ->
        ConfirmDialog(
            title = stringResource(R.string.forget_title),
            text = stringResource(
                R.string.forget_body,
                credential.gate.ifEmpty { stringResource(R.string.enrollment_unnamed_gate) },
            ),
            confirmLabel = stringResource(R.string.action_forget),
            onConfirm = {
                model.forget(credential)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

@Composable
private fun glassesSummary(devices: List<GlassesDevice>): String {
    val worn = stringResource(R.string.glasses_worn)
    val notWorn = stringResource(R.string.glasses_not_worn)
    val update = stringResource(R.string.glasses_update_needed)
    val linked = devices.filter { it.isLinked == true }
    return when {
        linked.isNotEmpty() -> linked.joinToString { device ->
            buildList {
                add(device.name)
                when (device.wear) {
                    BeaconPolicy.Wear.WORN -> add(worn)
                    BeaconPolicy.Wear.NOT_WORN -> add(notWorn)
                    BeaconPolicy.Wear.UNKNOWN -> Unit
                }
                if (device.needsUpdate) add(update)
            }.joinToString(" · ")
        }
        devices.isNotEmpty() -> stringResource(R.string.status_glasses_known_offline)
        else -> stringResource(R.string.status_glasses_none)
    }
}

@Composable
private fun CredentialRow(
    credential: BoundCredential,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = selectable, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                credential.gate.ifEmpty { stringResource(R.string.enrollment_unnamed_gate) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.enrollment_detail,
                    credential.label.ifEmpty { credential.shortId },
                    credential.serial.ifEmpty { stringResource(R.string.enrollment_no_serial) },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onForget) { Text(stringResource(R.string.action_forget)) }
    }
}

/**
 * Scans the admin's enrollment code and binds it to the glasses on this phone.
 *
 * Every rejection says why, and every failure offers another try. A code that silently does
 * nothing is indistinguishable from a broken camera, and someone standing at a door needs to
 * know which it is.
 */
@Composable
fun UserClaimScreen(
    model: AppViewModel,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onDone: () -> Unit,
) {
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val gateFallback = stringResource(R.string.enrollment_unnamed_gate)
    val claimedFormat = stringResource(R.string.claim_ok)
    val notACode = stringResource(R.string.claim_not_a_code)
    val expired = stringResource(R.string.claim_expired)
    val noGlasses = stringResource(R.string.claim_no_glasses)
    val ambiguous = stringResource(R.string.claim_ambiguous)
    val duplicate = stringResource(R.string.claim_already)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val result = outcome
            when {
                !hasCameraPermission -> CenteredMessage(
                    stringResource(R.string.error_camera_permission),
                    actionLabel = stringResource(R.string.action_grant_permission),
                    onAction = onRequestPermissions,
                )

                result != null -> CenteredMessage(
                    result.second,
                    // A success is final; anything else deserves another attempt.
                    actionLabel = if (result.first) null else stringResource(R.string.action_scan_again),
                    onAction = { outcome = null },
                )

                // Leaving and re-entering composition is what resets the scanner for a retry.
                else -> QrScannerView(modifier = Modifier.fillMaxSize()) { raw ->
                    // ML Kit delivers results on the main thread; the strings were resolved
                    // above because a composable lookup is not available inside this callback.
                    outcome = when (val claim = model.claim(raw)) {
                        is ClaimResult.Claimed ->
                            true to claimedFormat.format(claim.credential.gate.ifEmpty { gateFallback })
                        ClaimResult.NotAnEnrollmentCode -> false to notACode
                        ClaimResult.Expired -> false to expired
                        ClaimResult.NoGlassesConnected -> false to noGlasses
                        ClaimResult.AmbiguousGlasses -> false to ambiguous
                        ClaimResult.AlreadyClaimed -> false to duplicate
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(24.dp)) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** For the two actions in the app that cannot be undone: revoking and forgetting a credential. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
