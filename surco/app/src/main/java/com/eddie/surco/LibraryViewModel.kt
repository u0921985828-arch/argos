package com.eddie.surco

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
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
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = LibraryScanner(app)
    val player = PlayerConnection(app)

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var query by mutableStateOf("")
    var nowPlayingOpen by mutableStateOf(false)
    var sheetTrack by mutableStateOf<Track?>(null)

    private var scanJob: Job? = null

    fun push(s: Screen) { stack += s }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false

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

    fun openTag(field: TagField, key: String, label: String) {
        sheetTrack = null
        nowPlayingOpen = false
        push(Screen.Songs(field, key, label))
    }

    override fun onCleared() { player.release() }
}
