package com.glassesgate.app.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassesgate.app.R
import com.glassesgate.app.admin.ApprovedCredential
import com.glassesgate.app.enrollment.QrCodeImage

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
                ApprovedRow(credential = credential, onRevoke = { model.revoke(credential) })
            }
        }

        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

@Composable
private fun ApprovedRow(credential: ApprovedCredential, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                credential.label.ifEmpty { credential.shortId },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (credential.isUnclaimed) {
                    // Offline enrollment means the admin gets no confirmation the user scanned
                    // the code. Saying so beats showing an entry that looks the same either way.
                    stringResource(R.string.approved_never_seen)
                } else {
                    stringResource(
                        R.string.approved_serial,
                        credential.serial.ifEmpty { stringResource(R.string.enrollment_no_serial) },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke) { Text(stringResource(R.string.action_revoke)) }
    }
}

/**
 * Where the admin types in the serial number off the glasses.
 *
 * The serial is a label, not a key -- see [ApprovedCredential]. It is asked for first anyway,
 * because it is how a human tells two identical pairs of glasses apart later.
 */
@Composable
fun AdminEnrollScreen(
    model: AppViewModel,
    onIssued: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var serial by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var gate by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(
            stringResource(R.string.enroll_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.enroll_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it },
            label = { Text(stringResource(R.string.field_serial)) },
            supportingText = { Text(stringResource(R.string.field_serial_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(R.string.field_label)) },
            supportingText = { Text(stringResource(R.string.field_label_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = gate,
            onValueChange = { gate = it },
            label = { Text(stringResource(R.string.field_gate)) },
            supportingText = { Text(stringResource(R.string.field_gate_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onIssued(model.issueEnrollment(serial, label, gate).encode()) },
            enabled = label.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_generate_code))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

/**
 * The enrollment code itself, on a white card so it scans in a dim doorway.
 *
 * The warning underneath is not boilerplate: until the code expires, whoever scans it first gets
 * the credential, and that includes someone photographing it over a shoulder.
 */
@Composable
fun AdminShowCodeScreen(encoded: String?, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.code_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))

        if (encoded == null) {
            // Reached by rotating away from the code: the payload is deliberately not saved.
            EmptyState(stringResource(R.string.code_lost))
        } else {
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
                stringResource(R.string.code_instructions),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            NoticeCard(stringResource(R.string.code_warning))
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
        Spacer(Modifier.height(24.dp))
    }
}

