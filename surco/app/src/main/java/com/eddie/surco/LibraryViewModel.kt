package com.eddie.surco

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.Lyrics
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.buckets
import com.eddie.surco.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

sealed interface Screen {
    data object Home : Screen
    data class Groups(val field: TagField) : Screen
    /** field/key nulos = todas las canciones. */
    data class Songs(val field: TagField?, val key: String?, val title: String) : Screen
    data class Smart(val list: SmartList) : Screen
    data class PlaylistView(val id: Long) : Screen
    data object Playlists : Screen
    data object Settings : Screen
    data object Equalizer : Screen
    data object Library : Screen
    data object Tags : Screen
    data object Search : Screen
}

/** Pestañas de la barra inferior, en el orden de uso: lo de cada día primero. */
enum class Tab(val label: String, val root: Screen) {
    HOME("Inicio", Screen.Home), LIBRARY("Biblioteca", Screen.Library), TAGS("Etiquetas", Screen.Tags), LISTS("Listas", Screen.Playlists),
}

enum class LibraryTab(val label: String) { SONGS("Canciones"), ALBUMS("Álbumes"), ARTISTS("Artistas"), FOLDERS("Carpetas") }

/** Menú de una canción; si viene de una lista, dónde está en ella (para poder quitarla). */
data class SongSheetState(val track: Track, val playlistId: Long? = null, val playlistIndex: Int = -1)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = LibraryScanner(app)

    init {
        SurcoSettings.init(app)
        UserStore.init(app)
    }

    val player = PlayerConnection(app)

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var query by mutableStateOf("")
    var nowPlayingOpen by mutableStateOf(false)
    var sheet by mutableStateOf<SongSheetState?>(null)
    var queueOpen by mutableStateOf(false)
    var sleepOpen by mutableStateOf(false)
    var speedOpen by mutableStateOf(false)
    /** Canciones que se van a añadir a una lista (abre el diálogo). */
    var addTo by mutableStateOf<List<Track>?>(null)

    private var scanJob: Job? = null

    var tab by mutableStateOf(Tab.HOME)
    var libraryTab by mutableStateOf(LibraryTab.ALBUMS)

    fun push(s: Screen) { stack += s }

    /** Cambiar de pestaña empieza en su raíz; tocar la pestaña en la que ya se está, también. */
    fun selectTab(t: Tab) {
        tab = t
        stack.clear(); stack.add(t.root)
    }

    /** Atrás: primero dentro de la pestaña, después a Inicio. */
    fun pop(): Boolean = when {
        stack.size > 1 -> { stack.removeAt(stack.lastIndex); true }
        tab != Tab.HOME -> { selectTab(Tab.HOME); true }
        else -> false
    }

    fun scan(force: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            if (tracks.value.isEmpty()) tracks.value = scanner.loadCache()
            val cached = if (force) emptyMap() else tracks.value.associateBy { it.id }
            val fresh = scanner.queryMediaStore().map { m ->
                cached[m.id]?.takeIf { it.enriched && it.dateModified == m.dateModified } ?: m
            }.sortedBy { it.title.lowercase() }
            tracks.value = fresh

            val todo = fresh.indices.filter { !fresh[it].enriched }
            if (todo.isNotEmpty()) {
                val list = fresh.toMutableList()
                val done = AtomicInteger(0)
                progress.value = 0 to todo.size
                // Lectura de etiquetas en paralelo, por bloques
                todo.chunked(64).forEach { chunk ->
                    chunk.map { i ->
                        async {
                            val r = scanner.enrich(list[i])
                            progress.value = done.incrementAndGet() to todo.size
                            i to r
                        }
                    }.awaitAll().forEach { (i, r) -> list[i] = r }
                    tracks.value = list.toList()
                }
            }
            progress.value = null
            scanner.saveCache(tracks.value)
        }
    }

    fun loadLyrics(t: Track): Lyrics? = scanner.loadLyrics(t)

    fun goToAlbum(t: Track) = TagField.ALBUM.buckets(t).firstOrNull()?.let { openTag(TagField.ALBUM, it.key, it.label) }
    fun goToArtist(t: Track) = TagField.ARTIST.buckets(t).firstOrNull()?.let { openTag(TagField.ARTIST, it.key, it.label) }

    fun openTag(field: TagField, key: String, label: String) {
        sheet = null
        nowPlayingOpen = false
        push(Screen.Songs(field, key, label))
    }

    override fun onCleared() { player.release() }
}
