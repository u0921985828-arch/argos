package com.eddie.surco.data

import kotlin.math.abs
import kotlin.random.Random

// Radio de una canción y aleatorio que reparte artistas, sin Android (se prueban en la JVM).

/**
 * Orden aleatorio sin dos canciones seguidas del mismo artista siempre que se pueda (ningún
 * artista con más de la mitad, redondeando hacia arriba). [keys] es el artista de cada posición;
 * una clave vacía no choca con nada. Si [first] es una posición válida, va la primera (la que
 * suena al activar el aleatorio). Con un artista que lo llena casi todo, lo reparte lo más posible.
 *
 * Cada paso elige un artista distinto del anterior al azar, con peso según lo que le queda (así
 * cada uno se reparte por toda la lista y no se amontona al final), salvo que uno tenga más de la
 * mitad de lo que falta: entonces va él, que es lo único que deja el resto sin choques.
 */
fun spreadShuffle(keys: List<String>, first: Int, rnd: Random): IntArray {
    fun keyOf(i: Int) = keys[i].trim().lowercase()
    // Las canciones sin artista van en un grupo «» que no choca con nada (ni consigo mismo).
    val groups = LinkedHashMap<String, ArrayDeque<Int>>()
    for (i in keys.indices) if (i != first) groups.getOrPut(keyOf(i)) { ArrayDeque() }.add(i)
    // Dentro de cada artista, sus canciones también en orden aleatorio.
    groups.values.forEach { d -> val s = d.shuffled(rnd); d.clear(); d.addAll(s) }
    val out = IntArray(keys.size)
    var n = 0
    var prev = ""
    if (first in keys.indices) { out[n++] = first; prev = keyOf(first) }
    var left = keys.size - n
    while (left > 0) {
        val others = groups.entries.filter { it.value.isNotEmpty() && (it.key != prev || it.key.isEmpty()) }
        val pick = when {
            others.isEmpty() -> groups.entries.first { it.key == prev }
            else -> others.firstOrNull { it.key.isNotEmpty() && it.value.size * 2 > left } ?: run {
                var r = rnd.nextInt(others.sumOf { it.value.size })
                others.first { e -> (r < e.value.size).also { if (!it) r -= e.value.size } }
            }
        }
        out[n++] = pick.value.removeFirst()
        prev = pick.key
        left--
    }
    return out
}

/** Lo que la radio mira de cada canción. */
data class RadioSong(
    val id: Long, val artist: String, val genre: String, val year: Int,
    val bpm: Float?, val key: MusicKey?, val dir: String,
)

private fun genres(g: String) = g.split(';', '/', ',', '|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

/** Parecido de [o] a [seed]: género, artista, época, tonalidad vecina, tempo y carpeta. */
fun radioScore(seed: RadioSong, o: RadioSong): Double {
    var s = 0.0
    val g = genres(seed.genre)
    if (g.isNotEmpty() && genres(o.genre).any { it in g }) s += 3.0
    if (seed.artist.isNotBlank() && seed.artist.equals(o.artist, ignoreCase = true)) s += 2.0
    if (seed.year > 0 && o.year > 0) s += (1.5 - abs(seed.year - o.year) / 5.0).coerceAtLeast(0.0)
    if (seed.key != null && o.key != null && harmonic(seed.key, o.key)) s += 1.0
    if (seed.bpm != null && o.bpm != null && tempoMatch(seed.bpm, o.bpm)) s += 1.0
    if (seed.dir.isNotEmpty() && seed.dir == o.dir) s += 0.5
    return s
}

/**
 * Radio de [seed]: hasta [size] canciones de [all] parecidas a ella, empezando por ella, con un
 * poco de azar para que dos radios de la misma canción no sean iguales, como mucho [perArtist]
 * de cada artista (la radio no es el disco) y sin dos del mismo artista seguidas si se puede.
 * Si casi nada se parece (canciones sin etiquetas), se completa con otras al azar: la radio suena.
 */
fun radio(seed: RadioSong, all: List<RadioSong>, rnd: Random, size: Int = 50, perArtist: Int = 4): List<Long> {
    val count = HashMap<String, Int>()
    fun room(s: RadioSong): Boolean {
        val a = s.artist.trim().lowercase()
        return a.isEmpty() || (count[a] ?: 0) < perArtist
    }
    fun take(s: RadioSong) { val a = s.artist.trim().lowercase(); if (a.isNotEmpty()) count[a] = (count[a] ?: 0) + 1 }
    take(seed)
    val picked = ArrayList<RadioSong>()
    val others = all.filter { it.id != seed.id }
    others.map { it to radioScore(seed, it) + rnd.nextDouble() * 0.75 }
        .filter { it.second >= 1.5 }
        .sortedByDescending { it.second }
        .forEach { (s, _) -> if (picked.size < size - 1 && room(s)) { picked += s; take(s) } }
    if (picked.size < minOf(10, size - 1)) {
        val have = picked.mapTo(HashSet()) { it.id }
        others.filter { it.id !in have }.shuffled(rnd).forEach { s -> if (picked.size < size - 1 && room(s)) { picked += s; take(s) } }
    }
    val list = listOf(seed) + picked
    return spreadShuffle(list.map { it.artist }, 0, rnd).map { list[it].id }
}
