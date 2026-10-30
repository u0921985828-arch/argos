package com.eddie.surco.player

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.FileNotFoundException

/**
 * Portadas para Android Auto (y la notificación de otros mandos): el coche no tiene permiso para
 * leer MediaStore, así que pide `content://com.eddie.surco.arte/<id de canción>` y Surco le da
 * un JPEG de 512 px, guardado en la caché para la próxima vez. Solo da portadas, de solo lectura.
 */
class ArtProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.eddie.surco.arte"
        fun uri(trackId: Long): Uri = Uri.parse("content://$AUTHORITY/$trackId")
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("solo lectura")
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val dir = File(ctx.cacheDir, "arte").apply { mkdirs() }
        val f = File(dir, "$id.jpg")
        if (!f.exists() || f.length() == 0L) {
            val track = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val bmp: Bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(track, Size(512, 512), null)
                else MediaMetadataRetriever().run {
                    try { setDataSource(ctx, track); embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { release() }
                }
            }.getOrNull() ?: throw FileNotFoundException("sin portada: $id")
            val tmp = File(dir, "$id.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(f)
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
