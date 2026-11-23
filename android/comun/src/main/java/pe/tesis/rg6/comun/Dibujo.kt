package pe.tesis.rg6.comun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

// rg6Bien en verde y rg6Mal en rojo
val COLORES = intArrayOf(Color.rgb(0, 200, 83), Color.rgb(255, 23, 68))

/**
 * Dibuja las máscaras, cajas y etiquetas de un Resultado.
 * fotoADestino: pasa coordenadas de la foto original a las del lienzo donde se dibuja.
 */
object Dibujo {
    private val pinturaMascara = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pinturaCaja = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pinturaTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val pinturaFondo = Paint()

    fun dibujar(lienzo: Canvas, resultado: Resultado, nombres: List<String>, fotoADestino: Matrix) {
        // de la entrada del modelo a la foto original (se deshace el letterbox)
        val lb = resultado.letterbox
        val entradaAFoto = Matrix()
        entradaAFoto.setTranslate(-lb.izquierda.toFloat(), -lb.arriba.toFloat())
        entradaAFoto.postScale(1f / lb.escala, 1f / lb.escala)
        // y de la foto al lienzo
        val transformacion = Matrix(entradaAFoto)
        transformacion.postConcat(fotoADestino)

        val escala = transformacion.mapRadius(1f)
        pinturaCaja.strokeWidth = (3f * escala).coerceIn(3f, 8f)
        pinturaTexto.textSize = (16f * escala).coerceIn(28f, 46f)

        for (d in resultado.detecciones) {
            val color = COLORES[d.clase % COLORES.size]

            // la máscara está a 1/4 de la entrada: se agranda x4 y se lleva al lienzo
            val colorMascara = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
            val imagenMascara = mascaraABitmap(d, colorMascara)
            lienzo.save()
            lienzo.concat(transformacion)
            lienzo.scale(4f, 4f)
            lienzo.drawBitmap(imagenMascara, 0f, 0f, pinturaMascara)
            lienzo.restore()

            // caja
            val caja = RectF(d.caja.x1, d.caja.y1, d.caja.x2, d.caja.y2)
            transformacion.mapRect(caja)
            pinturaCaja.color = color
            lienzo.drawRect(caja, pinturaCaja)

            // etiqueta con la clase y la puntuación
            val etiqueta = "${nombres[d.clase]} %.2f".format(d.score)
            val anchoTexto = pinturaTexto.measureText(etiqueta)
            pinturaFondo.color = color
            lienzo.drawRect(caja.left, caja.top - pinturaTexto.textSize - 6, caja.left + anchoTexto + 10, caja.top, pinturaFondo)
            lienzo.drawText(etiqueta, caja.left + 5, caja.top - 6, pinturaTexto)
        }
    }

    private fun mascaraABitmap(d: Deteccion, color: Int): Bitmap {
        val pixeles = IntArray(d.mascara.size)
        for (i in pixeles.indices) {
            if (d.mascara[i]) pixeles[i] = color
        }
        return Bitmap.createBitmap(pixeles, d.mascaraAncho, d.mascaraAlto, Bitmap.Config.ARGB_8888)
    }

    /** Copia de la foto con las detecciones dibujadas encima (la foto segmentada). */
    fun sobreFoto(foto: Bitmap, resultado: Resultado, nombres: List<String>): Bitmap {
        val copia = foto.copy(Bitmap.Config.ARGB_8888, true)
        dibujar(Canvas(copia), resultado, nombres, Matrix())
        return copia
    }
}

/** Capa transparente encima de la cámara que dibuja las detecciones del cuadro en vivo. */
class Overlay(ctx: Context) : View(ctx) {
    @Volatile private var resultado: Resultado? = null
    private var nombres: List<String> = emptyList()

    fun mostrar(nuevo: Resultado?, nombresClases: List<String> = nombres) {
        resultado = nuevo
        nombres = nombresClases
        postInvalidate()
    }

    override fun onDraw(lienzo: Canvas) {
        val r = resultado ?: return
        // la vista previa usa FIT_CENTER: la imagen se escala sin deformar y se centra (las dos son 4:3)
        val escala = min(width / r.anchoImg.toFloat(), height / r.altoImg.toFloat())
        val margenX = (width - r.anchoImg * escala) / 2
        val margenY = (height - r.altoImg * escala) / 2
        val fotoAPantalla = Matrix()
        fotoAPantalla.setScale(escala, escala)
        fotoAPantalla.postTranslate(margenX, margenY)
        Dibujo.dibujar(lienzo, r, nombres, fotoAPantalla)
    }
}
