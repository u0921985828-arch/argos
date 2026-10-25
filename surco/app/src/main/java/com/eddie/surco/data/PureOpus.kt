package com.eddie.surco.data

import java.io.InputStream

/**
 * Comentarios de un Opus (paquete «OpusTags» del Ogg, mismo formato que Vorbis): jaudiotagger
 * 3.0.1 no abre .opus. Devuelve clave en mayúsculas → valores, o null si no es un Ogg Opus.
 */
fun readOpusTags(input: InputStream, maxBytes: Int = 16 shl 20): Map<String, List<String>>? {
    val packet = java.io.ByteArrayOutputStream()
    var packetIndex = 0
    val header = ByteArray(27)
    while (true) {
        if (!readFully(input, header)) return null
        if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() || header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()) return null
        val segs = header[26].toInt() and 0xFF
        val table = ByteArray(segs)
        if (!readFully(input, table)) return null
        for (lace in table) {
            val len = lace.toInt() and 0xFF
            val data = ByteArray(len)
            if (!readFully(input, data)) return null
            packet.write(data)
            if (packet.size() > maxBytes) return null
            if (len < 255) {
                val p = packet.toByteArray()
                packet.reset()
                when (packetIndex++) {
                    0 -> if (!p.startsWith("OpusHead")) return null
                    1 -> return if (p.startsWith("OpusTags")) parseComments(p, 8) else null
                }
            }
        }
    }
}

private fun ByteArray.startsWith(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

private fun readFully(input: InputStream, b: ByteArray): Boolean {
    var off = 0
    while (off < b.size) {
        val n = input.read(b, off, b.size - off)
        if (n < 0) return false
        off += n
    }
    return true
}

private fun parseComments(p: ByteArray, start: Int): Map<String, List<String>>? {
    var i = start
    fun u32(): Long? {
        if (i + 4 > p.size) return null
        val v = (p[i].toLong() and 0xFF) or ((p[i + 1].toLong() and 0xFF) shl 8) or ((p[i + 2].toLong() and 0xFF) shl 16) or ((p[i + 3].toLong() and 0xFF) shl 24)
        i += 4; return v
    }
    val vendor = u32() ?: return null
    i += vendor.toInt().coerceAtLeast(0)
    val count = u32() ?: return null
    val out = LinkedHashMap<String, MutableList<String>>()
    for (k in 0 until count) {
        val len = u32()?.toInt() ?: break
        if (len < 0 || i + len > p.size) break
        val s = String(p, i, len, Charsets.UTF_8); i += len
        val eq = s.indexOf('=')
        if (eq <= 0) continue
        out.getOrPut(s.substring(0, eq).uppercase()) { ArrayList() } += s.substring(eq + 1)
    }
    return out
}

/** ReplayGain desde comentarios Vorbis/Opus (REPLAYGAIN_* o, si no, R128_*). */
fun replayGainFromComments(c: Map<String, List<String>>): ReplayGain {
    fun first(k: String) = c[k]?.firstOrNull()
    return ReplayGain(
        first("REPLAYGAIN_TRACK_GAIN")?.let { parseGainDb(it) } ?: first("R128_TRACK_GAIN")?.let { r128ToDb(it) },
        first("REPLAYGAIN_ALBUM_GAIN")?.let { parseGainDb(it) } ?: first("R128_ALBUM_GAIN")?.let { r128ToDb(it) },
    )
}
