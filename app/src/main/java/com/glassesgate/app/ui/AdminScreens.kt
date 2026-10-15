package com.glassesgate.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassesgate.app.R
import com.glassesgate.app.admin.ApprovedCredential
import com.glassesgate.app.enrollment.QrCodeImage
import com.glassesgate.core.EnrollmentPayload
import kotlinx.coroutines.delay

/**
 * The door phone's home screen: who is allowed in, and the button that opens the gate.
 *
 * "Start gate session" stays disabled until at least one pair is enrolled, because a gate with an
 * empty allowlist is a screen that shows red forever and looks broken rather than empty.
 */
@Composable
fun AdminHomeScreen(
    model: AppViewModel,
    hasScanPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onNewEnrollment: () -> Unit,
    onStartGate: () -> Unit,
    onBack: () -> Unit,
) {
    val credentials by model.approved.credentials.collectAsStateWithLifecycle()
    var revoking by remember { mutableStateOf<ApprovedCredential?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.admin_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Button(onClick = onNewEnrollment, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_add_glasses))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStartGate,
            enabled = credentials.isNotEmpty() && hasScanPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_start_gate))
        }

        if (!hasScanPermission) {
            Spacer(Modifier.height(8.dp))
            EmptyState(stringResource(R.string.error_scan_permission))
            TextButton(onClick = onRequestPermissions) {
                Text(stringResource(R.string.action_grant_permission))
            }
        } else if (credentials.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            EmptyState(stringResource(R.string.admin_no_credentials))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader(stringResource(R.string.section_approved, credentials.size))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(credentials, key = { it.shortId }) { credential ->
                ApprovedRow(credential = credential, onRevoke = { revoking = credential })
            }
        }

        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }

    revoking?.let { credential ->
        ConfirmDialog(
            title = stringResource(R.string.revoke_title),
            text = stringResource(R.string.revoke_body, credential.displayName),
            confirmLabel = stringResource(R.string.action_revoke),
            onConfirm = {
                model.revoke(credential)
                revoking = null
            },
            onDismiss = { revoking = null },
        )
    }
}

@Composable
private fun ApprovedRow(credential: ApprovedCredential, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(credential.displayName, style = MaterialTheme.typography.bodyLarge)
            val serial = credential.serial.ifEmpty { stringResource(R.string.enrollment_no_serial) }
            Text(
                if (credential.isUnclaimed) {
                    // Offline enrollment means the admin gets no confirmation the wearer scanned
                    // the code. Saying so beats an entry that looks the same either way.
                    stringResource(R.string.approved_never_seen, serial)
                } else {
                    stringResource(R.string.approved_serial, serial)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke) { Text(stringResource(R.string.action_revoke)) }
    }
}

/**
 * Where the admin enters the serial number off the glasses.
 *
 * The serial is required. It is not a key -- see [ApprovedCredential] -- but it is what makes
 * re-enrolling a pair revoke its previous credential, which is the whole recovery story for a lost
 * phone. A pair enrolled without one could never be cleanly replaced.
 */
@Composable
fun AdminEnrollScreen(
    model: AppViewModel,
    onIssued: (IssuedEnrollment) -> Unit,
    onCancel: () -> Unit,
) {
    var serial by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var gate by rememberSaveable { mutableStateOf("") }
    val max = AppViewModel.MAX_FIELD_CHARS
    val replacing = model.isSerialEnrolled(serial)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(stringResource(R.string.enroll_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.enroll_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = serial,
            // Capped at input, because the wire format cannot carry unbounded text and the old
            // form crashed on a long enough label.
            onValueChange = { serial = it.take(max) },
            label = { Text(stringResource(R.string.field_serial)) },
            supportingText = {
                Text(
                    stringResource(
                        if (replacing) R.string.field_serial_replaces else R.string.field_serial_help,
                    ),
                )
            },
            isError = replacing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(max) },
            label = { Text(stringResource(R.string.field_label)) },
            supportingText = { Text(stringResource(R.string.field_label_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = gate,
            onValueChange = { gate = it.take(max) },
            label = { Text(stringResource(R.string.field_gate)) },
            supportingText = { Text(stringResource(R.string.field_gate_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onIssued(model.issueEnrollment(serial, label, gate)) },
            enabled = AppViewModel.normalizeSerial(serial).isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (replacing) R.string.action_replace_code else R.string.action_generate_code,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

/**
 * The enrollment code itself, on a white card so it scans in a dim doorway.
 *
 * Until it expires, whoever scans this first gets the credential, so the screen does three things
 * to keep it from leaking: it blocks screenshots, screen recording, and the recent-apps thumbnail
 * while it is up; it counts down; and at expiry it takes the code off the screen.
 */
@Composable
fun AdminShowCodeScreen(issued: IssuedEnrollment?, onDone: () -> Unit) {
    SecureWindow()

    val encoded = remember(issued) { issued?.payload?.encode() }
    val expiresAt = issued?.let {
        it.payload.issuedAtEpochSeconds + EnrollmentPayload.DEFAULT_TTL_SECONDS
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expiresAt) {
        while (expiresAt != null && now <= expiresAt) {
            delay(1_000)
            now = System.currentTimeMillis() / 1000
        }
    }
    val remaining = expiresAt?.let { (it - now).coerceAtLeast(0) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.code_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when {
            // Reached by rotating away from the code: the payload is deliberately not saved.
            encoded == null || remaining == null -> EmptyState(stringResource(R.string.code_lost))

            remaining == 0L -> EmptyState(stringResource(R.string.code_expired))

            else -> {
                if ((issued?.replaced ?: 0) > 0) {
                    NoticeCard(stringResource(R.string.code_replaced))
                    Spacer(Modifier.height(12.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(16.dp),
                ) {
                    QrCodeImage(encoded, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(
                        R.string.code_countdown,
                        remaining / 60,
                        remaining % 60,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.code_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                NoticeCard(stringResource(R.string.code_warning))
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * FLAG_SECURE for as long as the calling composable is on screen. The window flag is what Android
 * honours for screenshots, screen recording, casting, and the recents thumbnail alike.
 */
@Composable
private fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
