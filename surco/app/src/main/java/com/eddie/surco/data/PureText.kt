package com.eddie.surco.data

import java.text.Normalizer

private val MARKS = Regex("\\p{Mn}+")

/** Para buscar: sin tildes ni mayúsculas («Electrónica» = «electronica», «Ñu» = «nu»). */
fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

/** Etiquetas de `extra` que son texto de verdad (no «24 bits», «No», «-6.48»…). */
val SEARCHABLE_EXTRA = listOf("mood", "label", "lyricist", "producer", "remixer", "conductor", "grouping", "comment", "subtitle", "language", "country", "catalog", "isrc")

/** ¿Están todas las palabras de la consulta en alguno de los textos? */
fun matchesQuery(texts: List<String>, query: String): Boolean {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return false
    val hay = texts.joinToString("\u0001") { fold(it) }
    return words.all { hay.contains(it) }
}

/** Texto de búsqueda ya plegado de una canción (se calcula una vez, no en cada tecla). */
fun haystack(texts: List<String>): String = texts.joinToString("\u0001") { fold(it) }

fun matchesFolded(hay: String, query: String): Boolean {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    return words.isNotEmpty() && words.all { hay.contains(it) }
}
