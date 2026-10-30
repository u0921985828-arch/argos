package com.eddie.surco.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.data.M3uSong
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.decodeM3u
import com.eddie.surco.data.dirFromDocumentId
import com.eddie.surco.data.m3uFileName
import com.eddie.surco.data.matchM3u
import com.eddie.surco.data.parseM3u
import com.eddie.surco.data.playlistNameFromFile
import com.eddie.surco.data.writeM3u8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/*
 * Listas M3U/M3U8 con el selector de archivos del sistema: importar las de otro reproductor o de un
 * PC y exportar las de Surco. Lo que se lee y escribe va fuera del hilo principal y en el ámbito del
 * ViewModel: si se cambia de pantalla mientras tanto, la lista se crea igual y el aviso sale.
 */

// Cada selector y cada app de archivos llama distinto a las M3U; «*/*» de reserva, porque muchos
// (y Drive) no les ponen tipo y sin él no se podrían elegir.
private val M3U_TYPES = arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "*/*")

/** Una lista de verdad no pasa de unos cientos de KB: así, elegir un vídeo por error no llena la memoria. */
private const val MAX_BYTES = 8 shl 20

private fun Track.m3u() = M3uSong(id, path, title, artist, durationMs)

private class M3uFile(val name: String, val text: String, val dir: String?)

private fun InputStream.readLimited(max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
    return out.toByteArray()
}

/** Nombre, texto y, si el proveedor la deja deducir, la carpeta real (para las rutas relativas). */
private fun readM3u(ctx: Context, uri: Uri): M3uFile? = runCatching {
    val cr = ctx.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()
    val bytes = cr.openInputStream(uri)?.use { it.readLimited(MAX_BYTES) } ?: return@runCatching null
    val dir = if (DocumentsContract.isDocumentUri(ctx, uri)) dirFromDocumentId(DocumentsContract.getDocumentId(uri)) else null
    M3uFile(name, decodeM3u(bytes), dir)
}.getOrNull()

private suspend fun importM3u(ctx: Context, uri: Uri, vm: LibraryViewModel) {
    val library = vm.tracks.value
    val read = withContext(Dispatchers.IO) {
        val f = readM3u(ctx, uri) ?: return@withContext null
        f to matchM3u(parseM3u(f.text), library.map { it.m3u() }, f.dir)
    }
    if (read == null) { Notice.show(Res.str(R.string.m3u_read_error)); return }
    val (file, match) = read
    val name = playlistNameFromFile(file.name).ifBlank { Res.str(R.string.m3u_default_name) }
    // Sin ninguna canción no se crea una lista vacía: se dice por qué.
    if (match.ids.isEmpty()) {
        Notice.show(
            if (match.missing == 0) Res.str(R.string.m3u_empty, name)
            else Res.str(R.string.m3u_none_found, name, Res.plural(R.plurals.m3u_not_found, match.missing)),
            long = true,
        )
        return
    }
    val id = UserStore.createPlaylist(name, match.ids)
    val text = if (match.missing == 0) Res.str(R.string.m3u_imported, name, songs(match.ids.size))
    else Res.str(R.string.m3u_imported_missing, name, songs(match.ids.size), Res.plural(R.plurals.m3u_not_found, match.missing))
    Notice.show(text, Res.str(R.string.m3u_view)) { vm.openPlaylist(id) }
}

private suspend fun exportM3u(ctx: Context, uri: Uri, name: String, items: List<M3uSong>) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            // «wt» vacía el archivo si ya existía; hay proveedores que solo aceptan «w».
            val out = runCatching { cr.openOutputStream(uri, "wt") }.getOrNull() ?: cr.openOutputStream(uri, "w")
            if (out == null) false else { out.use { it.write(writeM3u8(items).toByteArray(Charsets.UTF_8)) }; true }
        }.getOrDefault(false)
    }
    Notice.show(if (ok) Res.str(R.string.m3u_exported, name, songs(items.size)) else Res.str(R.string.m3u_write_error))
}

/** Abre el selector para importar una lista; devuelve la acción para un botón. */
@Composable
fun rememberM3uImport(vm: LibraryViewModel): () -> Unit {
    val ctx = LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.viewModelScope.launch { importM3u(ctx, uri, vm) }
    }
    return {
        try { launcher.launch(M3U_TYPES) } catch (_: ActivityNotFoundException) { Notice.show(Res.str(R.string.m3u_no_picker)) }
    }
}

/** Abre el selector para guardar [tracks] como «[name].m3u8»; devuelve la acción para el menú. */
@Composable
fun rememberM3uExport(vm: LibraryViewModel, name: String, tracks: List<Track>): () -> Unit {
    val ctx = LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) {
            val items = tracks.map { it.m3u() }
            vm.viewModelScope.launch { exportM3u(ctx, uri, name, items) }
        }
    }
    return {
        try { launcher.launch(m3uFileName(name)) } catch (_: ActivityNotFoundException) { Notice.show(Res.str(R.string.m3u_no_picker)) }
    }
}
