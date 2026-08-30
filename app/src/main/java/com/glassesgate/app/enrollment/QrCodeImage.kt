package com.glassesgate.app.enrollment

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders an enrollment code.
 *
 * Always black on white regardless of app theme -- a themed QR code is a QR code that scans
 * badly. The bitmap is generated at module resolution and scaled up with nearest-neighbour
 * filtering, so the modules stay crisp squares instead of being blurred by bilinear scaling.
 */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { encode(content) }
    Image(
        painter = BitmapPainter(bitmap.asImageBitmap(), filterQuality = FilterQuality.None),
        contentDescription = "GlassesGate enrollment code",
        contentScale = ContentScale.FillWidth,
        modifier = modifier,
    )
}

private fun encode(content: String): Bitmap {
    val hints = mapOf(
        // The code is held up close and read once, so a low correction level keeps the module
        // count down and the squares large.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Passing 0x0 makes ZXing emit one pixel per module; scaling happens at draw time.
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)

    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            pixels[row + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
}

private const val QUIET_ZONE_MODULES = 2
