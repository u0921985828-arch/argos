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
