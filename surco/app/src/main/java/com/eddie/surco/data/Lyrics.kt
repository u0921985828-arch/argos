package com.eddie.surco.data

/** Letra de una canción. Si [synced], cada línea tiene su instante; si no, todas van a -1. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String) {
    /** Índice de la línea que suena en [posMs], o -1 antes de la primera. */
    fun indexAt(posMs: Long): Int {
        if (!synced) return -1
        var lo = 0; var hi = lines.lastIndex; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

data class LyricLine(val timeMs: Long, val text: String)

private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
private val META_TAG = Regex("""^\[[a-zA-Z]+:.*]$""")

/**
 * Lee una letra en formato LRC ("[01:23.45] texto", varias marcas por línea, [offset:+/-ms]).
 * Sin marcas de tiempo devuelve la letra tal cual, sin sincronizar. Vacía → null.
 */
fun parseLyrics(raw: String, source: String): Lyrics? {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (text.isEmpty()) return null
    var offset = 0L
    val timed = ArrayList<LyricLine>()
    for (line in text.lines()) {
        val l = line.trim()
        val off = OFFSET_TAG.find(l)
        if (off != null) { offset = off.groupValues[1].toLongOrNull() ?: 0L; continue }
        val tags = TIME_TAG.findAll(l).toList()
        if (tags.isEmpty() || tags.first().range.first != 0) continue
        val body = l.substring(tags.last().range.last + 1).trim()
        for (m in tags) {
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val frac = m.groupValues[3]
            val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            timed += LyricLine(min * 60_000 + sec * 1000 + ms, body)
        }
    }
    if (timed.isNotEmpty()) {
        // Un offset positivo adelanta la letra.
        val lines = timed.map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0)) }.sortedBy { it.timeMs }
        return Lyrics(lines, synced = true, source = source)
    }
    val plain = text.lines().map { it.trim() }.filterNot { META_TAG.matches(it) }
    if (plain.all { it.isEmpty() }) return null
    return Lyrics(plain.map { LyricLine(-1, it) }, synced = false, source = source)
}

/** "−6.48 dB", "+1,2 dB", "-3.1" → dB; null si no se entiende. */
fun parseGainDb(raw: String): Float? {
    val m = Regex("""([+\-−]?\d+(?:[.,]\d+)?)""").find(raw.trim()) ?: return null
    return m.value.replace('−', '-').replace(',', '.').toFloatOrNull()?.takeIf { it in -60f..60f }
}
