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
    // Cada artista es un grupo; las canciones sin artista van en el grupo «», que no choca con nada.
    val ids = HashMap<String, Int>()
    val names = ArrayList<String>()
    val lists = ArrayList<ArrayList<Int>>()
    fun keyOf(i: Int) = keys[i].trim().lowercase()
    for (i in keys.indices) {
        if (i == first) continue
        val g = ids.getOrPut(keyOf(i)) { names.add(keyOf(i)); lists.add(ArrayList()); names.lastIndex }
        lists[g].add(i)
    }
    // Dentro de cada artista, sus canciones también en orden aleatorio.
    lists.forEach { it.shuffle(rnd) }
    val g = names.size
    val size = IntArray(g) { lists[it].size }
    // Árbol de Fenwick con lo que le queda a cada grupo (elegir con peso en log g) y montículo del
    // mayor (con entradas viejas que se descartan al mirarlas): con 10.000 canciones y 2.500
    // artistas son milisegundos, no segundos en el hilo principal.
    val tree = IntArray(g + 1)
    fun add(i: Int, d: Int) { var x = i + 1; while (x <= g) { tree[x] += d; x += x and -x } }
    fun before(i: Int): Int { var x = i; var s = 0; while (x > 0) { s += tree[x]; x -= x and -x }; return s }
    fun find(r: Int): Int { // el grupo donde cae la unidad r (0 ≤ r < total)
        var pos = 0; var rem = r; var step = Integer.highestOneBit(maxOf(g, 1))
        while (step > 0) { val nx = pos + step; if (nx <= g && tree[nx] <= rem) { pos = nx; rem -= tree[nx] }; step = step shr 1 }
        return pos
    }
    for (i in 0 until g) add(i, size[i])
    val heap = java.util.PriorityQueue<Long>(maxOf(g, 1), compareByDescending { it })
    fun push(i: Int) { if (size[i] > 0) heap.add((size[i].toLong() shl 32) or i.toLong()) }
    for (i in 0 until g) push(i)
    val empty = ids[""] ?: -1
    val out = IntArray(keys.size)
    var n = 0
    var prev = -1 // grupo de la anterior; -1 o el grupo «» no excluyen nada
    if (first in keys.indices) { out[n++] = first; prev = ids[keyOf(first)] ?: -2 }
    var left = keys.size - n
    while (left > 0) {
        while (heap.isNotEmpty()) { val top = heap.peek()!!; if ((top ushr 32).toInt() == size[(top and 0xFFFFFFFFL).toInt()]) break; heap.poll() }
        val big = (heap.peek()!! and 0xFFFFFFFFL).toInt()
        val excl = if (prev >= 0 && prev != empty) prev else -1
        val pick = when {
            // Uno con más de la mitad de lo que falta va ya: si no, al final no habría con qué separarlo.
            big != excl && big != empty && size[big] * 2 > left -> big
            excl < 0 -> find(rnd.nextInt(left))
            size[excl] == left -> excl
            else -> {
                val r = rnd.nextInt(left - size[excl])
                if (r < before(excl)) find(r) else find(r + size[excl])
            }
        }
        out[n++] = lists[pick][lists[pick].size - size[pick]]
        size[pick]--; add(pick, -1); push(pick)
        prev = pick
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
fun radioScore(seed: RadioSong, o: RadioSong, g: Set<String> = genres(seed.genre)): Double {
    var s = 0.0
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
    val g = genres(seed.genre)
    others.map { it to radioScore(seed, it, g) + rnd.nextDouble() * 0.75 }
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
