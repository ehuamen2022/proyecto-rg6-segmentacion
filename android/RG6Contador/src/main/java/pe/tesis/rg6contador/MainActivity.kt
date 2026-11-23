package pe.tesis.rg6contador

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import pe.tesis.rg6.comun.COLORES
import pe.tesis.rg6.comun.Camara
import pe.tesis.rg6.comun.CsvPublico
import pe.tesis.rg6.comun.Dibujo
import pe.tesis.rg6.comun.Modelo
import pe.tesis.rg6.comun.Overlay
import pe.tesis.rg6.comun.Resultado
import pe.tesis.rg6.comun.girar
import pe.tesis.rg6.comun.guardarJpeg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/** Una foto ya analizada, lista para «Guardar». */
class Analizada(val original: Bitmap, val segmentada: Bitmap, val resultado: Resultado, val origen: String)

/**
 * RG6 Contador: detecta y cuenta conectores rg6Bien y rg6Mal con el modelo INT8 del OE3.
 *  - En vivo: la cámara analiza cada cuadro (el botón lo activa o lo desactiva).
 *  - Foto: toma una foto a resolución completa y deja el resultado quieto en pantalla.
 *  - Galería: analiza una foto guardada en el celular.
 *  - Cámara: vuelve a la vista de la cámara.
 *  - Guardar: guarda la foto original y la segmentada en Pictures/RG6Contador/ y añade una fila
 *    a Documents/RG6Contador/capturas.csv (evidencia de campo).
 */
class MainActivity : ComponentActivity() {
    private val hilo = Executors.newSingleThreadExecutor()     // una inferencia a la vez
    private lateinit var modelo: Modelo
    private lateinit var conteo: TextView
    private lateinit var estado: TextView
    private lateinit var vista: PreviewView
    private lateinit var capa: Overlay
    private lateinit var foto: ImageView
    private lateinit var bVivo: Button
    private lateinit var bGuardar: Button
    private val captura = Camara.nuevaCaptura()
    private val capturas by lazy { CsvPublico(this, "RG6Contador", "capturas.csv", ::cabeceraCsv) }
    @Volatile private var enVivo = true
    @Volatile private var mostrandoFoto = false
    private var actual: Analizada? = null                       // la foto que se ve en pantalla

    private val galeria = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analizarFoto("galeria") { leerImagen(this, uri) }
    }
    private val permisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) Camara.iniciar(this, vista, hilo, ::analizarCuadro, captura)
        else estado.text = "Sin permiso de cámara: solo funciona Galería."
    }

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        conteo = TextView(this)
        conteo.textSize = 26f
        conteo.setPadding(24, 48, 24, 8)
        conteo.setTextColor(Color.WHITE)
        estado = TextView(this)
        estado.textSize = 13f
        estado.setPadding(24, 0, 24, 8)
        estado.setTextColor(Color.LTGRAY)

        // zona central: cámara, capa con las detecciones y foto analizada, una encima de otra
        vista = PreviewView(this)
        vista.scaleType = PreviewView.ScaleType.FIT_CENTER
        capa = Overlay(this)
        foto = ImageView(this)
        foto.scaleType = ImageView.ScaleType.FIT_CENTER
        foto.visibility = View.GONE
        foto.setBackgroundColor(Color.BLACK)
        val area = FrameLayout(this)
        area.addView(vista)
        area.addView(capa)
        area.addView(foto)

        bVivo = boton("En vivo: SÍ") { cambiarEnVivo() }
        val bFoto = boton("Foto") { tomarFoto() }
        val bGaleria = boton("Galería") {
            galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        val bCamara = boton("Cámara") { volverACamara() }
        bGuardar = boton("Guardar") { guardar() }
        bGuardar.isEnabled = false

        val pantalla = LinearLayout(this)
        pantalla.orientation = LinearLayout.VERTICAL
        pantalla.setBackgroundColor(Color.BLACK)
        pantalla.keepScreenOn = true
        pantalla.addView(conteo)
        pantalla.addView(estado)
        pantalla.addView(area, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        pantalla.addView(fila(bVivo, bFoto, bGaleria))
        val ultimaFila = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        ultimaFila.bottomMargin = 48
        pantalla.addView(fila(bCamara, bGuardar), ultimaFila)
        setContentView(pantalla)
        mostrarConteo(null)
        estado.text = "Cargando modelo..."

        // el modelo se carga en el hilo de inferencia para no congelar la pantalla
        hilo.execute {
            modelo = Modelo(this, "yolo11nseg_int8_conv_only_dyn.onnx")
            runOnUiThread {
                estado.text = textoModelo()
                permisoCamara.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun boton(texto: String, alPulsar: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.textSize = 13f
        b.isAllCaps = false
        b.setOnClickListener { alPulsar() }
        return b
    }

    /** Una fila de botones del mismo ancho. */
    private fun fila(vararg botones: Button): LinearLayout {
        val f = LinearLayout(this)
        for (b in botones) f.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return f
    }

    /** Nombre del modelo cargado, umbral de cada clase e IoU del NMS. */
    private fun textoModelo(): String {
        val cfg = modelo.cfg
        val partes = ArrayList<String>()
        for (k in cfg.nombres.indices) partes.add("${cfg.nombres[k]} ${"%.2f".format(cfg.umbrales[k])}")
        return "Modelo: ${modelo.descripcion}\nUmbrales: " + partes.joinToString(" · ") + " · IoU NMS ${cfg.iouNms}"
    }

    private fun cambiarEnVivo() {
        enVivo = !enVivo
        bVivo.text = if (enVivo) "En vivo: SÍ" else "En vivo: NO"
        if (!enVivo) capa.mostrar(null)
    }

    /** Modo en vivo: se llama con cada cuadro de la cámara, en el hilo de inferencia. */
    private fun analizarCuadro(img: ImageProxy) {
        img.use {
            if (!enVivo || mostrandoFoto || !::modelo.isInitialized) return
            val cuadro = girar(it.toBitmap(), it.imageInfo.rotationDegrees)
            val resultado = modelo.detectar(cuadro)
            runOnUiThread {
                // si mientras tanto se pasó a una foto, este resultado ya no se muestra
                if (enVivo && !mostrandoFoto) {
                    capa.mostrar(resultado, modelo.cfg.nombres)
                    mostrarConteo(resultado)
                    estado.text = "En vivo · %.0f ms por cuadro · «Foto» para guardar evidencia".format(resultado.totalMs)
                }
            }
        }
    }

    private fun tomarFoto() {
        estado.text = "Tomando foto..."
        captura.takePicture(hilo, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val imagen = img.use { girar(it.toBitmap(), it.imageInfo.rotationDegrees) }
                analizarFoto("camara") { imagen }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread { estado.text = "Error al tomar la foto: ${e.message}" }
            }
        })
    }

    /** Foto o galería: analiza la imagen (reducida a 2048 px de lado mayor) y muestra el resultado. */
    private fun analizarFoto(origen: String, obtener: () -> Bitmap) {
        mostrandoFoto = true
        capa.mostrar(null)
        estado.text = "Analizando..."
        hilo.execute {
            try {
                val imagen = reducir(obtener(), 2048)
                val resultado = modelo.detectar(imagen)
                val segmentada = Dibujo.sobreFoto(imagen, resultado, modelo.cfg.nombres)
                val analizada = Analizada(imagen, segmentada, resultado, origen)
                runOnUiThread { mostrarFoto(analizada) }
            } catch (e: Exception) {
                runOnUiThread {
                    estado.text = "No se pudo analizar la imagen: ${e.message}"
                    volverACamara()
                }
            }
        }
    }

    private fun mostrarFoto(a: Analizada) {
        actual = a
        foto.setImageBitmap(a.segmentada)
        foto.visibility = View.VISIBLE
        bGuardar.isEnabled = true
        mostrarConteo(a.resultado)
        estado.text = "Foto %dx%d · %.0f ms · «Guardar» o «Cámara» para volver".format(
            a.original.width, a.original.height, a.resultado.totalMs)
    }

    /** Guarda la foto original, la segmentada y una fila en capturas.csv. */
    private fun guardar() {
        val a = actual ?: return
        bGuardar.isEnabled = false
        hilo.execute {
            val mensaje = try {
                val ahora = Date()
                val base = "rg6_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(ahora)
                val nombreOriginal = "${base}_original.jpg"
                val nombreSegmentada = "${base}_segmentada.jpg"
                guardarJpeg(this, "RG6Contador", nombreOriginal, a.original)
                guardarJpeg(this, "RG6Contador", nombreSegmentada, a.segmentada)

                val cuenta = a.resultado.conteo
                val umbrales = modelo.cfg.umbrales.joinToString("|") { "%.3f".format(Locale.US, it) }
                val fila = listOf(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(ahora), a.origen,
                    nombreOriginal, nombreSegmentada, a.original.width, a.original.height,
                    cuenta[0], cuenta[1], cuenta.sum(), "%.1f".format(Locale.US, a.resultado.totalMs), umbrales,
                )
                capturas.anexar(fila.joinToString(",") + "\n")
                "Guardado en Pictures/RG6Contador/ y Documents/RG6Contador/capturas.csv"
            } catch (e: Exception) {
                runOnUiThread { bGuardar.isEnabled = true }
                "No se pudo guardar: ${e.message}"
            }
            runOnUiThread { aviso(mensaje) }
        }
    }

    private fun cabeceraCsv() =
        "fecha,origen,foto_original,foto_segmentada,ancho,alto,n_rg6Bien,n_rg6Mal,total,ms,umbrales_bien_mal\n"

    private fun volverACamara() {
        mostrandoFoto = false
        actual = null
        bGuardar.isEnabled = false
        foto.visibility = View.GONE
        foto.setImageBitmap(null)
        mostrarConteo(null)
        if (!enVivo) estado.text = "Cámara · pulsa «En vivo» para detectar"
    }

    /** Muestra arriba el conteo de cada clase con su color, y el total. */
    private fun mostrarConteo(resultado: Resultado?) {
        val nombres = if (::modelo.isInitialized) modelo.cfg.nombres else listOf("rg6Bien", "rg6Mal")
        val cuenta = resultado?.conteo ?: IntArray(nombres.size)
        val texto = SpannableStringBuilder()
        for (k in nombres.indices) {
            val inicio = texto.length
            texto.append("${nombres[k]}: ${cuenta[k]}   ")
            val color = ForegroundColorSpan(COLORES[k % COLORES.size])
            texto.setSpan(color, inicio, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        texto.append("Total: ${cuenta.sum()}")
        conteo.text = texto
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        hilo.execute { if (::modelo.isInitialized) modelo.close() }
        hilo.shutdown()
    }
}

/** Reduce la imagen para que su lado mayor sea como máximo maxLado. Ahorra memoria (el modelo usa 1024). */
fun reducir(imagen: Bitmap, maxLado: Int): Bitmap {
    val ladoMayor = max(imagen.width, imagen.height)
    if (ladoMayor <= maxLado) return imagen
    val escala = maxLado.toFloat() / ladoMayor
    val ancho = (imagen.width * escala).toInt()
    val alto = (imagen.height * escala).toInt()
    return Bitmap.createScaledBitmap(imagen, ancho, alto, true)
}

/** Lee una foto de la galería ya girada según su EXIF (ImageDecoder lo hace solo), con 2048 px como máximo. */
fun leerImagen(ctx: Context, uri: Uri): Bitmap {
    val fuente = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(fuente) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE    // para poder leer los píxeles después
        val ladoMayor = max(info.size.width, info.size.height)
        if (ladoMayor > 2048) {
            val escala = 2048f / ladoMayor
            decoder.setTargetSize((info.size.width * escala).toInt(), (info.size.height * escala).toInt())
        }
    }
}
