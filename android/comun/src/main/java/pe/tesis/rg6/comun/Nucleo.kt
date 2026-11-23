package pe.tesis.rg6.comun

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Núcleo de detección. No usa nada de Android, así se puede probar también en el PC (NucleoTest).
 * Hace lo mismo que Ultralytics con la salida de YOLOv11-seg exportado a ONNX:
 *   salida 0: [1, 4 + nc + 32, N]      -> cx, cy, w, h, puntuación de cada clase y 32 coeficientes de máscara
 *   salida 1: [1, 32, alto/4, ancho/4] -> prototipos de máscara
 */

/** Caja en píxeles de la entrada del modelo. */
data class Caja(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val ancho get() = x2 - x1
    val alto get() = y2 - y1
}

/** Una detección. La máscara tiene la resolución de los prototipos (entrada / 4) y solo se llena dentro de la caja. */
class Deteccion(
    val caja: Caja,
    val clase: Int,
    val score: Float,
    val mascara: BooleanArray,
    val mascaraAncho: Int,
    val mascaraAlto: Int,
)

/** Configuración del post-proceso. Se lee de classes.json, que genera el cuaderno de cuantización. */
class Config(val nombres: List<String>, val umbrales: FloatArray, val iouNms: Float, val maxDet: Int = 300)

object Nucleo {
    const val NM = 32    // número de coeficientes de máscara

    /** Tamaño de entrada (alto, ancho): 768x1024 si la foto es horizontal y 1024x768 si es vertical, como en el OE3. */
    fun tamanoEntrada(anchoImg: Int, altoImg: Int): Pair<Int, Int> {
        if (anchoImg >= altoImg) return Pair(768, 1024)
        return Pair(1024, 768)
    }

    fun iou(a: Caja, b: Caja): Float {
        val anchoInter = min(a.x2, b.x2) - max(a.x1, b.x1)
        val altoInter = min(a.y2, b.y2) - max(a.y1, b.y1)
        if (anchoInter <= 0f || altoInter <= 0f) return 0f
        val interseccion = anchoInter * altoInter
        val union = a.ancho * a.alto + b.ancho * b.alto - interseccion
        return interseccion / union
    }

    /**
     * det: salida 0 aplanada, de tamaño (4 + nc + 32) * N. proto: salida 1 aplanada, de tamaño 32 * (alto/4) * (ancho/4).
     * Igual que Ultralytics: cada candidato se queda con la clase de mayor puntuación y se descarta si no supera
     * el umbral de esa clase. Después se hace NMS por clase: un candidato se elimina si su IoU con una detección
     * ya aceptada de la misma clase es mayor que iouNms.
     */
    fun decodificar(det: FloatArray, proto: FloatArray, altoEnt: Int, anchoEnt: Int, cfg: Config): List<Deteccion> {
        val nc = cfg.nombres.size
        val canales = 4 + nc + NM
        val n = det.size / canales    // número de candidatos
        require(n * canales == det.size) { "Salida inesperada: ${det.size} no es múltiplo de $canales" }
        val altoProto = altoEnt / 4
        val anchoProto = anchoEnt / 4
        require(proto.size == NM * altoProto * anchoProto) {
            "Prototipos inesperados: ${proto.size} != ${NM * altoProto * anchoProto}"
        }

        // 1. Clase y puntuación de cada candidato.
        //    La salida está ordenada por canal: el canal c del candidato i está en det[c * n + i].
        val clase = IntArray(n)
        val score = FloatArray(n)
        val candidatos = ArrayList<Int>()
        for (i in 0 until n) {
            var mejorClase = 0
            var mejorScore = det[4 * n + i]
            for (k in 1 until nc) {
                val s = det[(4 + k) * n + i]
                if (s > mejorScore) {
                    mejorScore = s
                    mejorClase = k
                }
            }
            clase[i] = mejorClase
            score[i] = mejorScore
            if (mejorScore > cfg.umbrales[mejorClase]) candidatos.add(i)
        }
        candidatos.sortByDescending { score[it] }

        // 2. NMS por clase, de mayor a menor puntuación.
        val resultado = ArrayList<Deteccion>()
        for (i in candidatos) {
            if (resultado.size >= cfg.maxDet) break
            val cx = det[i]
            val cy = det[n + i]
            val w = det[2 * n + i]
            val h = det[3 * n + i]
            val caja = Caja(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val repetida = resultado.any { it.clase == clase[i] && iou(it.caja, caja) > cfg.iouNms }
            if (repetida) continue
            val mascara = mascara(det, proto, n, i, caja, nc, anchoProto, altoProto)
            resultado.add(Deteccion(caja, clase[i], score[i], mascara, anchoProto, altoProto))
        }
        return resultado
    }

    /**
     * Máscara del candidato i: un píxel es parte de la máscara si (coeficientes x prototipos) > 0,
     * que es lo mismo que sigmoide > 0,5. Solo se calcula dentro de la caja.
     */
    private fun mascara(det: FloatArray, proto: FloatArray, n: Int, i: Int, caja: Caja, nc: Int,
                        anchoProto: Int, altoProto: Int): BooleanArray {
        val coef = FloatArray(NM)
        for (k in 0 until NM) coef[k] = det[(4 + nc + k) * n + i]

        val area = anchoProto * altoProto
        val mascara = BooleanArray(area)
        // la caja está en píxeles de la entrada y los prototipos son 4 veces más pequeños
        val xIni = max(0, ceil(caja.x1 / 4).toInt())
        val xFin = min(anchoProto, ceil(caja.x2 / 4).toInt())
        val yIni = max(0, ceil(caja.y1 / 4).toInt())
        val yFin = min(altoProto, ceil(caja.y2 / 4).toInt())
        for (y in yIni until yFin) {
            for (x in xIni until xFin) {
                val p = y * anchoProto + x
                var suma = 0f
                for (k in 0 until NM) suma += coef[k] * proto[k * area + p]
                if (suma > 0f) mascara[p] = true
            }
        }
        return mascara
    }

    /** Cuántas detecciones hay de cada clase. */
    fun contar(dets: List<Deteccion>, nc: Int): IntArray {
        val conteo = IntArray(nc)
        for (d in dets) conteo[d.clase]++
        return conteo
    }
}
