package io.github.patschgoo.anibox.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera picture with a QR code reader behind it (CameraX + ZXing: no Google Play services).
 * [onText] gets each code's text (on the main thread) until it returns true.
 */
@Composable
fun QrScanner(modifier: Modifier, onText: (String) -> Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val done = remember { AtomicBoolean(false) }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        val main = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = try { future.get() } catch (_: Exception) { return@addListener }
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val text = if (done.get()) null else decode(reader, image)
                image.close()
                if (text != null) {
                    main.execute {
                        if (!done.get() && callback.value(text)) done.set(true)
                    }
                }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // No back camera (or it's busy): pasting the text still works.
            }
        }, main)
        onDispose {
            try { provider?.unbindAll() } catch (_: Exception) {}
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val width = image.width
    val height = image.height
    val stride = plane.rowStride
    if (stride < width || data.size < stride * (height - 1) + width) return null
    return try {
        val source = PlanarYUVLuminanceSource(data, stride, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
