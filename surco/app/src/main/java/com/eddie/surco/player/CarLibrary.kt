package com.eddie.surco.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.AnalysisStore
import com.eddie.surco.data.BrowseIds
import com.eddie.surco.data.BrowseList
import com.eddie.surco.data.BrowseNode
import com.eddie.surco.data.BrowseSong
import com.eddie.surco.data.BrowseTree
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.buckets
import java.io.File

/**
 * La biblioteca para Android Auto y los demás mandos que navegan: se arma con la caché que deja
 * la app (sin volver a leer etiquetas), con los mismos filtros (carpetas excluidas, audios
 * cortos) y lo analizado. Se rehace como mucho cada 30 s: navegar en el coche no relee nada.
 */
class CarLibrary(private val ctx: Context) {
    @Volatile private var built: Pair<Long, Built>? = null

    class Built(val tree: BrowseTree, val byId: Map<Long, Track>)

    /** Llamar fuera del hilo principal: lee la caché del disco. */
    fun get(): Built {
        built?.let { (at, b) -> if (System.currentTimeMillis() - at < 30_000) return b }
        val prefs = SurcoSettings.state.value
        val store = AnalysisStore(ctx)
        val tracks = LibraryScanner(ctx).loadCache()
            .filter { File(it.path).exists() }
            .filter { prefs.minDurationSec <= 0 || it.durationMs <= 0 || it.durationMs >= prefs.minDurationSec * 1000L }
            .filter { it.dir !in prefs.excludedFolders }
            .map { store.apply(it) }
            .sortedBy { com.eddie.surco.data.fold(it.title) }
        val user = UserStore.state.value
        val songs = tracks.map { t ->
            BrowseSong(
                id = t.id, title = t.title, artist = t.artist, album = t.album,
                albumKey = TagField.ALBUM.buckets(t).firstOrNull()?.takeIf { t.album.isNotBlank() }?.key,
                artists = TagField.ARTIST.buckets(t).filter { t.artist.isNotBlank() }.map { it.key to it.label },
                genres = TagField.GENRE.buckets(t).filter { t.genre.isNotBlank() }.map { it.key to it.label },
                bpm = TagField.BPM.buckets(t).firstOrNull()?.let { it.label to it.order },
                discNo = t.discNo, trackNo = t.trackNo, artKey = t.artKey,
            )
        }
        val smart = listOf(SmartList.FAVORITES, SmartList.MOST_PLAYED, SmartList.RECENT, SmartList.ADDED)
            .map { sl -> BrowseList("s:${sl.name}", sl.label, sl.tracks(tracks, user).map { it.id }) }
        val playlists = user.playlists.map { BrowseList("p:${it.id}", it.name, it.trackIds) }
        val b = Built(BrowseTree(songs, smart, playlists), tracks.associateBy { it.id })
        built = System.currentTimeMillis() to b
        return b
    }

    fun invalidate() { built = null }

    /** Un nodo del árbol como elemento de Media3 (con su portada y el estilo de cuadrícula o lista). */
    fun toItem(n: BrowseNode, byId: Map<Long, Track>): MediaItem {
        val t = n.songId?.let { byId[it] }
        val art = t?.takeIf { it.albumId > 0 }?.let { ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it.albumId) }
        val extras = Bundle().apply {
            if (n.browsable) {
                putInt(CONTENT_STYLE_BROWSABLE, if (n.grid) STYLE_GRID else STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
            }
        }
        val md = MediaMetadata.Builder()
            .setTitle(n.title)
            .setSubtitle(n.subtitle.ifBlank { null })
            .setArtist(if (n.playable) n.subtitle.ifBlank { null } else null)
            .setIsBrowsable(n.browsable)
            .setIsPlayable(n.playable)
            .setMediaType(if (n.playable) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setArtworkUri(art)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(n.id).setMediaMetadata(md).build()
    }

    companion object {
        // Las de MediaConstants (EXTRAS_KEY_CONTENT_STYLE_*), con su valor: son las de Android Auto.
        const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_LIST = 1
        const val STYLE_GRID = 2
        /** Prefijo de los resultados de una búsqueda: al tocar uno, suenan todos desde él. */
        const val SEARCH = "buscar:"
    }
}

/** (canciones, índice) de lo que se toca en el coche; null si no es algo del árbol. */
fun CarLibrary.Built.resolve(nodeId: String): Triple<List<Track>, Int, Boolean>? {
    BrowseIds.parseSong(nodeId)?.let { (parent, id) ->
        if (parent.startsWith(CarLibrary.SEARCH)) {
            val list = tree.search(parent.removePrefix(CarLibrary.SEARCH)).mapNotNull { byId[it.id] }
            val at = list.indexOfFirst { it.id == id }
            if (at >= 0) return Triple(list, at, false)
        }
    }
    val (ids, at, shuffle) = tree.playContext(nodeId) ?: return null
    val list = ids.mapNotNull { byId[it] }
    if (list.isEmpty()) return null
    return Triple(list, at.coerceIn(0, list.lastIndex), shuffle)
}
