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
 * (mismas detecciones, clases, puntuaciones, cajas y máscaras, y por tanto el mismo conteo).
 * Las referencias las genera referencia_ultralytics.py.
 */
class NucleoTest {
    private fun recurso(nombre: String) = javaClass.classLoader!!.getResourceAsStream("referencia/$nombre")!!

    /** Lee un archivo .bin de floats de 32 bits (little endian), como los guarda numpy. */
    private fun leerFloats(nombre: String): FloatArray {
        val bytes = recurso(nombre).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val valores = FloatArray(buffer.remaining())
        buffer.get(valores)
        return valores
    }

    @Test
    fun igualQueUltralytics() {
        // config.txt: nombres de clase, umbrales, IoU del NMS y número de casos (una línea cada uno)
        val config = recurso("config.txt").bufferedReader().readLines()
        val nombres = config[0].split(" ")
        val umbrales = config[1].split(" ").map { it.toFloat() }.toFloatArray()
        val cfg = Config(nombres, umbrales, config[2].toFloat())
        val casos = config[3].trim().toInt()

        var total = 0
        for (k in 0 until casos) {
            // caso<k>_ref.txt: la primera línea es "alto ancho" y después hay una detección por línea:
            // clase score x1 y1 x2 y2 pixeles_de_mascara
            val ref = recurso("caso${k}_ref.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            val (alto, ancho) = ref[0].split(" ").map { it.toInt() }
            val esperadas = ref.drop(1).map { linea -> linea.split(" ").map { it.toFloat() } }
            val det = leerFloats("caso${k}_det.bin")
            val proto = leerFloats("caso${k}_proto.bin")
            val dets = Nucleo.decodificar(det, proto, alto, ancho, cfg)

            assertEquals("caso $k: número de detecciones", esperadas.size, dets.size)
            for ((d, e) in dets.zip(esperadas)) {
                assertEquals("caso $k: clase", e[0].toInt(), d.clase)
                assertTrue("caso $k: score ${d.score} vs ${e[1]}", abs(d.score - e[1]) < 1e-5f)
                val caja = floatArrayOf(d.caja.x1, d.caja.y1, d.caja.x2, d.caja.y2)
                for (j in 0..3) {
                    assertTrue("caso $k: caja ${caja[j]} vs ${e[2 + j]}", abs(caja[j] - e[2 + j]) < 1e-2f)
                }
                // se acepta una diferencia pequeña en los píxeles de la máscara: 2 píxeles o el 0,5 %
                val pixeles = d.mascara.count { it }
                val pixelesRef = e[6].toInt()
                val tolerancia = maxOf(2, pixelesRef / 200)
                assertTrue("caso $k: píxeles de máscara $pixeles vs $pixelesRef", abs(pixeles - pixelesRef) <= tolerancia)
            }

            val conteo = Nucleo.contar(dets, nombres.size)
            val conteoRef = IntArray(nombres.size)
            for (e in esperadas) conteoRef[e[0].toInt()]++
            assertEquals("caso $k: conteo", conteoRef.toList(), conteo.toList())
            println("caso $k (${ancho}x$alto): ${dets.size} detecciones = Ultralytics | conteo ${nombres.zip(conteo.toList())}")
            total += dets.size
        }
        assertTrue("debe haber detecciones que comparar", total > 0)
    }
}
