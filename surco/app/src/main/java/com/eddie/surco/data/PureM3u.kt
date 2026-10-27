package com.eddie.surco.data

import java.nio.ByteBuffer
import java.text.Normalizer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Listas M3U y M3U8: las que trae quien viene de otro reproductor o de un PC (Winamp, foobar,
 * iTunes, VLC, MusicBee). Sin Android, para probarlo en la JVM.
 */

/** Una línea de canción de la lista, con lo que dijera su #EXTINF (si lo había). */
data class M3uEntry(val path: String, val durationSec: Int = -1, val display: String = "")

/** Lo mínimo de una canción de la biblioteca para casarla (Track lleva Android dentro). */
data class M3uSong(val id: Long, val path: String, val title: String, val artist: String, val durationMs: Long = 0)

/** Canciones casadas en el orden de la lista y cuántas entradas no se encontraron. */
data class M3uMatch(val ids: List<Long>, val missing: Int)

/**
 * Bytes del archivo a texto. M3U8 es UTF-8 por definición, pero los .m3u de Windows suelen ir en
 * Windows-1252: si no es UTF-8 válido, se lee así (si no, «Canción» saldría «Canci�n» y no casaría).
 */
fun decodeM3u(bytes: ByteArray): String {
    val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    val buf = ByteBuffer.wrap(bytes, start, bytes.size - start)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, start, bytes.size - start, Charset.forName("windows-1252"))
    }
}

/** Lee la lista: rutas en orden, cada una con el #EXTINF que la precede. Ignora comentarios y huecos. */
fun parseM3u(text: String): List<M3uEntry> {
    val out = ArrayList<M3uEntry>()
    var dur = -1
    var display = ""
    for (raw in text.removePrefix("\uFEFF").lineSequence()) {
        val line = raw.trim().removePrefix("\uFEFF")
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = line.substring(8)
                // «#EXTINF:215 tvg-name="a,b",Artista - Título»: la coma que vale es la primera fuera de comillas.
                var quoted = false
                var comma = -1
                for ((i, c) in info.withIndex()) {
                    if (c == '"') quoted = !quoted else if (c == ',' && !quoted) { comma = i; break }
                }
                val head = if (comma < 0) info else info.substring(0, comma)
                dur = DURATION.find(head.trim())?.value?.toDoubleOrNull()?.toInt() ?: -1
                display = if (comma < 0) "" else info.substring(comma + 1).trim()
            }
            continue
        }
        out += M3uEntry(line, dur, display)
        dur = -1; display = ""
    }
    return out
}

/** Una ruta de la lista ya entendida: sus carpetas y archivo, y si es absoluta y de este sistema (Unix). */
private class EntryPath(val segments: List<String>, val unixAbsolute: Boolean)

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]+://")
private val DRIVE = Regex("^[A-Za-z]:")

/** «%C3%B3» → «ó» (URLDecoder no vale: convierte «+» en espacio, y en una URL file:// es un «+»). */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) { bytes.write(hex); i += 3 } else { bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
    }
    return bytes.toString("UTF-8")
}

/** null si no es un archivo (una radio por http no está en la biblioteca, salvo por su #EXTINF). */
private fun entryPath(raw: String, baseDir: String?): EntryPath? {
    var p = raw.trim()
    if (p.startsWith("file:", ignoreCase = true)) {
        p = percentDecode(p.substring(5))
        // file:///ruta, file://localhost/ruta, file:/ruta y file:///C:/ruta
        p = when {
            p.startsWith("//localhost/", ignoreCase = true) -> p.substring(11)
            p.startsWith("///") -> p.substring(2)
            p.startsWith("//") -> p.substring(1)
            else -> p
        }
        if (FILE_DRIVE.containsMatchIn(p)) p = p.substring(1)
    } else if (SCHEME.containsMatchIn(p)) return null
    p = p.replace('\\', '/')
    val windows = DRIVE.containsMatchIn(p) || p.startsWith("//")
    if (DRIVE.containsMatchIn(p)) p = p.substring(2)
    val absolute = p.startsWith("/")
    val parts = ArrayList<String>()
    // Relativa con la carpeta de la lista conocida: se resuelve contra ella y pasa a absoluta.
    val base = if (!absolute && !windows) baseDir else null
    if (base != null) parts += base.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    for (s in p.split('/')) {
        when (s) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += s
        }
    }
    if (parts.isEmpty()) return null
    return EntryPath(parts, unixAbsolute = (absolute && !windows) || base != null)
}

private val SPACES = Regex("\\s+")
private val DURATION = Regex("^-?\\d+(\\.\\d+)?")
private val FILE_DRIVE = Regex("^/[A-Za-z]:")
private val BREAKS = Regex("[\\r\\n]+")

/** Para comparar rutas: sin mayúsculas y en NFC (un Mac escribe «ó» como «o» + tilde suelta). */
private fun low(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()

private fun key(s: String) = fold(s).replace(SPACES, " ").trim()

/** «Artista - Título», como lo escriben las listas y como lo escribe [writeM3u8]. */
private fun displayOf(artist: String, title: String) = if (artist.isBlank()) title else "$artist - $title"

/**
 * Casa cada entrada con la biblioteca, por este orden: ruta absoluta; ruta relativa (las últimas
 * carpetas y el archivo: «Música\Artista\Disco\01.flac» de un PC casa con la misma ruta en el
 * móvil); nombre de archivo sin mayúsculas; y, como último recurso, «Artista - Título» del #EXTINF
 * (o del nombre del archivo) sin tildes ni mayúsculas. [baseDir] es la carpeta de la lista, si se sabe.
 */
fun matchM3u(entries: List<M3uEntry>, library: List<M3uSong>, baseDir: String? = null): M3uMatch {
    val byPath = HashMap<String, M3uSong>()
    val byPathLower = HashMap<String, M3uSong>()
    val byName = HashMap<String, MutableList<M3uSong>>()
    val byDisplay = HashMap<String, MutableList<M3uSong>>()
    val segs = HashMap<Long, List<String>>()
    for (s in library) {
        val norm = "/" + s.path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        byPath.putIfAbsent(norm, s)
        byPathLower.putIfAbsent(low(norm), s)
        val ls = low(s.path).split('/').filter { it.isNotEmpty() }
        segs[s.id] = ls
        ls.lastOrNull()?.let { byName.getOrPut(it) { ArrayList() } += s }
        byDisplay.getOrPut(key(displayOf(s.artist, s.title))) { ArrayList() } += s
        // Con artista, también solo por título: hay listas cuyo #EXTINF no lo lleva.
        if (s.artist.isNotBlank()) byDisplay.getOrPut("\u0001" + key(s.title)) { ArrayList() } += s
    }

    /** Entre varias, la de duración más parecida a la del #EXTINF (o la primera). */
    fun pick(c: List<M3uSong>, durSec: Int): M3uSong =
        if (durSec <= 0 || c.size == 1) c.first() else c.minBy { kotlin.math.abs(it.durationMs / 1000.0 - durSec) }

    fun byText(e: M3uEntry, within: List<M3uSong>?): M3uSong? {
        val stem = e.path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
        for (text in listOf(e.display, percentDecode(stem))) {
            if (text.isBlank()) continue
            val k = key(text)
            val found = byDisplay[k] ?: byDisplay["\u0001$k"] ?: continue
            val c = if (within == null) found else found.filter { f -> within.any { it.id == f.id } }
            if (c.isNotEmpty()) return pick(c, e.durationSec)
        }
        return null
    }

    fun one(e: M3uEntry): M3uSong? {
        val p = entryPath(e.path, baseDir) ?: return byText(e, null)
        if (p.unixAbsolute) {
            val abs = "/" + p.segments.joinToString("/")
            (byPath[abs] ?: byPathLower[low(abs)])?.let { return it }
        }
        val lower = p.segments.map(::low)
        val same = byName[lower.last()] ?: return byText(e, null)
        // Cuántas carpetas coinciden desde el final (el archivo ya coincide).
        fun common(s: M3uSong): Int {
            val l = segs.getValue(s.id)
            var n = 0
            while (n < l.size && n < lower.size && l[l.size - 1 - n] == lower[lower.size - 1 - n]) n++
            return n
        }
        val scored = same.map { it to common(it) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        if (top.size == 1) return top.first()
        // Empatan (dos «01 Intro.mp3»): decide el #EXTINF entre ellas; si no dice nada, la primera.
        return byText(e, top) ?: pick(top, e.durationSec)
    }

    val ids = ArrayList<Long>()
    var missing = 0
    for (e in entries) one(e)?.let { ids += it.id } ?: missing++
    return M3uMatch(ids, missing)
}

/**
 * La lista en M3U8 (UTF-8): #EXTM3U, y por canción #EXTINF con segundos y «Artista - Título» y su
 * ruta absoluta. Lo que no tiene duración va con -1, como manda el formato.
 */
fun writeM3u8(songs: List<M3uSong>): String = buildString {
    append("#EXTM3U\n")
    for (s in songs) {
        val sec = if (s.durationMs > 0) (s.durationMs + 500) / 1000 else -1
        // Un salto de línea en una etiqueta partiría la entrada en dos.
        val text = displayOf(s.artist, s.title).replace(BREAKS, " ").trim()
        append("#EXTINF:").append(sec).append(',').append(text).append('\n')
        append(s.path).append('\n')
    }
}

/** Nombre de la lista a partir del archivo: «Mi lista.m3u8» → «Mi lista». */
fun playlistNameFromFile(fileName: String): String {
    val base = fileName.replace('\\', '/').substringAfterLast('/')
    val dot = base.lastIndexOf('.')
    return (if (dot > 0 && base.substring(dot + 1).lowercase() in setOf("m3u", "m3u8")) base.substring(0, dot) else base).trim()
}

/** Nombre de archivo para exportar: sin los caracteres que Windows o Android no admiten. */
fun m3uFileName(playlistName: String): String =
    playlistName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifBlank { "playlist" } + ".m3u8"

/**
 * Carpeta real de un documento del selector del sistema a partir de su id de documento, si se
 * deja deducir: «primary:Music/x.m3u» → «/storage/emulated/0/Music», «1A2B-3C4D:Listas/x.m3u» →
 * «/storage/1A2B-3C4D/Listas», «raw:/storage/…/x.m3u» → su carpeta. Las de Descargas («msf:12»)
 * y las de la nube no la dicen: entonces las rutas relativas casan por sufijo.
 */
fun dirFromDocumentId(docId: String): String? {
    val colon = docId.indexOf(':')
    if (colon <= 0) return null
    val volume = docId.substring(0, colon)
    val rel = docId.substring(colon + 1)
    val file = when {
        volume == "raw" -> rel.takeIf { it.startsWith("/") } ?: return null
        volume == "primary" -> "/storage/emulated/0/$rel"
        Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(volume) -> "/storage/$volume/$rel"
        else -> return null
    }
    return file.substringBeforeLast('/', "").ifEmpty { null }
}
