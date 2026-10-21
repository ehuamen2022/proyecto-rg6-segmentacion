package pe.tesis.rg6.comun

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore

/*
 * Archivos visibles desde el PC (cable USB, Almacenamiento interno): fotos en Pictures/<app>/ y tablas CSV en
 * Documents/<app>/. Se usa MediaStore, así que no hace falta pedir permiso de almacenamiento.
 */

/** CSV en Documents/<carpeta>/<nombre>: se crea con la cabecera la primera vez y luego se le añaden filas. */
class CsvPublico(private val ctx: Context, carpeta: String, private val nombre: String, private val cabecera: () -> String) {
    private val ruta = "Documents/$carpeta/"
    private var uri: Uri? = null

    fun anexar(texto: String) {
        val u = uri ?: buscar() ?: crear()
        uri = u
        ctx.contentResolver.openOutputStream(u, "wa")!!.use { it.write(texto.toByteArray()) }
    }

    /** El archivo que esta app creó antes (si se reinstala la app, Android crea otro: "nombre (1).csv"). */
    private fun buscar(): Uri? {
        val coleccion = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ctx.contentResolver.query(coleccion, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(ruta, nombre), null)?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(coleccion, c.getLong(0).toString())
        }
        return null
    }

    private fun crear(): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
        }
        val u = ctx.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)!!
        ctx.contentResolver.openOutputStream(u, "w")!!.use { it.write(cabecera().toByteArray()) }
        return u
    }
}

/** Guarda una imagen JPEG en Pictures/<carpeta>/<nombre>. */
fun guardarJpeg(ctx: Context, carpeta: String, nombre: String, bmp: Bitmap) {
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$carpeta/")
        put(MediaStore.Images.Media.IS_PENDING, 1)               // invisible hasta terminar de escribir
    }
    val r = ctx.contentResolver
    val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
    r.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    r.update(u, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
}
