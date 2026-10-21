package pe.tesis.rg6.comun

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.min

val COLORES = intArrayOf(Color.rgb(0, 200, 83), Color.rgb(255, 23, 68))    // rg6Bien verde, rg6Mal rojo

/**
 * Dibuja máscaras, cajas y etiquetas de un Resultado.
 * imagenADestino: transforma coordenadas de la FOTO original a las del lienzo destino.
 */
object Dibujo {
    private val pMascara = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pCaja = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val pFondo = Paint()

    fun dibujar(c: Canvas, r: Resultado, nombres: List<String>, imagenADestino: Matrix) {
        // entrada del modelo -> foto original (deshace el letterbox)
        val entradaAImagen = Matrix().apply {
            setTranslate(-r.lb.izq.toFloat(), -r.lb.arr.toFloat())
            postScale(1f / r.lb.r, 1f / r.lb.r)
        }
        val total = Matrix(entradaAImagen).apply { postConcat(imagenADestino) }
        val escala = total.mapRadius(1f)
        pCaja.strokeWidth = (3f * escala).coerceIn(3f, 8f)
        pTexto.textSize = (16f * escala).coerceIn(28f, 46f)

        for (d in r.dets) {
            val color = COLORES[d.clase % COLORES.size]
            // máscara (resolución entrada/4) -> entrada (x4) -> destino
            val bmp = mascaraBitmap(d, Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)))
            c.save()
            c.concat(total)
            c.scale(4f, 4f)
            c.drawBitmap(bmp, 0f, 0f, pMascara)
            c.restore()
            val caja = RectF(d.caja.x1, d.caja.y1, d.caja.x2, d.caja.y2).also { total.mapRect(it) }
            pCaja.color = color
            c.drawRect(caja, pCaja)
            val etiqueta = "${nombres[d.clase]} %.2f".format(d.score)
            val ancho = pTexto.measureText(etiqueta)
            pFondo.color = color
            c.drawRect(caja.left, caja.top - pTexto.textSize - 6, caja.left + ancho + 10, caja.top, pFondo)
            c.drawText(etiqueta, caja.left + 5, caja.top - 6, pTexto)
        }
    }

    private fun mascaraBitmap(d: Deteccion, color: Int): Bitmap {
        val px = IntArray(d.mascara.size) { if (d.mascara[it]) color else 0 }
        return Bitmap.createBitmap(px, d.mascaraAncho, d.mascaraAlto, Bitmap.Config.ARGB_8888)
    }

    /** Copia de la foto con las detecciones dibujadas encima (foto segmentada). */
    fun sobreFoto(foto: Bitmap, r: Resultado, nombres: List<String>): Bitmap {
        val out = foto.copy(Bitmap.Config.ARGB_8888, true)
        dibujar(Canvas(out), r, nombres, Matrix())
        return out
    }
}

/** Capa transparente sobre la vista de la cámara que dibuja las detecciones del cuadro en vivo. */
class Overlay(ctx: Context) : View(ctx) {
    @Volatile private var res: Resultado? = null
    private var nombres: List<String> = emptyList()

    fun mostrar(r: Resultado?, n: List<String> = nombres) { res = r; nombres = n; postInvalidate() }

    override fun onDraw(c: Canvas) {
        val r = res ?: return
        // el cuadro se ve con FIT_CENTER (igual que la vista previa, ambas 4:3)
        val s = min(width / r.anchoImg.toFloat(), height / r.altoImg.toFloat())
        val ox = (width - r.anchoImg * s) / 2
        val oy = (height - r.altoImg * s) / 2
        Dibujo.dibujar(c, r, nombres, Matrix().apply { setScale(s, s); postTranslate(ox, oy) })
    }
}
