package com.glassesgate.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Gate colours are deliberately *not* part of the dynamic scheme. Red and green here mean
 * "denied" and "admitted" to someone glancing at a phone across a doorway, and a wallpaper-tinted
 * green is not worth the ambiguity.
 */
object GateColors {
    val Admitted = Color(0xFF1B5E20)
    val Denied = Color(0xFFB3261E)
    val OnGate = Color.White
}

@Composable
fun GlassesGateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // minSdk is 31, so Material You is always available.
    val colors = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
