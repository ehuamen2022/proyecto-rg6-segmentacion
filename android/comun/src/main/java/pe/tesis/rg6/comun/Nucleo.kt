package pe.tesis.rg6.comun

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Núcleo de detección SIN dependencias de Android, para poder probarlo también en el PC.
 * Replica la decodificación de Ultralytics (YOLOv11-seg exportado a ONNX):
 *   salida 0: [1, 4 + nc + 32, N]  -> cx, cy, w, h | puntuación por clase | 32 coeficientes de máscara
 *   salida 1: [1, 32, alto/4, ancho/4] -> prototipos de máscara
 */

/** Caja en coordenadas de la entrada del modelo (píxeles). */
data class Caja(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val ancho get() = x2 - x1
    val alto get() = y2 - y1
}

/** Una detección. La máscara está a la resolución de los prototipos (entrada / 4), solo dentro de la caja. */
class Deteccion(
    val caja: Caja, val clase: Int, val score: Float,
    val mascara: BooleanArray, val mascaraAncho: Int, val mascaraAlto: Int,
)

/** Configuración del post-proceso (viene de modelo.json = classes.json del cuaderno de cuantización). */
class Config(val nombres: List<String>, val umbrales: FloatArray, val iouNms: Float, val maxDet: Int = 300)

object Nucleo {
    const val NM = 32                       // coeficientes de máscara

    /** (alto, ancho) de la entrada: 768×1024 si la foto es horizontal, 1024×768 si es vertical (igual que el OE3). */
    fun tamanoEntrada(anchoImg: Int, altoImg: Int): Pair<Int, Int> =
        if (anchoImg >= altoImg) 768 to 1024 else 1024 to 768

    fun iou(a: Caja, b: Caja): Float {
        val w = min(a.x2, b.x2) - max(a.x1, b.x1)
        val h = min(a.y2, b.y2) - max(a.y1, b.y1)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.ancho * a.alto + b.ancho * b.alto - inter)
    }

    /**
     * det: salida 0 aplanada [(4 + nc + 32) * N]; proto: salida 1 aplanada [32 * (alto/4) * (ancho/4)].
     * Igual que Ultralytics: clase = la de mayor puntuación; se descarta si no supera el umbral de SU clase;
     * NMS por clase (se elimina si IoU > iouNms con una detección de la misma clase y mayor puntuación).
     */
    fun decodificar(det: FloatArray, proto: FloatArray, altoEnt: Int, anchoEnt: Int, cfg: Config): List<Deteccion> {
        val nc = cfg.nombres.size
        val canales = 4 + nc + NM
        val n = det.size / canales
        require(n * canales == det.size) { "Salida inesperada: ${det.size} no es múltiplo de $canales" }
        val ph = altoEnt / 4
        val pw = anchoEnt / 4
        require(proto.size == NM * ph * pw) { "Prototipos inesperados: ${proto.size} != ${NM * ph * pw}" }

        val clase = IntArray(n)
        val score = FloatArray(n)
        val candidatos = ArrayList<Int>()
        for (i in 0 until n) {
            var mejor = 0
            var s = det[4 * n + i]
            for (k in 1 until nc) {
                val v = det[(4 + k) * n + i]
                if (v > s) { s = v; mejor = k }
            }
            clase[i] = mejor
            score[i] = s
            if (s > cfg.umbrales[mejor]) candidatos += i
        }
        candidatos.sortByDescending { score[it] }

        val res = ArrayList<Deteccion>()
        for (i in candidatos) {
            if (res.size >= cfg.maxDet) break
            val cx = det[i]; val cy = det[n + i]; val w = det[2 * n + i]; val h = det[3 * n + i]
            val c = Caja(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            if (res.any { it.clase == clase[i] && iou(it.caja, c) > cfg.iouNms }) continue
            res += Deteccion(c, clase[i], score[i], mascara(det, proto, n, i, c, nc, pw, ph), pw, ph)
        }
        return res
    }

    /** Máscara = (coeficientes · prototipos) > 0 (equivale a sigmoide > 0,5), recortada a la caja. */
    private fun mascara(det: FloatArray, proto: FloatArray, n: Int, i: Int, c: Caja, nc: Int, pw: Int, ph: Int): BooleanArray {
        val coef = FloatArray(NM) { det[(4 + nc + it) * n + i] }
        val m = BooleanArray(pw * ph)
        val area = pw * ph
        val x0 = max(0, ceil(c.x1 / 4).toInt()); val x1 = min(pw, ceil(c.x2 / 4).toInt())
        val y0 = max(0, ceil(c.y1 / 4).toInt()); val y1 = min(ph, ceil(c.y2 / 4).toInt())
        for (y in y0 until y1) for (x in x0 until x1) {
            val o = y * pw + x
            var v = 0f
            for (k in 0 until NM) v += coef[k] * proto[k * area + o]
            if (v > 0f) m[o] = true
        }
        return m
    }

    /** Conteo por clase. */
    fun contar(dets: List<Deteccion>, nc: Int): IntArray = IntArray(nc).also { cnt -> dets.forEach { cnt[it.clase]++ } }
}
