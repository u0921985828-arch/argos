package com.eddie.surco.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

// Letras de LRCLIB (lrclib.net): base libre y sin clave, con letras sincronizadas. Solo se usa si
// se activa en Ajustes; lo único que sale del móvil es artista, título, álbum y duración.
// Sin Android: se prueba en la JVM (también contra el servicio de verdad, con SURCO_RED=1).

const val LRCLIB = "https://lrclib.net"
const val LRCLIB_AGENT = "Surco/2.7 (reproductor de música para Android)"

private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

fun lrclibGetUrl(artist: String, title: String, album: String, durationSec: Int): String =
    "$LRCLIB/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
        (if (album.isNotBlank()) "&album_name=${enc(album)}" else "") + "&duration=$durationSec"

fun lrclibSearchUrl(artist: String, title: String): String =
    "$LRCLIB/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"

/**
 * Letra de una respuesta de LRCLIB: la sincronizada si la hay, si no la de texto. Una lista
 * (búsqueda) se filtra por duración (± 3 s: otra versión de la canción no casa con la letra
 * sincronizada) y gana la que trae letra sincronizada. «» = instrumental; null = nada útil.
 */
fun lrclibPick(json: Any?, durationSec: Int): String? {
    fun one(o: Map<*, *>): String? {
        if (o["instrumental"] == true) return ""
        val synced = (o["syncedLyrics"] as? String)?.takeIf { it.isNotBlank() }
        val plain = (o["plainLyrics"] as? String)?.takeIf { it.isNotBlank() }
        return synced ?: plain
    }
    return when (json) {
        is Map<*, *> -> one(json)
        is List<*> -> json.filterIsInstance<Map<*, *>>()
            .filter { o -> (o["duration"] as? Number)?.let { abs(it.toDouble() - durationSec) <= 3 } ?: false }
            .sortedByDescending { (it["syncedLyrics"] as? String)?.isNotBlank() == true }
            .firstNotNullOfOrNull { one(it)?.takeIf { s -> s.isNotEmpty() } }
        else -> null
    }
}

/**
 * Busca la letra: primero la ficha exacta (artista, título, álbum y duración); si no está, la
 * búsqueda por artista y título filtrada por duración. [http] devuelve (código, cuerpo) o null
 * si no hay red. Resultado: la letra, «» si es instrumental, o null si no se encontró.
 */
fun fetchLrclib(artist: String, title: String, album: String, durationSec: Int, http: (String) -> Pair<Int, String>?): String? {
    if (artist.isBlank() || title.isBlank() || durationSec <= 0) return null
    http(lrclibGetUrl(artist, title, album, durationSec))?.let { (code, body) ->
        if (code == 200) lrclibPick(Json.parse(body), durationSec)?.let { return it }
    }
    http(lrclibSearchUrl(artist, title))?.let { (code, body) ->
        if (code == 200) return lrclibPick(Json.parse(body), durationSec)
    }
    return null
}

/** GET con el agente de Surco y plazos cortos: una letra no puede dejar la pantalla esperando. */
fun httpGet(url: String): Pair<Int, String>? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", LRCLIB_AGENT)
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        code to body
    } finally { c.disconnect() }
}.getOrNull()

/** JSON mínimo (objetos, listas, textos, números, verdadero/falso, nulo): lo justo para LRCLIB, sin Android. */
object Json {
    fun parse(s: String): Any? = runCatching { P(s).run { val v = value(); ws(); if (i != s.length) null else v } }.getOrNull()

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else error("JSON: $c")
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)); i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("JSON: objeto") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("JSON: lista") }
            }
        }
        fun str(): String {
            require(s[i] == '"'); i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}
