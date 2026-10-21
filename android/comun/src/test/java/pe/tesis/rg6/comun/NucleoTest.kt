package pe.tesis.rg6.comun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Prueba en el PC (sin celular): el núcleo de la app decodifica las salidas REALES del modelo ONNX
 * en 4 fotos de test y debe dar lo mismo que la decodificación oficial de Ultralytics
 * (mismas detecciones, clases, puntuaciones, cajas y máscaras -> mismo conteo).
 * Las referencias las genera referencia_ultralytics.py.
 */
class NucleoTest {
    private fun recurso(nombre: String) = javaClass.classLoader!!.getResourceAsStream("referencia/$nombre")!!

    private fun floats(nombre: String): FloatArray {
        val b = recurso(nombre).use { it.readBytes() }
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    @Test
    fun igualQueUltralytics() {
        val cfgTxt = recurso("config.txt").bufferedReader().readLines()
        val nombres = cfgTxt[0].split(" ")
        val cfg = Config(nombres, cfgTxt[1].split(" ").map { it.toFloat() }.toFloatArray(), cfgTxt[2].toFloat())
        val casos = cfgTxt[3].trim().toInt()
        var total = 0
        for (k in 0 until casos) {
            val ref = recurso("caso${k}_ref.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            val (alto, ancho) = ref[0].split(" ").map { it.toInt() }
            val esperadas = ref.drop(1).map { l -> l.split(" ").let { p -> p.map { it.toFloat() } } }
            val dets = Nucleo.decodificar(floats("caso${k}_det.bin"), floats("caso${k}_proto.bin"), alto, ancho, cfg)

            assertEquals("caso $k: número de detecciones", esperadas.size, dets.size)
            for ((d, e) in dets.zip(esperadas)) {
                assertEquals("caso $k: clase", e[0].toInt(), d.clase)
                assertTrue("caso $k: score ${d.score} vs ${e[1]}", abs(d.score - e[1]) < 1e-5f)
                val caja = floatArrayOf(d.caja.x1, d.caja.y1, d.caja.x2, d.caja.y2)
                for (j in 0..3) assertTrue("caso $k: caja ${caja[j]} vs ${e[2 + j]}", abs(caja[j] - e[2 + j]) < 1e-2f)
                val px = d.mascara.count { it }
                val pxRef = e[6].toInt()
                assertTrue("caso $k: píxeles de máscara $px vs $pxRef", abs(px - pxRef) <= maxOf(2, pxRef / 200))
            }
            val conteo = Nucleo.contar(dets, nombres.size)
            val conteoRef = IntArray(nombres.size).also { c -> esperadas.forEach { c[it[0].toInt()]++ } }
            assertEquals("caso $k: conteo", conteoRef.toList(), conteo.toList())
            println("caso $k (${ancho}x$alto): ${dets.size} detecciones = Ultralytics | conteo ${nombres.zip(conteo.toList())}")
            total += dets.size
        }
        assertTrue("debe haber detecciones que comparar", total > 0)
    }
}
