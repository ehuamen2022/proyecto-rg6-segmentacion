package pe.tesis.rg6.comun

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** Cámara trasera en 4:3, como las fotos del dataset: vista previa, análisis de cuadros y, si se pide, fotos. */
object Camara {
    // pide resoluciones 4:3 (si no hay, la más parecida)
    private fun selector43() = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)

    /** Para tomar fotos a resolución completa (lo usa RG6Contador). */
    fun nuevaCaptura(): ImageCapture {
        return ImageCapture.Builder()
            .setResolutionSelector(selector43().build())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    fun iniciar(act: ComponentActivity, vista: PreviewView, hilo: Executor, analizar: ImageAnalysis.Analyzer,
                captura: ImageCapture? = null) {
        val futuro = ProcessCameraProvider.getInstance(act)
        futuro.addListener({
            val preview = Preview.Builder()
                .setResolutionSelector(selector43().build())
                .build()
            preview.setSurfaceProvider(vista.surfaceProvider)

            // cuadros de 1280x960 para el modelo; si no hay, el más cercano
            val resolucion = ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            val analisis = ImageAnalysis.Builder()
                .setResolutionSelector(selector43().setResolutionStrategy(resolucion).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)   // tiempo real: siempre el último cuadro
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analisis.setAnalyzer(hilo, analizar)

            val proveedor = futuro.get()
            proveedor.unbindAll()
            val casos = listOfNotNull(preview, analisis, captura).toTypedArray()
            proveedor.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, *casos)
        }, ContextCompat.getMainExecutor(act))
    }
}

/** Gira el cuadro de la cámara para que quede derecho. */
fun girar(imagen: Bitmap, grados: Int): Bitmap {
    if (grados == 0) return imagen
    val giro = Matrix()
    giro.postRotate(grados.toFloat())
    return Bitmap.createBitmap(imagen, 0, 0, imagen.width, imagen.height, giro, true)
}
