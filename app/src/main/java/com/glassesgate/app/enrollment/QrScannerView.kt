package com.glassesgate.app.enrollment

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera preview that reads an enrollment code and calls [onDecoded] exactly once.
 *
 * Callers are responsible for holding the CAMERA permission before composing this.
 *
 * `ImageProxy.getImage` is still opt-in in CameraX. Handing ML Kit the underlying `Image` is the
 * documented path and avoids copying every frame out of the buffer, so the opt-in is taken
 * deliberately rather than worked around.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerView(modifier: Modifier = Modifier, onDecoded: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)

    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoded = remember { AtomicBoolean(false) }
    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            // Only QR: narrowing the formats measurably speeds up each frame.
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    // Held so teardown can unbind without blocking the main thread on the provider future.
    val boundProvider = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider>() }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
            // Otherwise the camera stays bound and the preview leaks into the next screen.
            runCatching { boundProvider.get()?.unbindAll() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                val provider = providerFuture.get()
                boundProvider.set(provider)

                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || decoded.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    scanner
                        .process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { barcodes ->
                            val value = barcodes.firstNotNullOfOrNull { it.rawValue }
                            // compareAndSet, so two frames decoding at once cannot both fire.
                            if (value != null && decoded.compareAndSet(false, true)) {
                                currentOnDecoded(value)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}
