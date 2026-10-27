package com.eddie.surco

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.AnalysisStore
import com.eddie.surco.data.AudioAnalyzer
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.needsAnalysis
import com.eddie.surco.data.Lyrics
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.buckets
import com.eddie.surco.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock
import android.util.Log

sealed interface Screen {
    data object Home : Screen
    data class Groups(val field: TagField) : Screen
    /** field/key nulos = todas las canciones. */
    data class Songs(val field: TagField?, val key: String?, val title: String) : Screen
    data class Smart(val list: SmartList) : Screen
    data class PlaylistView(val id: Long) : Screen
    data object Playlists : Screen
    /** Canciones que se pueden mezclar con [id]: tonalidad vecina en la rueda y tempo parecido. */
    data class Compatible(val id: Long) : Screen
    /** Ajustes: sin página, la lista de secciones. */
    data class Settings(val page: com.eddie.surco.ui.SettingsPage? = null) : Screen
    data object Equalizer : Screen
    data object Library : Screen
    data object Tags : Screen
    data object Search : Screen
    /** Editar las etiquetas de una canción (ui/TagEditor.kt). */
    data class TagEditor(val id: Long) : Screen
}

/** Pestañas de la barra inferior, en el orden de uso: lo de cada día primero. */
enum class Tab(@StringRes private val labelRes: Int, val root: Screen) {
    HOME(R.string.app_tab_home, Screen.Home), LIBRARY(R.string.app_tab_library, Screen.Library),
    TAGS(R.string.app_tab_tags, Screen.Tags), LISTS(R.string.app_tab_lists, Screen.Playlists);

    /** El nombre en el idioma de la app (se lee al pintar: cambia con el idioma). */
    val label: String get() = Res.str(labelRes)
}

enum class AlbumSort(@StringRes private val labelRes: Int) {
    TITLE(R.string.app_sort_title), ARTIST(R.string.app_sort_artist), YEAR(R.string.app_sort_year), ADDED(R.string.app_sort_added);

    val label: String get() = Res.str(labelRes)
}

enum class LibraryTab(@StringRes private val labelRes: Int) {
    SONGS(R.string.app_lib_songs), ALBUMS(R.string.app_lib_albums), ARTISTS(R.string.app_lib_artists), FOLDERS(R.string.app_lib_folders);

    val label: String get() = Res.str(labelRes)
}

/** Menú de una canción; si viene de una lista, dónde está en ella (para poder quitarla). */
data class SongSheetState(val track: Track, val playlistId: Long? = null, val playlistIndex: Int = -1, val fromPlayer: Boolean = false)

/**
 * Lectores de etiquetas a la vez. Leer es sobre todo esperar al almacenamiento (en Android 11+ cada
 * archivo pasa por FUSE): más lectores que núcleos; no más de 8 por la memoria de las portadas
 * incrustadas, que jaudiotagger lee enteras.
 */
private val TAG_READERS = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(4, 8)

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
    var albumSort by mutableStateOf(AlbumSort.TITLE)

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

    /** Análisis de BPM y tonalidad: (hechas, total) mientras dura. */
    val analysis = MutableStateFlow<Pair<Int, Int>?>(null)
    private var analysisJob: Job? = null
    private val store by lazy { AnalysisStore(getApplication<Application>()) }

    /**
     * Analiza, una a una y con prioridad baja, las canciones a las que les falta BPM o tonalidad.
     * Lo hecho se aplica a la biblioteca cada 10 canciones y se guarda; se puede cortar en Ajustes.
     */
    fun analyzeLibrary() {
        if (!SurcoSettings.state.value.analyze || analysisJob?.isActive == true || !fresh.value) return
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            tracks.value = tracks.value.map { store.apply(it) }
            val todo = tracks.value.filter { it.needsAnalysis() && !store.has(it) }
            if (todo.isEmpty()) return@launch
            analysis.value = 0 to todo.size
            try {
                todo.forEachIndexed { i, t ->
                    if (!SurcoSettings.state.value.analyze) return@launch
                    store.put(t, AudioAnalyzer.analyze(getApplication<Application>(), t))
                    analysis.value = i + 1 to todo.size
                    if ((i + 1) % 10 == 0 || i == todo.lastIndex) {
                        tracks.value = tracks.value.map { store.apply(it) }
                        store.save()
                    }
                }
                scanner.saveCache(tracks.value)
            } finally {
                analysis.value = null
                store.save()
            }
        }
    }

    fun stopAnalysis() { analysisJob?.cancel() }

    /** true cuando la lista ya viene de MediaStore (no solo de la caché): hasta entonces no se restaura la cola. */
    val fresh = MutableStateFlow(false)
    private var scannedOnce = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tagReaders = Dispatchers.IO.limitedParallelism(TAG_READERS)

    /** Una vez por proceso (girar la pantalla no vuelve a escanear); [force] relee todo. */
    fun scan(force: Boolean = false) {
        if (scanJob?.isActive == true || (scannedOnce && !force)) return
        scannedOnce = true
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            if (tracks.value.isEmpty()) tracks.value = scanner.loadCache()
            val cached = if (force) emptyMap() else tracks.value.associateBy { it.id }
            val current = scanner.queryMediaStore().map { m ->
                cached[m.id]?.takeIf { it.enriched && it.dateModified == m.dateModified } ?: m
            }.sortedBy { it.title.lowercase() }
            tracks.value = current
            UserStore.reconcile(current)
            fresh.value = true

            val todo = current.indices.filter { !current[it].enriched }
            if (todo.isNotEmpty()) {
                val list = current.toMutableList()
                val done = AtomicInteger(0)
                val next = AtomicInteger(0)
                val reading = AtomicLong(0)
                val t0 = SystemClock.elapsedRealtime()
                progress.value = 0 to todo.size
                // La interfaz se rehace con lo leído: cada segundo con una biblioteca normal; con
                // miles de canciones, cada 4 s (rehacer grupos y órdenes de 10.000 cada segundo le
                // quitaba tiempo a la lectura). Lo leído se guarda cada 20 s.
                val emitEvery = if (todo.size > 2000) 4000L else 1000L
                var lastEmit = t0
                var lastSave = t0
                // Lectores sin esperas entre tandas: cada uno coge la siguiente canción en cuanto
                // acaba (con tandas de 32, la más lenta de cada tanda paraba a las demás). Leer es
                // sobre todo esperar al almacenamiento: más lectores que núcleos.
                coroutineScope {
                    repeat(TAG_READERS) {
                        launch(tagReaders) {
                            while (true) {
                                val k = next.getAndIncrement()
                                if (k >= todo.size) break
                                val i = todo[k]
                                val s0 = SystemClock.elapsedRealtime()
                                val r = scanner.enrich(list[i])
                                reading.addAndGet(SystemClock.elapsedRealtime() - s0)
                                synchronized(list) { list[i] = r }
                                progress.value = done.incrementAndGet() to todo.size
                            }
                        }
                    }
                    launch {
                        while (done.get() < todo.size) {
                            delay(500)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit >= emitEvery) { tracks.value = synchronized(list) { list.toList() }; lastEmit = now }
                            if (now - lastSave > 20_000) { scanner.saveCache(synchronized(list) { list.toList() }); lastSave = now }
                        }
                    }
                }
                tracks.value = list.toList()
                UserStore.reconcile(tracks.value)
                val wall = SystemClock.elapsedRealtime() - t0
                // Para medir (rendimiento-surco.sh lo saca del logcat): cuánto fue leer y cuánto lo demás.
                Log.i("SurcoScan", "etiquetas: ${todo.size} canciones en $wall ms con $TAG_READERS lectores; " +
                    "leer ${reading.get() / todo.size} ms por canción (suma ${reading.get()} ms)")
            }
            progress.value = null
            scanner.saveCache(tracks.value)
            analyzeLibrary()
        }
    }

    fun loadLyrics(t: Track): Lyrics? = scanner.loadLyrics(t, online = SurcoSettings.state.value.onlineLyrics)

    fun goToAlbum(t: Track) = TagField.ALBUM.buckets(t).firstOrNull()?.let { openTag(TagField.ALBUM, it.key, it.label) }
    fun goToArtist(t: Track) = TagField.ARTIST.buckets(t).firstOrNull()?.let { openTag(TagField.ARTIST, it.key, it.label) }

    /** Desde un aviso («Ver»): la lista, en su pestaña, cerrando lo que la tape. */
    fun openPlaylist(id: Long) {
        sheet = null
        nowPlayingOpen = false
        queueOpen = false
        selectTab(Tab.LISTS)
        push(Screen.PlaylistView(id))
    }

    fun openTag(field: TagField, key: String, label: String) {
        sheet = null
        nowPlayingOpen = false
        push(Screen.Songs(field, key, label))
    }

    override fun onCleared() { player.release() }
}
