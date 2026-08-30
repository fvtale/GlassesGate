package com.glassesgate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassesgate.app.R
import com.glassesgate.app.admin.GateState
import java.text.DateFormat
import java.util.Date

/**
 * The gate itself: a full-screen red that turns green while an approved pair is in range.
 *
 * Deliberately one enormous colour block with the name underneath. It is read across a doorway,
 * at a glance, by someone who is also looking at a person -- so the colour has to carry the
 * message on its own and the text only has to confirm it.
 *
 * The screen is held awake for the whole session: a gate that sleeps is a gate that is closed.
 */
@Composable
fun GateScreen(model: AppViewModel, onExit: () -> Unit) {
    val state by model.scanner.state.collectAsStateWithLifecycle()
    val admissions by model.scanner.admissions.collectAsStateWithLifecycle()
    val view = LocalView.current

    DisposableEffect(Unit) {
        model.scanner.start()
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            model.scanner.stop()
        }
    }

    val background = when (state) {
        is GateState.Open -> GateColors.Admitted
        is GateState.Closed -> GateColors.Denied
        // An unavailable radio is not a denial. Grey says "this gate is not working" rather
        // than accusing whoever is standing in front of it.
        is GateState.Unavailable -> Color(0xFF37474F)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (state) {
                        is GateState.Open -> stringResource(R.string.gate_open)
                        is GateState.Closed -> stringResource(R.string.gate_closed)
                        is GateState.Unavailable -> stringResource(R.string.gate_unavailable)
                    },
                    color = GateColors.OnGate,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when (val current = state) {
                        is GateState.Open -> current.credential.label.ifEmpty {
                            current.credential.shortId
                        }
                        is GateState.Closed -> stringResource(R.string.gate_closed_detail)
                        is GateState.Unavailable -> current.reason
                    },
                    color = GateColors.OnGate,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (admissions.isNotEmpty()) {
            Text(
                stringResource(R.string.gate_recent),
                color = GateColors.OnGate,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Two people can be admitted in the same millisecond; the label disambiguates.
                items(admissions, key = { "${it.atEpochMillis}:${it.label}" }) { admission ->
                    Text(
                        stringResource(
                            R.string.gate_admission_line,
                            timeFormat.format(Date(admission.atEpochMillis)),
                            admission.label,
                        ),
                        color = GateColors.OnGate,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        TextButton(onClick = onExit) {
            Text(stringResource(R.string.action_end_session), color = GateColors.OnGate)
        }
    }
}

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)
