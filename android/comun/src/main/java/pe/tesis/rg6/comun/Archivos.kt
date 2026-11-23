package pe.tesis.rg6.comun

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore

/*
 * Archivos que se ven desde el PC (cable USB, Almacenamiento interno): fotos en Pictures/<app>/ y tablas CSV
 * en Documents/<app>/. Se usa MediaStore, así no hace falta pedir permiso de almacenamiento.
 */

/** CSV en Documents/<carpeta>/<nombre>: la primera vez se crea con la cabecera y después solo se añaden filas. */
class CsvPublico(private val ctx: Context, carpeta: String, private val nombre: String, private val cabecera: () -> String) {
    private val ruta = "Documents/$carpeta/"
    private var uri: Uri? = null

    fun anexar(texto: String) {
        val archivo = uri ?: buscar() ?: crear()
        uri = archivo
        // "wa": se escribe al final sin borrar lo anterior
        ctx.contentResolver.openOutputStream(archivo, "wa")!!.use { it.write(texto.toByteArray()) }
    }

    /** Busca el archivo que esta app creó antes. Si se reinstala la app, Android crea otro: "nombre (1).csv". */
    private fun buscar(): Uri? {
        val coleccion = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columnas = arrayOf(MediaStore.MediaColumns._ID)
        val filtro = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val cursor = ctx.contentResolver.query(coleccion, columnas, filtro, arrayOf(ruta, nombre), null) ?: return null
        cursor.use {
            if (it.moveToFirst()) return Uri.withAppendedPath(coleccion, it.getLong(0).toString())
        }
        return null
    }

    private fun crear(): Uri {
        val datos = ContentValues()
        datos.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
        datos.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
        datos.put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
        val coleccion = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val archivo = ctx.contentResolver.insert(coleccion, datos)!!
        ctx.contentResolver.openOutputStream(archivo, "w")!!.use { it.write(cabecera().toByteArray()) }
        return archivo
    }
}

/** Guarda una imagen JPEG en Pictures/<carpeta>/<nombre>. */
fun guardarJpeg(ctx: Context, carpeta: String, nombre: String, imagen: Bitmap) {
    val datos = ContentValues()
    datos.put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
    datos.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    datos.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$carpeta/")
    datos.put(MediaStore.Images.Media.IS_PENDING, 1)    // no aparece en la galería hasta terminar de escribir
    val resolver = ctx.contentResolver
    val archivo = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, datos)!!
    resolver.openOutputStream(archivo)!!.use { imagen.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    val listo = ContentValues()
    listo.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(archivo, listo, null, null)
}
