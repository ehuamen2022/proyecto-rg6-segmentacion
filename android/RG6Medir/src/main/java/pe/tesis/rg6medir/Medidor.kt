package pe.tesis.rg6medir

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import pe.tesis.rg6.comun.CsvPublico
import pe.tesis.rg6.comun.Modelo
import pe.tesis.rg6.comun.Resultado
import pe.tesis.rg6.comun.girar

/** Un modo de medición: qué modelo se usa y con cuántos hilos de CPU. */
class Modo(val nombre: String, val archivo: String, val hilos: Int)

private const val INT8 = "yolo11nseg_int8_conv_only_dyn.onnx"
private const val FP32 = "yolo11nseg_fp32_dyn.onnx"

// Los nombres se guardan tal cual en registro.csv y resumen_android.ipynb los usa
// (para el L% busca "INT8" y "FP32" en el nombre). No cambiarlos.
val MODOS = listOf(
    Modo("ONNX INT8 · CPU 4 hilos", INT8, 4),
    Modo("ONNX INT8 · CPU 2 hilos", INT8, 2),
    Modo("ONNX FP32 · CPU 4 hilos", FP32, 4),
    Modo("ONNX FP32 · CPU 2 hilos", FP32, 2),
)

const val CALENTAMIENTO = 5        // cuadros sin medir al empezar cada modo
const val MEDIDOS = 100            // cuadros medidos en cada modo
const val PAUSA_MS = 30_000L       // pausa entre modos para que el teléfono se enfríe
val ENTRADA = Pair(1024, 768)      // (alto, ancho): cámara vertical 4:3, igual que la evaluación del OE3

/**
 * Analiza cada cuadro de la cámara con el modo actual. Al pulsar «Medir» recorre los 4 modos:
 * en cada uno hay 5 cuadros de calentamiento y 100 medidos, y después 30 s de pausa antes del siguiente.
 * Cada cuadro de la medición se guarda como una fila de Documents/RG6Medir/registro.csv.
 */
class Medidor(private val ctx: Context, private val mostrar: (Resultado, String) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile var modo = 0             // modo que se quiere usar
    @Volatile var midiendo = false
    private var restantes = 0          // cuadros que faltan en el modo actual
    private var pausaHasta = 0L
    private var modelo: Modelo? = null
    private var activo = -1            // modo del modelo que está cargado
    private var aviso = ""
    private val registro = CsvPublico(ctx, "RG6Medir", "registro.csv", ::cabecera)

    val enfriando: Boolean
        get() = System.currentTimeMillis() < pausaHasta

    fun medir() {
        modo = 0
        restantes = CALENTAMIENTO + MEDIDOS
        pausaHasta = 0
        aviso = ""
        midiendo = true
    }

    override fun analyze(img: ImageProxy) {
        if (enfriando) {
            img.close()
            return
        }
        if (activo != modo) cambiarModelo()
        val m = modelo
        if (m == null) {
            img.close()
            return
        }

        val t0 = System.nanoTime()
        val cuadro = img.use { girar(it.toBitmap(), it.imageInfo.rotationDegrees) }
        val conversionMs = (System.nanoTime() - t0) / 1e6      // de cuadro de cámara a bitmap derecho
        val resultado = try {
            m.detectar(cuadro)
        } catch (e: Exception) {
            fallo(e)
            return
        }
        val pre = conversionMs + resultado.preMs
        val total = pre + resultado.inferenciaMs + resultado.postMs

        if (midiendo) {
            val fase = if (restantes > MEDIDOS) "calentamiento" else "medido"
            val fila = listOf(
                System.currentTimeMillis(), MODOS[activo].nombre, fase,
                pre, resultado.inferenciaMs, resultado.postMs, total,
                resultado.detecciones.size, resultado.conteo[0], resultado.conteo[1],
                temperatura(), termico(),
            )
            try {
                registro.anexar(fila.joinToString(",") + "\n")
            } catch (e: Exception) {
                aviso = "No se pudo escribir registro.csv: ${e.message}"    // un fallo al guardar no debe cerrar la app
            }
        }

        var progreso = ""
        if (midiendo) {
            progreso = "\nmedición: modo ${activo + 1}/${MODOS.size} · faltan $restantes cuadros"
            restantes--
            if (restantes == 0) siguienteModo()
        }
        val texto = "%s\ninferencia %.0f ms · total %.0f ms · %.1f FPS%s\n%s".format(
            MODOS[activo].nombre, resultado.inferenciaMs, total, 1000 / total, progreso, aviso)
        mostrar(resultado, texto)
    }

    private fun siguienteModo() {
        if (modo + 1 < MODOS.size) {
            modo++
            restantes = CALENTAMIENTO + MEDIDOS
            pausaHasta = System.currentTimeMillis() + PAUSA_MS
        } else {
            midiendo = false
        }
    }

    private fun cambiarModelo() {
        modelo?.close()
        modelo = null
        val m = MODOS[modo]
        try {
            modelo = Modelo(ctx, m.archivo, m.hilos, ENTRADA)
        } catch (e: Exception) {
            fallo(e)
            return
        }
        activo = modo
    }

    /** Si un modo falla en este teléfono, se avisa en pantalla y se pasa al siguiente. */
    private fun fallo(e: Exception) {
        aviso = "${MODOS[modo].nombre} no disponible: ${e.message?.take(120)}"
        modelo?.close()
        modelo = null
        activo = -1
        if (midiendo) siguienteModo() else modo = 0
    }

    /** Temperatura de la batería en °C (Android la da en décimas de grado). */
    private fun temperatura(): Float {
        val bateria = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val decimas = bateria?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return decimas / 10f
    }

    /** Estado térmico que informa Android (0 = normal). */
    private fun termico(): Int = ctx.getSystemService(PowerManager::class.java).currentThermalStatus

    /** Primeras líneas de registro.csv: datos del teléfono y nombres de las columnas. */
    private fun cabecera(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return "# ${Build.MANUFACTURER} ${Build.MODEL} | SoC $soc | Android ${Build.VERSION.RELEASE} | " +
               "onnxruntime 1.27.0 | entrada ${ENTRADA.second}x${ENTRADA.first}\n" +
               "epoch_ms,modo,fase,pre_ms,inferencia_ms,post_ms,total_ms,n_det,n_bien,n_mal,temp_bat_c,termico\n"
    }
}
