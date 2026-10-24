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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(val id: Long, val name: String, val trackIds: List<Long>)

/** Cola guardada para reanudar donde se quedó. */
data class SavedQueue(val ids: List<Long> = emptyList(), val index: Int = 0, val positionMs: Long = 0)

/** Lo que el usuario hace con su música: favoritas, listas, escuchas y la cola. Nada sale del móvil. */
data class UserData(
    val favorites: Set<Long> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val plays: Map<Long, Int> = emptyMap(),
    val lastPlayed: Map<Long, Long> = emptyMap(),
    val queue: SavedQueue = SavedQueue(),
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
        runCatching { _state.value = fromJson(JSONObject(f.readText())) }
        scope.launch { _state.drop(1).debounce(800).collect { save(it) } }
    }

    private fun save(d: UserData) {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(toJson(d).toString()); tmp.renameTo(f)
        }
    }

    /** Guarda ya, sin esperar (al cerrar el servicio). */
    fun flush() = save(_state.value)

    @Synchronized
    fun update(f: (UserData) -> UserData) { _state.value = f(_state.value) }

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

    fun saveQueue(q: SavedQueue) { if (q != _state.value.queue) update { it.copy(queue = q) } }

    // ----- JSON -----
    private fun toJson(d: UserData) = JSONObject().apply {
        put("favorites", JSONArray(d.favorites.toList()))
        put("playlists", JSONArray().apply {
            d.playlists.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", JSONArray(p.trackIds))) }
        })
        put("plays", JSONObject().apply { d.plays.forEach { (k, v) -> put(k.toString(), v) } })
        put("lastPlayed", JSONObject().apply { d.lastPlayed.forEach { (k, v) -> put(k.toString(), v) } })
        put("queue", JSONObject().put("ids", JSONArray(d.queue.ids)).put("index", d.queue.index).put("position", d.queue.positionMs))
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
            queue = if (q == null) SavedQueue() else SavedQueue(longs(q.optJSONArray("ids")), q.optInt("index"), q.optLong("position")),
        )
    }
}

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
