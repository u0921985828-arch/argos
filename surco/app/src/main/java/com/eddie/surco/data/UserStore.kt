package com.eddie.surco.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(val id: Long, val name: String, val trackIds: List<Long>)

/** Cola guardada para reanudar donde se quedó. */
data class SavedQueue(
    val ids: List<Long> = emptyList(), val index: Int = 0, val positionMs: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = 0,
)

/** Lo que el usuario hace con su música: favoritas, listas, escuchas y la cola. Nada sale del móvil. */
data class UserData(
    val favorites: Set<Long> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val plays: Map<Long, Int> = emptyMap(),
    val lastPlayed: Map<Long, Long> = emptyMap(),
    val queue: SavedQueue = SavedQueue(),
    /** Claves estables (ruta, datos) de cada id usado, para recuperarlo si MediaStore le cambia el id. */
    val keys: Map<Long, List<String>> = emptyMap(),
)

object UserStore {
    private val _state = MutableStateFlow(UserData())
    val state: StateFlow<UserData> = _state
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "usuario.json")
        file = f
        Disk.read(f)?.let { text ->
            runCatching { _state.value = fromJson(JSONObject(text)) }.onFailure { Disk.quarantine(f) }
        }
        scope.launch(Disk.writer) { _state.drop(1).debounce(800).collect { save(it) } }
    }

    private fun save(d: UserData) {
        val f = file ?: return
        Disk.write(f, toJson(d).toString())
    }

    /** Guarda ya, sin esperar (al cerrar el servicio), por el mismo hilo que los demás guardados. */
    fun flush() = runBlocking(Disk.writer) { save(_state.value) }

    /** La biblioteca actual: para guardar las claves de una canción en cuanto se usa. */
    @Volatile
    private var library: Map<Long, Track> = emptyMap()

    @Synchronized
    fun update(f: (UserData) -> UserData) { _state.value = withKeys(f(_state.value)) }

    private fun refsOf(d: UserData): Set<Long> =
        d.favorites + d.playlists.flatMap { it.trackIds } + d.plays.keys + d.lastPlayed.keys + d.queue.ids

    /** Lo que se marca, se añade a una lista o se escucha guarda sus claves en el acto (no al escanear). */
    private fun withKeys(d: UserData): UserData {
        val lib = library
        if (lib.isEmpty()) return d
        val missing = refsOf(d).filter { it !in d.keys && it in lib }
        if (missing.isEmpty()) return d
        return d.copy(keys = d.keys + missing.associateWith { lib.getValue(it).stableKeys() })
    }

    fun isFavorite(id: Long) = id in _state.value.favorites
    fun toggleFavorite(id: Long) = update { d -> d.copy(favorites = if (id in d.favorites) d.favorites - id else d.favorites + id) }

    fun createPlaylist(name: String, ids: List<Long> = emptyList()): Long {
        val id = System.currentTimeMillis()
        update { d -> d.copy(playlists = d.playlists + Playlist(id, name.trim().ifBlank { "Lista" }, ids)) }
        return id
    }
    fun renamePlaylist(id: Long, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    fun deletePlaylist(id: Long) = update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
    fun addToPlaylist(id: Long, ids: List<Long>) = editPlaylist(id) { it.copy(trackIds = it.trackIds + ids) }
    fun removeFromPlaylist(id: Long, index: Int) = editPlaylist(id) { p ->
        p.copy(trackIds = p.trackIds.toMutableList().also { if (index in it.indices) it.removeAt(index) })
    }
    fun movePlaylistItem(id: Long, from: Int, to: Int) = editPlaylist(id) { p ->
        val l = p.trackIds.toMutableList()
        if (from in l.indices && to in l.indices) l.add(to, l.removeAt(from))
        p.copy(trackIds = l)
    }
    private fun editPlaylist(id: Long, f: (Playlist) -> Playlist) =
        update { d -> d.copy(playlists = d.playlists.map { if (it.id == id) f(it) else it }) }

    fun recordPlay(id: Long) = update { d ->
        d.copy(plays = d.plays + (id to (d.plays[id] ?: 0) + 1), lastPlayed = d.lastPlayed + (id to System.currentTimeMillis()))
    }

    /**
     * Tras cada escaneo: los ids que ya no existen se cambian por el id actual de la misma canción
     * (por ruta o por título+artista+álbum+duración) en favoritas, listas, escuchas y cola.
     */
    fun reconcile(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        library = byId
        val idByKey = HashMap<String, Long>()
        for (t in tracks) for (k in t.stableKeys()) idByKey.putIfAbsent(k, t.id)
        update { d ->
            val m = remapIds(refsOf(d), byId.keys, d.keys, idByKey)
            fun f(id: Long) = m[id] ?: id
            val nd = if (m.isEmpty()) d else d.copy(
                favorites = d.favorites.map(::f).toSet(),
                playlists = d.playlists.map { p -> p.copy(trackIds = p.trackIds.map(::f)) },
                plays = d.plays.entries.groupBy({ f(it.key) }, { it.value }).mapValues { it.value.sum() },
                lastPlayed = d.lastPlayed.entries.groupBy({ f(it.key) }, { it.value }).mapValues { it.value.max() },
                queue = d.queue.copy(ids = d.queue.ids.map(::f)),
            )
            val newRefs = refsOf(nd)
            // Claves al día de lo que existe; las de lo que falta se guardan por si vuelve.
            val keys = newRefs.associateWith { id -> byId[id]?.stableKeys() ?: d.keys[id].orEmpty() }.filterValues { it.isNotEmpty() }
            if (m.isEmpty() && keys == d.keys) d else nd.copy(keys = keys)
        }
    }

    fun saveQueue(q: SavedQueue) { if (q != _state.value.queue) update { it.copy(queue = q) } }

    // ----- JSON -----
    private fun toJson(d: UserData) = JSONObject().apply {
        put("favorites", JSONArray(d.favorites.toList()))
        put("playlists", JSONArray().apply {
            d.playlists.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", JSONArray(p.trackIds))) }
        })
        put("plays", JSONObject().apply { d.plays.forEach { (k, v) -> put(k.toString(), v) } })
        put("lastPlayed", JSONObject().apply { d.lastPlayed.forEach { (k, v) -> put(k.toString(), v) } })
        put("queue", JSONObject().put("ids", JSONArray(d.queue.ids)).put("index", d.queue.index).put("position", d.queue.positionMs)
            .put("shuffle", d.queue.shuffle).put("repeat", d.queue.repeat))
        put("keys", JSONObject().apply { d.keys.forEach { (id, ks) -> put(id.toString(), JSONArray(ks)) } })
    }

    private fun longs(a: JSONArray?): List<Long> = a?.let { arr -> List(arr.length()) { arr.optLong(it) } }.orEmpty()

    private fun fromJson(o: JSONObject): UserData {
        val playlists = o.optJSONArray("playlists")?.let { arr ->
            List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                Playlist(p.optLong("id"), p.optString("name"), longs(p.optJSONArray("tracks")))
            }
        }.orEmpty()
        fun <T> map(key: String, get: (JSONObject, String) -> T): Map<Long, T> {
            val m = o.optJSONObject(key) ?: return emptyMap()
            val out = HashMap<Long, T>()
            m.keys().forEach { k -> k.toLongOrNull()?.let { out[it] = get(m, k) } }
            return out
        }
        val q = o.optJSONObject("queue")
        return UserData(
            favorites = longs(o.optJSONArray("favorites")).toSet(),
            playlists = playlists,
            plays = map("plays") { m, k -> m.optInt(k) },
            lastPlayed = map("lastPlayed") { m, k -> m.optLong(k) },
            queue = if (q == null) SavedQueue() else SavedQueue(
                longs(q.optJSONArray("ids")), q.optInt("index"), q.optLong("position"), q.optBoolean("shuffle"), q.optInt("repeat"),
            ),
            keys = map("keys") { m, k -> m.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty() },
        )
    }
}

fun Track.stableKeys() = stableKeys(path, title, artist, album, durationMs)

/** Listas que se hacen solas a partir de lo escuchado. */
enum class SmartList(val label: String) {
    FAVORITES("Favoritas"), MOST_PLAYED("Más escuchadas"), RECENT("Escuchadas hace poco"),
    ADDED("Añadidas hace poco"), NEVER("Sin escuchar");

    fun tracks(all: List<Track>, d: UserData): List<Track> = when (this) {
        FAVORITES -> all.filter { it.id in d.favorites }
        MOST_PLAYED -> all.filter { (d.plays[it.id] ?: 0) > 0 }.sortedByDescending { d.plays[it.id] ?: 0 }.take(100)
        RECENT -> all.filter { it.id in d.lastPlayed }.sortedByDescending { d.lastPlayed[it.id] ?: 0 }.take(100)
        ADDED -> all.sortedByDescending { it.dateAdded }.take(100)
        NEVER -> all.filter { it.id !in d.plays }
    }
}
