package pe.tesis.rg6.comun

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.min
import kotlin.math.roundToInt

/** Cómo se colocó la imagen dentro de la entrada del modelo (para volver a coordenadas de la foto). */
class Letterbox(val r: Float, val izq: Int, val arr: Int)

/**
 * Resultado de analizar una imagen. Las cajas y máscaras están en coordenadas de la entrada del modelo.
 * Tiempos en ms: pre = letterbox + tensor · inferencia = onnxruntime · post = decodificación, NMS y máscaras.
 */
class Resultado(
    val dets: List<Deteccion>, val lb: Letterbox, val anchoImg: Int, val altoImg: Int, val conteo: IntArray,
    val preMs: Double, val inferenciaMs: Double, val postMs: Double,
) {
    val totalMs get() = preMs + inferenciaMs + postMs
}

/**
 * Carga un YOLOv11n-seg ONNX de assets y su assets/classes.json (lo genera el cuaderno de cuantización:
 * nombres de clase, umbral F1-óptimo por clase e IoU del NMS).
 * entradaFija = (alto, ancho): fija el tamaño de entrada del modelo dinámico (NNAPI necesita formas fijas).
 */
class Modelo(
    ctx: Context, archivo: String, hilos: Int = 4, nnapi: Boolean = false,
    private val entradaFija: Pair<Int, Int>? = null,
) : AutoCloseable {
    val cfg: Config
    val descripcion: String
    private val env = OrtEnvironment.getEnvironment()
    private val sesion: OrtSession

    init {
        val j = JSONObject(ctx.assets.open("classes.json").bufferedReader().use { it.readText() })
        val nombres = j.getJSONArray("names").let { a -> List(a.length()) { a.getString(it) } }
        val post = j.getJSONObject("postprocess")
        val porClase = post.optJSONObject("conf_campo_por_clase_f1_valid")
        val umbralGeneral = post.optDouble("conf_campo_sugerido", 0.25)
        val umbrales = FloatArray(nombres.size) { k ->                   // umbral F1 de valid; si no hay, el general
            (porClase?.optJSONObject(nombres[k])?.optDouble("conf", umbralGeneral) ?: umbralGeneral).toFloat()
        }
        cfg = Config(nombres, umbrales, post.optDouble("iou_nms", 0.7).toFloat(), post.optInt("max_det", 300))
        descripcion = j.optString("model", archivo)
        sesion = env.createSession(
            ctx.assets.open(archivo).use { it.readBytes() },
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(hilos)
                entradaFija?.let { (alto, ancho) ->
                    setSymbolicDimensionValue("batch", 1)
                    setSymbolicDimensionValue("height", alto.toLong())
                    setSymbolicDimensionValue("width", ancho.toLong())
                }
                if (nnapi) addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))     // NPU/GPU del teléfono vía NNAPI
            })
    }

    fun detectar(img: Bitmap): Resultado {
        val t0 = System.nanoTime()
        val (altoEnt, anchoEnt) = entradaFija ?: Nucleo.tamanoEntrada(img.width, img.height)
        val (entrada, lb) = letterbox(img, altoEnt, anchoEnt)
        val x = aTensor(entrada)
        val t1 = System.nanoTime()
        val (det, proto) = OnnxTensor.createTensor(env, FloatBuffer.wrap(x),
            longArrayOf(1, 3, altoEnt.toLong(), anchoEnt.toLong())).use { t ->
            sesion.run(mapOf(sesion.inputNames.first() to t)).use { r ->
                val salidas = (0 until r.size()).map { k ->
                    (r[k] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { a -> b.get(a) } }
                }
                salidas.sortedBy { it.size }.let { it[0] to it[1] }    // det [38xN] es menor que proto [32 x H/4 x W/4]
            }
        }
        val t2 = System.nanoTime()
        val dets = Nucleo.decodificar(det, proto, altoEnt, anchoEnt, cfg)
        val t3 = System.nanoTime()
        return Resultado(dets, lb, img.width, img.height, Nucleo.contar(dets, cfg.nombres.size),
            (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6)
    }

    /** Letterbox idéntico a Ultralytics: escala manteniendo proporción y rellena con gris 114, centrado. */
    private fun letterbox(src: Bitmap, altoEnt: Int, anchoEnt: Int): Pair<Bitmap, Letterbox> {
        val r = min(altoEnt.toFloat() / src.height, anchoEnt.toFloat() / src.width)
        val nw = (src.width * r).roundToInt()
        val nh = (src.height * r).roundToInt()
        val izq = ((anchoEnt - nw) / 2f - 0.1f).roundToInt()
        val arr = ((altoEnt - nh) / 2f - 0.1f).roundToInt()
        val out = Bitmap.createBitmap(anchoEnt, altoEnt, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(Bitmap.createScaledBitmap(src, nw, nh, true), izq.toFloat(), arr.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out to Letterbox(r, izq, arr)
    }

    /** RGB / 255, formato NCHW. */
    private fun aTensor(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height; val a = w * h
        val px = IntArray(a).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val out = FloatArray(3 * a)
        for (i in 0 until a) {
            val c = px[i]
            out[i] = (c shr 16 and 0xFF) / 255f
            out[a + i] = (c shr 8 and 0xFF) / 255f
            out[2 * a + i] = (c and 0xFF) / 255f
        }
        return out
    }

    override fun close() = sesion.close()
}
