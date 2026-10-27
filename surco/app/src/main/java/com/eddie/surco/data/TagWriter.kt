package com.eddie.surco.data

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Escribe las etiquetas en el archivo de una canción. Desde Android 10 los archivos no son de
 * Surco: hace falta que el usuario lo permita (Android 11+: `createWriteRequest`; Android 10: la
 * acción de `RecoverableSecurityException`; Android 8–9: el permiso de almacenamiento).
 *
 * jaudiotagger necesita un `File` en el que pueda escribir, y el original no lo es: se copia el
 * archivo a la caché, se escribe la copia, se comprueba que se sigue abriendo y se vuelca sobre
 * el original por MediaStore («wt»: truncando; «w» no trunca en Android 10+ y dejaría basura al
 * final si el archivo encoge). Si el volcado falla a medias, se vuelve a poner el original.
 * Todo esto bloquea: llamar fuera del hilo principal.
 */
object TagWriter {
    sealed interface Result {
        /** Escrito; [size]: lo que mide ahora el archivo. */
        class Done(val size: Long) : Result
        /** Falta el permiso del usuario: lanzar [sender] y, si dice que sí, volver a llamar a [write]. */
        class NeedsConsent(val sender: IntentSender) : Result
        class Failed(val reason: Reason) : Result
    }

    enum class Reason { UNSUPPORTED, NO_ACCESS, NO_SPACE, WRITE }

    private const val LOG = "SurcoTags"
    /** Una escritura a la vez: dos a la vez se pisarían la caché y el espacio libre. */
    private val lock = Any()

    /** Lo que dice cada campo en el archivo, o null si no se puede editar (Opus, roto, ilegible). */
    fun load(t: Track): Map<TagKey, String>? = readTagValues(File(t.path))

    fun write(ctx: Context, t: Track, changes: Map<TagKey, String>): Result = synchronized(lock) {
        if (!isWritableFormat(t.path)) return Result.Failed(Reason.UNSUPPORTED)
        val cr = ctx.contentResolver
        val uri = t.uri
        // ¿Se puede escribir? Antes de copiar nada. «rw» abre para escribir sin truncar.
        try {
            val fd = cr.openFileDescriptor(uri, "rw") ?: return Result.Failed(Reason.NO_ACCESS)
            fd.close()
        } catch (e: SecurityException) {
            return consent(cr, uri, e)
        } catch (e: FileNotFoundException) {
            Log.w(LOG, "no se abre ${t.path}", e)
            return Result.Failed(Reason.NO_ACCESS)
        }
        if (changes.isEmpty()) return Result.Done(t.size)

        // Sin AWT (Android no lo tiene) para las portadas que jaudiotagger encuentre al guardar.
        TagOptionSingleton.getInstance().isAndroid = true
        val dir = File(ctx.cacheDir, "etiquetas").apply { deleteRecursively(); mkdirs() }
        try {
            val ext = t.path.substringAfterLast('.').lowercase()
            val size = File(t.path).length().takeIf { it > 0 } ?: t.size
            // El original y la copia que se edita, y algo de margen por si la etiqueta crece.
            if (dir.usableSpace < 2 * size + (4 shl 20)) return Result.Failed(Reason.NO_SPACE)
            val original = File(dir, "original.$ext")
            val edited = File(dir, "editada.$ext")
            val read = cr.openInputStream(uri)?.use { input -> original.outputStream().use { input.copyTo(it) } }
            if (read == null || read <= 0) return Result.Failed(Reason.NO_ACCESS)
            original.copyTo(edited)
            try {
                writeTagValues(edited, changes)
                // Si jaudiotagger ya no la abre, el original no se toca.
                checkNotNull(readTagValues(edited)) { "la copia editada no se abre" }
            } catch (e: Exception) {
                Log.w(LOG, "no se pudo escribir la copia de ${t.path}", e)
                return Result.Failed(Reason.WRITE)
            }
            try {
                putOver(cr, uri, edited)
            } catch (e: SecurityException) {
                // Al abrir, antes de truncar: el original sigue entero.
                return consent(cr, uri, e)
            } catch (e: Exception) {
                Log.w(LOG, "volcado a medias de ${t.path}: se repone el original", e)
                runCatching { putOver(cr, uri, original) }.onFailure { Log.e(LOG, "no se pudo reponer ${t.path}", it) }
                return Result.Failed(Reason.WRITE)
            }
            // Android 11+ vuelve a leer el archivo al cerrarlo; antes hay que avisar a MediaStore.
            if (Build.VERSION.SDK_INT < 30) MediaScannerConnection.scanFile(ctx, arrayOf(t.path), null, null)
            return Result.Done(edited.length())
        } catch (e: IOException) {
            Log.w(LOG, "no se pudo copiar ${t.path}", e)
            return Result.Failed(Reason.WRITE)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun consent(cr: ContentResolver, uri: Uri, e: SecurityException): Result = when {
        Build.VERSION.SDK_INT >= 30 -> Result.NeedsConsent(MediaStore.createWriteRequest(cr, listOf(uri)).intentSender)
        Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException -> Result.NeedsConsent(e.userAction.actionIntent.intentSender)
        else -> Result.Failed(Reason.NO_ACCESS)
    }

    /** Vuelca [from] sobre el archivo de [uri], truncando, y lo lleva al disco antes de cerrarlo. */
    private fun putOver(cr: ContentResolver, uri: Uri, from: File) {
        val out = cr.openOutputStream(uri, "wt") ?: throw FileNotFoundException(uri.toString())
        out.use { o ->
            val n = from.inputStream().use { it.copyTo(o) }
            if (n != from.length()) throw IOException("escritos $n de ${from.length()} bytes")
            o.flush()
            (o as? FileOutputStream)?.fd?.let { fd -> runCatching { fd.sync() } }
        }
    }
}
