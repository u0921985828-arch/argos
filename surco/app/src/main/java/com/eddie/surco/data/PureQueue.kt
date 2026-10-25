package com.eddie.surco.data

/**
 * Qué queda de una cola guardada cuando algunas canciones ya no existen.
 * Devuelve las posiciones originales que se conservan, el índice de inicio sobre ellas y si
 * es la misma canción que sonaba (para conservar la posición) o null si no queda ninguna.
 */
fun keptQueue(ids: List<Long>, index: Int, exists: (Long) -> Boolean): Triple<List<Int>, Int, Boolean>? {
    val kept = ids.indices.filter { exists(ids[it]) }
    if (kept.isEmpty()) return null
    val exact = kept.indexOf(index)
    val start = if (exact >= 0) exact else kept.indexOfFirst { it > index }.let { if (it >= 0) it else 0 }
    return Triple(kept, start, exact >= 0)
}

/**
 * Claves estables de una canción. El _ID de MediaStore cambia si el archivo se borra y se vuelve a
 * copiar (o se cambia de móvil): la ruta suele seguir igual y, si no, título+artista+álbum+duración.
 */
fun stableKeys(path: String, title: String, artist: String, album: String, durationMs: Long): List<String> = listOfNotNull(
    path.takeIf { it.isNotBlank() }?.let { "p:" + it.lowercase() },
    ("m:" + listOf(title, artist, album).joinToString("|") { it.trim().lowercase() } + "|" + (durationMs / 1000))
        .takeIf { title.isNotBlank() },
)

/** Para cada id referenciado que ya no existe, el id actual con alguna de sus claves (si lo hay). */
fun remapIds(referenced: Set<Long>, alive: Set<Long>, keysOf: Map<Long, List<String>>, idByKey: Map<String, Long>): Map<Long, Long> {
    val out = HashMap<Long, Long>()
    for (old in referenced) {
        if (old in alive) continue
        val now = keysOf[old].orEmpty().firstNotNullOfOrNull { idByKey[it] } ?: continue
        out[old] = now
    }
    return out
}

/** Orden aleatorio que empieza por la que suena: al activar el aleatorio no se salta nada. */
fun currentFirstOrder(count: Int, current: Int, rnd: kotlin.random.Random): IntArray {
    val rest = (0 until count).filter { it != current }.shuffled(rnd)
    return (if (current in 0 until count) listOf(current) + rest else rest).toIntArray()
}

/**
 * Orden aleatorio tras meter [n] canciones en la posición [index] de la lista: las que ya estaban
 * conservan su orden (con los índices corridos) y las nuevas van justo detrás de la que suena
 * («A continuación») o al final («A la cola»).
 */
fun insertIntoOrder(order: List<Int>, index: Int, n: Int, current: Int, playNext: Boolean): List<Int> {
    val shifted = order.map { if (it >= index) it + n else it }
    val added = (index until index + n).toList()
    val cur = if (current >= index) current + n else current
    val at = if (playNext) shifted.indexOf(cur).let { if (it < 0) 0 else it + 1 } else shifted.size
    return shifted.toMutableList().apply { addAll(at, added) }
}
