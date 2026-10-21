package pe.tesis.rg6.comun

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** Cámara trasera en 4:3 (como las fotos del dataset): vista previa + análisis de cuadros (+ foto opcional). */
object Camara {
    private fun sel43() = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)

    /** Para tomar fotos a resolución completa (RG6Contador). */
    fun nuevaCaptura(): ImageCapture = ImageCapture.Builder().setResolutionSelector(sel43().build())
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()

    fun iniciar(act: ComponentActivity, vista: PreviewView, hilo: Executor, analizar: ImageAnalysis.Analyzer,
                captura: ImageCapture? = null) {
        val futuro = ProcessCameraProvider.getInstance(act)
        futuro.addListener({
            val preview = Preview.Builder().setResolutionSelector(sel43().build()).build()
                .also { it.setSurfaceProvider(vista.surfaceProvider) }
            val analisis = ImageAnalysis.Builder()
                .setResolutionSelector(sel43().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)   // tiempo real: siempre el último cuadro
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(hilo, analizar) }
            val proveedor = futuro.get()
            proveedor.unbindAll()
            val casos = listOfNotNull(preview, analisis, captura).toTypedArray()
            proveedor.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, *casos)
        }, ContextCompat.getMainExecutor(act))
    }
}

/** Gira el cuadro de la cámara para que quede derecho. */
fun girar(b: Bitmap, grados: Int): Bitmap =
    if (grados == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(grados.toFloat()) }, true)
