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
 * RG6 Medir (OE3): mide en el teléfono la latencia del modelo en 4 modos (INT8 y FP32, con 4 y 2 hilos).
 * El botón «Medir» ejecuta el protocolo completo (unos 6 minutos) y lo guarda en Documents/RG6Medir/registro.csv.
 */
class MainActivity : ComponentActivity() {
    private val hilo = Executors.newSingleThreadExecutor()     // una inferencia a la vez

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val vista = PreviewView(this)
        vista.scaleType = PreviewView.ScaleType.FIT_CENTER
        val capa = Overlay(this)
        val texto = TextView(this)
        texto.textSize = 15f
        texto.setTextColor(Color.WHITE)
        texto.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        texto.setPadding(24, 64, 24, 0)

        // el medidor recibe cada cuadro de la cámara; aquí solo se muestra lo que devuelve
        val medidor = Medidor(this) { resultado, info ->
            capa.mostrar(resultado, listOf("rg6Bien", "rg6Mal"))
            runOnUiThread { texto.text = "$info\nrg6Bien: ${resultado.conteo[0]}   rg6Mal: ${resultado.conteo[1]}" }
        }

        val bMedir = Button(this)
        bMedir.text = "Medir"
        bMedir.setTextColor(Color.WHITE)
        bMedir.setOnClickListener { if (!medidor.midiendo) medidor.medir() }
        val barra = LinearLayout(this)
        barra.addView(bMedir, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // cada medio segundo se actualizan el texto y el color del botón
        val ui = Handler(Looper.getMainLooper())
        ui.post(object : Runnable {
            override fun run() {
                bMedir.text = when {
                    !medidor.midiendo -> "Medir"
                    medidor.enfriando -> "Pausa de 30 s"
                    else -> "Midiendo ${medidor.modo + 1}/${MODOS.size}"
                }
                val color = if (medidor.midiendo) Color.rgb(198, 40, 40) else Color.rgb(21, 101, 192)    // rojo o azul
                bMedir.backgroundTintList = ColorStateList.valueOf(color)
                ui.postDelayed(this, 500)
            }
        })

        val pantalla = FrameLayout(this)
        pantalla.setBackgroundColor(Color.BLACK)
        pantalla.keepScreenOn = true            // la pantalla no se apaga durante la medición
        pantalla.addView(vista)
        pantalla.addView(capa)
        pantalla.addView(texto)
        val abajo = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        abajo.bottomMargin = 120
        pantalla.addView(barra, abajo)
        setContentView(pantalla)

        val pedirPermiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) Camara.iniciar(this, vista, hilo, medidor)
            else texto.text = "Sin permiso de cámara no se puede medir."
        }
        pedirPermiso.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        hilo.shutdown()
    }
}
