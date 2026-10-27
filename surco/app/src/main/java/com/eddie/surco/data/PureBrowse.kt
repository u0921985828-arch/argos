package com.eddie.surco.data

// Árbol de la biblioteca para Android Auto (y cualquier otro mando que navegue: relojes, la
// pantalla del coche, asistentes). Sin Android: se prueba en la JVM.
//
//   raiz
//   ├── inicio   «Para ti»: mezclar todo, listas automáticas, por género, por ritmo
//   ├── listas   automáticas y propias
//   ├── albumes  en cuadrícula
//   └── artistas
//
// En el coche hay cuatro pestañas como mucho: son estas cuatro.

/** Lo que el árbol necesita saber de una canción. */
data class BrowseSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    /** Claves y nombres de su álbum, sus artistas y sus géneros (como los grupos de la biblioteca). */
    val albumKey: String?,
    val artists: List<Pair<String, String>>,
    val genres: List<Pair<String, String>>,
    /** «120 – 129 BPM» y su orden, si se sabe. */
    val bpm: Pair<String, Long>?,
    val discNo: Int = 0,
    val trackNo: Int = 0,
    val artKey: Long = 0,
)

/** Una lista con nombre: automática (favoritas, más escuchadas…) o propia. */
data class BrowseList(val id: String, val title: String, val ids: List<Long>)

data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val playable: Boolean = false,
    /** Para los que se navegan: cuadrícula (álbumes) o lista. */
    val grid: Boolean = false,
    /** Canción que representa (portada) o que suena. */
    val songId: Long? = null,
    /** Una pestaña o carpeta con hijos. */
    val browsable: Boolean = !playable,
)

/** Palabras de relleno al pedir música en voz alta (español e inglés). */
private val STOP = setOf("de", "del", "el", "la", "los", "las", "y", "en", "por", "pon", "ponme", "reproduce", "escuchar", "cancion", "canciones",
    "musica", "surco", "by", "the", "and", "play", "song", "songs", "music", "on", "in", "of", "from")

object BrowseIds {
    const val ROOT = "raiz"
    const val HOME = "inicio"
    const val LISTS = "listas"
    const val ALBUMS = "albumes"
    const val ARTISTS = "artistas"
    const val GENRES = "generos"
    const val TEMPO = "ritmo"
    const val SHUFFLE_ALL = "mezclar"
    private const val SEP = "|t:"

    /** Una canción dentro de una carpeta: así al tocarla suena la carpeta entera desde ella. */
    fun song(parent: String, id: Long) = "$parent$SEP$id"

    /** (carpeta, canción) de un id de canción; null si no es de canción. */
    fun parseSong(nodeId: String): Pair<String, Long>? {
        val i = nodeId.lastIndexOf(SEP)
        if (i < 0) return null
        val id = nodeId.substring(i + SEP.length).toLongOrNull() ?: return null
        return nodeId.substring(0, i) to id
    }
}

class BrowseTree(private val songs: List<BrowseSong>, private val smart: List<BrowseList>, private val playlists: List<BrowseList>) {
    private val byId = songs.associateBy { it.id }
    private fun songsOf(ids: List<Long>) = ids.mapNotNull { byId[it] }
    private fun plural(n: Int) = Texts.n("data_songs", n, "%d canción", "%d canciones")
    private fun shuffleAll() = Texts.t("data_car_shuffle_all", "Mezclar todo")
    private fun albumOrder(l: List<BrowseSong>) = l.sortedWith(compareBy({ fold(it.album) }, { it.discNo }, { it.trackNo }, { fold(it.title) }))

    private val albums by lazy { songs.filter { it.albumKey != null }.groupBy { it.albumKey!! } }
    private val artists by lazy { songs.flatMap { s -> s.artists.map { it to s } }.groupBy({ it.first }, { it.second }) }
    private val genres by lazy { songs.flatMap { s -> s.genres.map { it to s } }.groupBy({ it.first }, { it.second }) }
    private val tempos by lazy { songs.filter { it.bpm != null }.groupBy { it.bpm!! } }

    fun root() = BrowseNode(BrowseIds.ROOT, "Surco")

    /** Hijos de una carpeta, o null si no existe (o ya no existe). */
    fun children(parent: String): List<BrowseNode>? = when {
        parent == BrowseIds.ROOT -> listOf(
            BrowseNode(BrowseIds.HOME, Texts.t("data_car_for_you", "Para ti")),
            BrowseNode(BrowseIds.LISTS, Texts.t("data_car_lists", "Listas")),
            BrowseNode(BrowseIds.ALBUMS, Texts.t("data_car_albums", "Álbumes"), grid = true),
            BrowseNode(BrowseIds.ARTISTS, Texts.t("data_car_artists", "Artistas")),
        )
        parent == BrowseIds.HOME -> buildList {
            if (songs.isNotEmpty()) add(BrowseNode(BrowseIds.SHUFFLE_ALL, shuffleAll(), plural(songs.size), playable = true))
            smart.filter { songsOf(it.ids).isNotEmpty() }.forEach { add(listNode(it)) }
            if (genres.isNotEmpty()) add(BrowseNode(BrowseIds.GENRES, Texts.t("data_car_by_genre", "Por género"), Texts.n("data_genres", genres.size, "%d género", "%d géneros")))
            if (tempos.isNotEmpty()) add(BrowseNode(BrowseIds.TEMPO, Texts.t("data_car_by_tempo", "Por ritmo"), "BPM"))
        }
        parent == BrowseIds.LISTS -> (smart + playlists).filter { songsOf(it.ids).isNotEmpty() }.map { listNode(it) }
        parent == BrowseIds.ALBUMS -> albums.entries.sortedBy { fold(it.value.first().album) }.map { (k, l) ->
            val names = l.map { it.artist }.filter { it.isNotBlank() }.distinct()
            BrowseNode("al:$k", l.first().album, names.take(2).joinToString(", ") + if (names.size > 2) "…" else "", songId = albumOrder(l).first().id)
        }
        parent == BrowseIds.ARTISTS -> artists.entries.sortedBy { fold(it.key.second) }.map { (k, l) ->
            BrowseNode("ar:${k.first}", k.second, plural(l.size), songId = l.first().id)
        }
        parent == BrowseIds.GENRES -> genres.entries.sortedByDescending { it.value.size }.map { (k, l) ->
            BrowseNode("ge:${k.first}", k.second, plural(l.size), songId = l.first().id)
        }
        parent == BrowseIds.TEMPO -> tempos.entries.sortedBy { it.key.second }.map { (k, l) ->
            BrowseNode("bpm:${k.second}", k.first, plural(l.size), songId = l.first().id)
        }
        else -> songsIn(parent)?.map { s -> BrowseNode(BrowseIds.song(parent, s.id), s.title, s.artist, playable = true, songId = s.id) }
    }

    private fun listNode(l: BrowseList) = BrowseNode(l.id, l.title, plural(songsOf(l.ids).size), songId = songsOf(l.ids).firstOrNull()?.id)

    /** Las canciones de una carpeta, en el orden en que suenan. */
    fun songsIn(parent: String): List<BrowseSong>? = when {
        parent == BrowseIds.SHUFFLE_ALL -> songs
        parent.startsWith("al:") -> albums[parent.removePrefix("al:")]?.let { albumOrder(it) }
        parent.startsWith("ar:") -> artists.entries.firstOrNull { it.key.first == parent.removePrefix("ar:") }?.value?.let { albumOrder(it) }
        parent.startsWith("ge:") -> genres.entries.firstOrNull { it.key.first == parent.removePrefix("ge:") }?.value?.sortedBy { fold(it.title) }
        parent.startsWith("bpm:") -> tempos.entries.firstOrNull { it.key.second.toString() == parent.removePrefix("bpm:") }?.value?.sortedBy { fold(it.title) }
        else -> (smart + playlists).firstOrNull { it.id == parent }?.let { songsOf(it.ids) }
    }

    /** Un nodo suelto (el coche pide a veces uno por su id). */
    fun item(id: String): BrowseNode? {
        if (id == BrowseIds.ROOT) return root()
        BrowseIds.parseSong(id)?.let { (_, sid) -> byId[sid]?.let { return BrowseNode(id, it.title, it.artist, playable = true, songId = it.id) } }
        if (id == BrowseIds.SHUFFLE_ALL) return BrowseNode(id, shuffleAll(), plural(songs.size), playable = true)
        return listOf(BrowseIds.ROOT, BrowseIds.HOME, BrowseIds.LISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.GENRES, BrowseIds.TEMPO)
            .asSequence().mapNotNull { children(it) }.flatten().firstOrNull { it.id == id }
    }

    /**
     * Qué suena al tocar [nodeId]: una canción suena con su carpeta desde ella; «Mezclar todo»,
     * toda la biblioteca en aleatorio. (ids, índice de inicio, aleatorio)
     */
    fun playContext(nodeId: String): Triple<List<Long>, Int, Boolean>? {
        if (nodeId == BrowseIds.SHUFFLE_ALL) return if (songs.isEmpty()) null else Triple(songs.map { it.id }, 0, true)
        val (parent, id) = BrowseIds.parseSong(nodeId) ?: return byId[nodeId.toLongOrNull()]?.let { Triple(listOf(it.id), 0, false) }
        val list = songsIn(parent)?.map { it.id } ?: return byId[id]?.let { Triple(listOf(id), 0, false) }
        val at = list.indexOf(id)
        return if (at < 0) Triple(listOf(id), 0, false) else Triple(list, at, false)
    }

    /**
     * Búsqueda por voz o en la pantalla del coche: por palabras, sin tildes, en título, artista
     * y álbum. Si la frase es un artista o un álbum, suenan los suyos.
     */
    fun search(query: String, limit: Int = 50): List<BrowseSong> {
        val q = fold(query).trim()
        if (q.isEmpty()) return emptyList()
        val all = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        // Lo que se dice al hablar y no está en las etiquetas: «pon Bohemian Rhapsody de Queen».
        val words = all.filter { it !in STOP }.ifEmpty { all }
        if (words.isEmpty()) return emptyList()
        // Cada palabra dicha tiene que empezar una palabra de la canción: «a» no casa con «banda».
        fun hay(s: BrowseSong) = fold("${s.title} ${s.artist} ${s.album}").split(Regex("[^\\p{L}\\p{N}]+"))
        fun matched(s: BrowseSong): Int { val h = hay(s); return words.count { w -> h.any { it.startsWith(w) } } }
        var hits = songs.filter { matched(it) == words.size }
        // Si nada casa con todo (el asistente añade palabras), las que casen con casi todo.
        if (hits.isEmpty() && words.size >= 3) hits = songs.filter { matched(it) >= words.size - 1 }
        // Primero lo que casa con el título entero; después, por artista y álbum.
        return hits.sortedWith(compareBy({ fold(it.title) != q }, { fold(it.artist) != q }, { fold(it.album) != q }, { fold(it.album) }, { it.discNo }, { it.trackNo }))
            .take(limit)
    }
}
