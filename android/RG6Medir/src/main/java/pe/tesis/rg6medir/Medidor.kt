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

/** Un modo de medición: qué modelo y cómo se ejecuta. */
class Modo(val nombre: String, val archivo: String, val hilos: Int, val nnapi: Boolean = false)

private const val INT8 = "yolo11nseg_int8_conv_only_dyn.onnx"
private const val FP32 = "yolo11nseg_fp32_dyn.onnx"

// Los nombres van tal cual a registro.csv: resumen_android.ipynb los usa (L% compara "INT8" con "FP32").
val MODOS = listOf(
    Modo("ONNX INT8 · CPU 4 hilos", INT8, 4),
    Modo("ONNX INT8 · CPU 2 hilos", INT8, 2),
    Modo("ONNX FP32 · CPU 4 hilos", FP32, 4),
    Modo("ONNX FP32 · CPU 2 hilos", FP32, 2),
    Modo("ONNX INT8 · NNAPI", INT8, 4, nnapi = true),
)
const val CALENTAMIENTO = 5; const val MEDIDOS = 100; const val PAUSA_MS = 30_000L
val ENTRADA = 1024 to 768             // (alto, ancho): cámara vertical 4:3, igual que la evaluación del OE3

/**
 * Analiza cada cuadro de la cámara con el modo actual. Al pulsar «Medir» recorre los 5 modos:
 * por modo 5 cuadros de calentamiento + 100 medidos, y 30 s de pausa antes del siguiente.
 * Cada cuadro de la medición es una fila de Documents/RG6Medir/registro.csv.
 */
class Medidor(private val ctx: Context, private val mostrar: (Resultado, String) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile var modo = 0
    @Volatile var midiendo = false
    private var restantes = 0
    private var pausaHasta = 0L
    val enfriando get() = System.currentTimeMillis() < pausaHasta
    private var modelo: Modelo? = null
    private var activo = -1
    private var aviso = ""
    private val registro = CsvPublico(ctx, "RG6Medir", "registro.csv", ::cabecera)

    fun medir() { modo = 0; restantes = CALENTAMIENTO + MEDIDOS; pausaHasta = 0; aviso = ""; midiendo = true }

    override fun analyze(img: ImageProxy) {
        if (enfriando) { img.close(); return }
        if (activo != modo) cambiarModelo()
        val m = modelo ?: run { img.close(); return }
        val t0 = System.nanoTime()
        val cuadro = img.use { girar(it.toBitmap(), it.imageInfo.rotationDegrees) }
        val conversionMs = (System.nanoTime() - t0) / 1e6           // cuadro de cámara -> bitmap derecho
        val r = try { m.detectar(cuadro) } catch (e: Exception) { fallo(e); return }
        val pre = conversionMs + r.preMs
        val total = pre + r.inferenciaMs + r.postMs
        if (midiendo) {
            val fase = if (restantes > MEDIDOS) "calentamiento" else "medido"
            runCatching {                                            // el registro nunca debe cerrar la app
                registro.anexar("${System.currentTimeMillis()},${MODOS[activo].nombre},$fase,$pre,${r.inferenciaMs}," +
                    "${r.postMs},$total,${r.dets.size},${r.conteo[0]},${r.conteo[1]},${temperatura()},${termico()}\n")
            }.onFailure { aviso = "No se pudo escribir registro.csv: ${it.message}" }
        }
        val progreso = if (midiendo) "\nmedición: modo ${activo + 1}/${MODOS.size} · faltan $restantes cuadros" else ""
        if (midiendo && --restantes == 0) siguienteModo()
        mostrar(r, "%s\ninferencia %.0f ms · total %.0f ms · %.1f FPS%s\n%s".format(
            MODOS[activo].nombre, r.inferenciaMs, total, 1000 / total, progreso, aviso))
    }

    private fun siguienteModo() {
        if (modo + 1 < MODOS.size) {
            modo++; restantes = CALENTAMIENTO + MEDIDOS; pausaHasta = System.currentTimeMillis() + PAUSA_MS
        } else midiendo = false
    }

    private fun cambiarModelo() {
        modelo?.close(); modelo = null
        val m = MODOS[modo]
        try {
            modelo = Modelo(ctx, m.archivo, m.hilos, m.nnapi, ENTRADA)
        } catch (e: Exception) { fallo(e); return }
        activo = modo
    }

    /** Si un modo no funciona en este teléfono (p. ej. NNAPI), se avisa y se pasa al siguiente. */
    private fun fallo(e: Exception) {
        aviso = "${MODOS[modo].nombre} no disponible: ${e.message?.take(120)}"
        modelo?.close(); modelo = null; activo = -1
        if (midiendo) siguienteModo() else modo = 0
    }

    private fun temperatura() = (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

    private fun termico() = ctx.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun cabecera(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return "# ${Build.MANUFACTURER} ${Build.MODEL} | SoC $soc | Android ${Build.VERSION.RELEASE} | " +
               "onnxruntime 1.27.0 | entrada ${ENTRADA.second}x${ENTRADA.first}\n" +
               "epoch_ms,modo,fase,pre_ms,inferencia_ms,post_ms,total_ms,n_det,n_bien,n_mal,temp_bat_c,termico\n"
    }
}
