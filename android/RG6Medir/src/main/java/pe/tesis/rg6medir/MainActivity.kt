package pe.tesis.rg6medir

import android.Manifest
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import pe.tesis.rg6.comun.Camara
import pe.tesis.rg6.comun.Overlay
import java.util.concurrent.Executors

/**
 * RG6 Medir (OE3): mide en el teléfono la latencia del modelo en 5 modos (INT8/FP32, 4/2 hilos, NNAPI).
 *  - «Modo»: cambia el modo de la vista previa (para encuadrar la escena).
 *  - «Medir»: ejecuta el protocolo completo (unos 8 minutos) y lo guarda en Documents/RG6Medir/registro.csv.
 */
class MainActivity : ComponentActivity() {
    private val hilo = Executors.newSingleThreadExecutor()     // una inferencia a la vez

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val vista = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        val capa = Overlay(this)
        val texto = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setShadowLayer(6f, 0f, 0f, Color.BLACK); setPadding(24, 64, 24, 0)
        }
        val medidor = Medidor(this) { r, t ->
            capa.mostrar(r, listOf("rg6Bien", "rg6Mal"))
            runOnUiThread { texto.text = "$t\nrg6Bien: ${r.conteo[0]}   rg6Mal: ${r.conteo[1]}" }
        }
        val boton = { t: String, f: Button.() -> Unit -> Button(this).apply { text = t; setOnClickListener { f() } } }
        val bModo = boton("Modo") { if (!medidor.midiendo) medidor.modo = (medidor.modo + 1) % MODOS.size }
        val bMedir = boton("Medir") { if (!medidor.midiendo) medidor.medir() }.apply { setTextColor(Color.WHITE) }
        val barra = LinearLayout(this).apply {
            listOf(bModo, bMedir).forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        val ui = Handler(Looper.getMainLooper())
        ui.post(object : Runnable {                                // estado visible de la medición
            override fun run() {
                bMedir.text = when {
                    !medidor.midiendo -> "Medir"
                    medidor.enfriando -> "Pausa de 30 s"
                    else -> "Midiendo ${medidor.modo + 1}/${MODOS.size}"
                }
                bMedir.backgroundTintList = ColorStateList.valueOf(if (medidor.midiendo) Color.rgb(198, 40, 40) else Color.rgb(21, 101, 192))
                bModo.isEnabled = !medidor.midiendo
                ui.postDelayed(this, 500)
            }
        })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true                                    // la medición no se corta
            addView(vista); addView(capa); addView(texto)
            addView(barra, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 120 })
        })
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) Camara.iniciar(this, vista, hilo, medidor) else texto.text = "Sin permiso de cámara no se puede medir."
        }.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        hilo.shutdown()
    }
}
