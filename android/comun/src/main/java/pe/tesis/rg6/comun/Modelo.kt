package pe.tesis.rg6.comun

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** Cómo se colocó la foto dentro de la entrada del modelo. Sirve para volver a las coordenadas de la foto. */
class Letterbox(val escala: Float, val izquierda: Int, val arriba: Int)

/**
 * Resultado de analizar una imagen. Las cajas y máscaras están en coordenadas de la entrada del modelo.
 * Tiempos en ms:
 *   pre        = letterbox y paso a tensor
 *   inferencia = onnxruntime
 *   post       = decodificación, NMS y máscaras
 */
class Resultado(
    val detecciones: List<Deteccion>,
    val letterbox: Letterbox,
    val anchoImg: Int,
    val altoImg: Int,
    val conteo: IntArray,
    val preMs: Double,
    val inferenciaMs: Double,
    val postMs: Double,
) {
    val totalMs get() = preMs + inferenciaMs + postMs
}

/**
 * Carga el modelo YOLOv11n-seg (ONNX) desde assets, junto con assets/classes.json.
 * classes.json lo genera el cuaderno de cuantización: nombres de las clases, umbral F1-óptimo de cada clase
 * e IoU del NMS.
 * entradaFija = (alto, ancho): si se da, todas las imágenes entran con ese tamaño (RG6Medir usa 1024x768).
 */
class Modelo(
    ctx: Context,
    archivo: String,
    hilos: Int = 4,
    private val entradaFija: Pair<Int, Int>? = null,
) : AutoCloseable {
    val cfg: Config
    val descripcion: String
    private val env = OrtEnvironment.getEnvironment()
    private val sesion: OrtSession

    init {
        // 1. Leer classes.json
        val texto = ctx.assets.open("classes.json").bufferedReader().use { it.readText() }
        val json = JSONObject(texto)
        val lista = json.getJSONArray("names")
        val nombres = List(lista.length()) { lista.getString(it) }
        val post = json.getJSONObject("postprocess")
        val porClase = post.optJSONObject("conf_campo_por_clase_f1_valid")
        val umbralGeneral = post.optDouble("conf_campo_sugerido", 0.25)
        // umbral F1 de valid de cada clase; si no está, se usa el general
        val umbrales = FloatArray(nombres.size)
        for (k in nombres.indices) {
            val datosClase = porClase?.optJSONObject(nombres[k])
            val umbral = datosClase?.optDouble("conf", umbralGeneral) ?: umbralGeneral
            umbrales[k] = umbral.toFloat()
        }
        cfg = Config(nombres, umbrales, post.optDouble("iou_nms", 0.7).toFloat(), post.optInt("max_det", 300))
        descripcion = json.optString("model", archivo)

        // 2. Crear la sesión de onnxruntime
        val opciones = OrtSession.SessionOptions()
        opciones.setIntraOpNumThreads(hilos)
        if (entradaFija != null) {
            val (alto, ancho) = entradaFija
            opciones.setSymbolicDimensionValue("batch", 1)
            opciones.setSymbolicDimensionValue("height", alto.toLong())
            opciones.setSymbolicDimensionValue("width", ancho.toLong())
        }
        val bytes = ctx.assets.open(archivo).use { it.readBytes() }
        sesion = env.createSession(bytes, opciones)
    }

    fun detectar(img: Bitmap): Resultado {
        val t0 = System.nanoTime()
        val (altoEnt, anchoEnt) = entradaFija ?: Nucleo.tamanoEntrada(img.width, img.height)
        val (entrada, letterbox) = ponerLetterbox(img, altoEnt, anchoEnt)
        val datos = aTensor(entrada)
        val t1 = System.nanoTime()

        // Inferencia: el modelo devuelve dos salidas (det y proto)
        val forma = longArrayOf(1, 3, altoEnt.toLong(), anchoEnt.toLong())
        val salidas = ArrayList<FloatArray>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(datos), forma).use { tensor ->
            sesion.run(mapOf(sesion.inputNames.first() to tensor)).use { respuesta ->
                for (k in 0 until respuesta.size()) {
                    val buffer = (respuesta[k] as OnnxTensor).floatBuffer
                    val valores = FloatArray(buffer.remaining())
                    buffer.get(valores)
                    salidas.add(valores)
                }
            }
        }
        // det [38 x N] siempre es más pequeña que proto [32 x H/4 x W/4]
        salidas.sortBy { it.size }
        val det = salidas[0]
        val proto = salidas[1]
        val t2 = System.nanoTime()

        val dets = Nucleo.decodificar(det, proto, altoEnt, anchoEnt, cfg)
        val t3 = System.nanoTime()

        val preMs = (t1 - t0) / 1e6
        val inferenciaMs = (t2 - t1) / 1e6
        val postMs = (t3 - t2) / 1e6
        val conteo = Nucleo.contar(dets, cfg.nombres.size)
        return Resultado(dets, letterbox, img.width, img.height, conteo, preMs, inferenciaMs, postMs)
    }

    /** Letterbox igual que Ultralytics: escala la foto sin deformarla, la centra y rellena con gris 114. */
    private fun ponerLetterbox(foto: Bitmap, altoEnt: Int, anchoEnt: Int): Pair<Bitmap, Letterbox> {
        val escala = min(altoEnt.toFloat() / foto.height, anchoEnt.toFloat() / foto.width)
        val nuevoAncho = (foto.width * escala).roundToInt()
        val nuevoAlto = (foto.height * escala).roundToInt()
        // el -0.1 es el mismo redondeo que usa Ultralytics
        val izquierda = ((anchoEnt - nuevoAncho) / 2f - 0.1f).roundToInt()
        val arriba = ((altoEnt - nuevoAlto) / 2f - 0.1f).roundToInt()

        val salida = Bitmap.createBitmap(anchoEnt, altoEnt, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(salida)
        lienzo.drawColor(Color.rgb(114, 114, 114))
        val escalada = Bitmap.createScaledBitmap(foto, nuevoAncho, nuevoAlto, true)
        lienzo.drawBitmap(escalada, izquierda.toFloat(), arriba.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return Pair(salida, Letterbox(escala, izquierda, arriba))
    }

    /** Pasa la imagen a números: RGB dividido entre 255, en el orden que espera el modelo (canal, alto, ancho). */
    private fun aTensor(imagen: Bitmap): FloatArray {
        val ancho = imagen.width
        val alto = imagen.height
        val area = ancho * alto
        val pixeles = IntArray(area)
        imagen.getPixels(pixeles, 0, ancho, 0, 0, ancho, alto)
        val datos = FloatArray(3 * area)
        for (i in 0 until area) {
            val color = pixeles[i]
            datos[i] = (color shr 16 and 0xFF) / 255f           // rojo
            datos[area + i] = (color shr 8 and 0xFF) / 255f     // verde
            datos[2 * area + i] = (color and 0xFF) / 255f       // azul
        }
        return datos
    }

    override fun close() = sesion.close()
}
