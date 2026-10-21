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
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import pe.tesis.rg6.comun.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/** Una foto ya analizada, lista para «Guardar». */
class Analizada(val original: Bitmap, val segmentada: Bitmap, val r: Resultado, val origen: String)

/**
 * RG6 Contador: detecta y cuenta conectores rg6Bien / rg6Mal con el modelo INT8 del OE3.
 *  - En vivo: la cámara analiza continuamente (el botón lo activa o desactiva).
 *  - Foto: captura a resolución completa y muestra el resultado congelado.
 *  - Galería: analiza una foto del celular.
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
    private lateinit var bGuardar: Button
    private val captura = Camara.nuevaCaptura()
    private val capturas by lazy { CsvPublico(this, "RG6Contador", "capturas.csv", ::cabeceraCsv) }
    @Volatile private var enVivo = true
    @Volatile private var mostrandoFoto = false
    private var actual: Analizada? = null                       // la foto que se ve en pantalla

    private val galeria = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analizarFoto("galeria") { leerImagen(this, uri) }
    }
    private val permisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Camara.iniciar(this, vista, hilo, ::analizarCuadro, captura)
        else estado.text = "Sin permiso de cámara: solo funciona Galería."
    }

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        conteo = TextView(this).apply { textSize = 26f; setPadding(24, 48, 24, 8); setTextColor(Color.WHITE) }
        estado = TextView(this).apply { textSize = 13f; setPadding(24, 0, 24, 8); setTextColor(Color.LTGRAY) }
        vista = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        capa = Overlay(this)
        foto = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        val area = FrameLayout(this).apply { addView(vista); addView(capa); addView(foto) }

        val boton = { t: String, accion: Button.() -> Unit ->
            Button(this).apply { text = t; textSize = 13f; isAllCaps = false; setOnClickListener { accion() } }
        }
        val bVivo = boton("En vivo: SÍ") {
            enVivo = !enVivo; text = if (enVivo) "En vivo: SÍ" else "En vivo: NO"; if (!enVivo) capa.mostrar(null)
        }
        val bFoto = boton("Foto") { tomarFoto() }
        val bGaleria = boton("Galería") { galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        val bCamara = boton("Cámara") { volverACamara() }
        bGuardar = boton("Guardar") { guardar() }.apply { isEnabled = false }
        val fila = { botones: List<Button> ->
            LinearLayout(this).apply { botones.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) } }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            addView(conteo); addView(estado)
            addView(area, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(fila(listOf(bVivo, bFoto, bGaleria)))
            addView(fila(listOf(bCamara, bGuardar)), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 48 })
        })
        mostrarConteo(null)
        estado.text = "Cargando modelo..."

        hilo.execute {
            modelo = Modelo(this, "yolo11nseg_int8_conv_only_dyn.onnx")
            runOnUiThread {
                estado.text = "Modelo: ${modelo.descripcion}\nUmbrales: " +
                    modelo.cfg.nombres.indices.joinToString(" · ") { "${modelo.cfg.nombres[it]} ${"%.2f".format(modelo.cfg.umbrales[it])}" } +
                    " · IoU NMS ${modelo.cfg.iouNms}"
                permisoCamara.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /** Modo en vivo: cada cuadro de la cámara (en el hilo de inferencia). */
    private fun analizarCuadro(img: ImageProxy) {
        img.use {
            if (!enVivo || mostrandoFoto || !::modelo.isInitialized) return
            val bmp = girar(it.toBitmap(), it.imageInfo.rotationDegrees)
            val r = modelo.detectar(bmp)
            runOnUiThread {
                if (enVivo && !mostrandoFoto) {
                    capa.mostrar(r, modelo.cfg.nombres)
                    mostrarConteo(r)
                    estado.text = "En vivo · %.0f ms por cuadro · «Foto» para guardar evidencia".format(r.totalMs)
                }
            }
        }
    }

    private fun tomarFoto() {
        estado.text = "Tomando foto..."
        captura.takePicture(hilo, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val bmp = img.use { girar(it.toBitmap(), it.imageInfo.rotationDegrees) }
                analizarFoto("camara") { bmp }
            }
            override fun onError(e: ImageCaptureException) = runOnUiThread { estado.text = "Error al tomar la foto: ${e.message}" }
        })
    }

    /** Foto o galería: analiza la imagen (reducida a 2048 px de lado mayor) y muestra el resultado. */
    private fun analizarFoto(origen: String, obtener: () -> Bitmap) {
        mostrandoFoto = true
        capa.mostrar(null)
        estado.text = "Analizando..."
        hilo.execute {
            try {
                val img = reducir(obtener(), 2048)
                val r = modelo.detectar(img)
                val a = Analizada(img, Dibujo.sobreFoto(img, r, modelo.cfg.nombres), r, origen)
                runOnUiThread {
                    actual = a
                    foto.setImageBitmap(a.segmentada)
                    foto.visibility = View.VISIBLE
                    bGuardar.isEnabled = true
                    mostrarConteo(r)
                    estado.text = "Foto %dx%d · %.0f ms · «Guardar» o «Cámara» para volver".format(img.width, img.height, r.totalMs)
                }
            } catch (e: Exception) {
                runOnUiThread { estado.text = "No se pudo analizar la imagen: ${e.message}"; volverACamara() }
            }
        }
    }

    /** Guarda la foto original, la segmentada y una fila en capturas.csv. */
    private fun guardar() {
        val a = actual ?: return
        bGuardar.isEnabled = false
        hilo.execute {
            val msg = try {
                val ahora = Date()
                val base = "rg6_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(ahora)
                guardarJpeg(this, "RG6Contador", "${base}_original.jpg", a.original)
                guardarJpeg(this, "RG6Contador", "${base}_segmentada.jpg", a.segmentada)
                val c = a.r.conteo
                capturas.anexar(listOf(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(ahora), a.origen,
                    "${base}_original.jpg", "${base}_segmentada.jpg", a.original.width, a.original.height,
                    c[0], c[1], c.sum(), "%.1f".format(Locale.US, a.r.totalMs),
                    modelo.cfg.umbrales.joinToString("|") { "%.3f".format(Locale.US, it) },
                ).joinToString(",") + "\n")
                "Guardado en Pictures/RG6Contador/ y Documents/RG6Contador/capturas.csv"
            } catch (e: Exception) {
                runOnUiThread { bGuardar.isEnabled = true }
                "No se pudo guardar: ${e.message}"
            }
            runOnUiThread { toast(msg) }
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

    private fun mostrarConteo(r: Resultado?) {
        val n = if (::modelo.isInitialized) modelo.cfg.nombres else listOf("rg6Bien", "rg6Mal")
        val c = r?.conteo ?: IntArray(n.size)
        val s = SpannableStringBuilder()
        n.indices.forEach { k ->
            val ini = s.length
            s.append("${n[k]}: ${c[k]}   ")
            s.setSpan(ForegroundColorSpan(COLORES[k % COLORES.size]), ini, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        s.append("Total: ${c.sum()}")
        conteo.text = s
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        hilo.execute { if (::modelo.isInitialized) modelo.close() }
        hilo.shutdown()
    }
}

/** Reduce la imagen para que su lado mayor sea como máximo maxLado (ahorra memoria; el modelo usa 1024). */
fun reducir(b: Bitmap, maxLado: Int): Bitmap {
    val m = max(b.width, b.height)
    if (m <= maxLado) return b
    val s = maxLado.toFloat() / m
    return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
}

/** Lee una foto de la galería ya girada según su EXIF (ImageDecoder lo hace solo). */
fun leerImagen(ctx: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val m = max(info.size.width, info.size.height)
        if (m > 2048) { val s = 2048f / m; dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
    }