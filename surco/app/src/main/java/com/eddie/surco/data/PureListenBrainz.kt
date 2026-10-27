package com.eddie.surco.data

import java.net.HttpURLConnection
import java.net.URL

// Escuchas a ListenBrainz (listenbrainz.org): libre y abierto, basta el token del usuario (Last.fm
// pide clave y secreto de desarrollador). Apagado por defecto; lo único que sale del móvil es
// artista, título, álbum y duración de lo que se escucha. Sin Android: se prueba en la JVM.

const val LB_API = "https://api.listenbrainz.org/1"
const val LB_SUBMIT = "$LB_API/submit-listens"
const val LB_VALIDATE = "$LB_API/validate-token"
const val LB_SETTINGS = "https://listenbrainz.org/settings/"
const val LB_CLIENT = "Surco"
const val LB_CLIENT_VERSION = "2.7"
/** Tope de la cola sin conexión: más allá se pierden las más antiguas, no el móvil entero. */
const val LB_QUEUE_MAX = 1000
/** Escuchas por envío («import»). */
const val LB_BATCH = 100
/** ListenBrainz no quiere canciones de menos de 30 s (avisos, interludios, pruebas de sonido). */
const val LB_MIN_MS = 30_000L

/** Una escucha: [listenedAt] en segundos Unix (cuando empezó); 0 en «sonando ahora». */
data class Listen(val artist: String, val title: String, val album: String, val durationMs: Long, val listenedAt: Long)

/** Regla de ListenBrainz: cuenta si se oyó la mitad o 4 minutos, lo que llegue antes. */
fun lbCounts(heardMs: Long, durationMs: Long): Boolean =
    durationMs >= LB_MIN_MS && heardMs >= minOf(durationMs / 2, 240_000L)

/** Sin artista o título el servidor la rechaza; sin duración no se sabe si dura 30 s. */
fun lbSendable(artist: String, title: String, durationMs: Long): Boolean =
    artist.isNotBlank() && title.isNotBlank() && durationMs >= LB_MIN_MS

/**
 * El token tal como se pega: a veces con espacios, saltos de línea o el «Token » de la cabecera
 * delante. Un salto de línea en la cabecera haría fallar la conexión como si no hubiera red.
 */
fun lbCleanToken(s: String): String {
    val t = s.trim()
    val bare = if (t.length > 5 && t.startsWith("token", ignoreCase = true) && t[5].isWhitespace()) t.substring(6) else t
    return bare.filterNot { it.isWhitespace() || it.isISOControl() }
}

/** Texto JSON entre comillas: comillas, barras y caracteres de control escapados. */
fun jsonStr(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        // Controles, DEL y los separadores de línea de Unicode (rompen algunos lectores de JSON).
        else -> if (c < ' ' || c == '\u007f' || c == ' ' || c == ' ') {
            append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
        } else append(c)
    }
    append('"')
}

/** Una escucha del «payload»; sin [withTime] es «sonando ahora» (que no lleva listened_at). */
fun lbItem(l: Listen, withTime: Boolean): String = buildString {
    append('{')
    if (withTime) append("\"listened_at\":").append(l.listenedAt).append(',')
    append("\"track_metadata\":{\"artist_name\":").append(jsonStr(l.artist.trim()))
    append(",\"track_name\":").append(jsonStr(l.title.trim()))
    if (l.album.isNotBlank()) append(",\"release_name\":").append(jsonStr(l.album.trim()))
    append(",\"additional_info\":{")
    if (l.durationMs > 0) append("\"duration_ms\":").append(l.durationMs).append(',')
    append("\"media_player\":").append(jsonStr(LB_CLIENT))
    append(",\"submission_client\":").append(jsonStr(LB_CLIENT))
    append(",\"submission_client_version\":").append(jsonStr(LB_CLIENT_VERSION))
    append("}}}")
}

/** Cuerpo de submit-listens: «single» (una), «import» (varias) o «playing_now» (sin hora). */
fun lbBody(type: String, listens: List<Listen>): String =
    "{\"listen_type\":${jsonStr(type)},\"payload\":[" +
        listens.joinToString(",") { lbItem(it, type != "playing_now") } + "]}"

/** Nombre de usuario si validate-token dice que vale; null si no vale o no se entiende. */
fun lbValidUser(body: String): String? {
    val o = Json.parse(body) as? Map<*, *> ?: return null
    if (o["valid"] != true) return null
    return (o["user_name"] as? String)?.takeIf { it.isNotBlank() }
}

// ---------- Cola sin conexión ----------

/** La cola como texto: una lista JSON con el mismo formato que el «payload». */
fun lbQueueText(q: List<Listen>): String = q.joinToString(",", "[", "]") { lbItem(it, true) }

/** La cola leída del archivo; null si el archivo está roto (se aparta, no se pisa). */
fun lbQueueParse(text: String): List<Listen>? {
    val list = Json.parse(text) as? List<*> ?: return null
    return list.mapNotNull { e ->
        val o = e as? Map<*, *> ?: return@mapNotNull null
        val at = (o["listened_at"] as? Number)?.toLong() ?: return@mapNotNull null
        val m = o["track_metadata"] as? Map<*, *> ?: return@mapNotNull null
        val info = m["additional_info"] as? Map<*, *>
        val artist = m["artist_name"] as? String ?: return@mapNotNull null
        val title = m["track_name"] as? String ?: return@mapNotNull null
        Listen(artist, title, m["release_name"] as? String ?: "", (info?.get("duration_ms") as? Number)?.toLong() ?: 0L, at)
    }.takeLast(LB_QUEUE_MAX)
}

/** Añade al final; la misma canción a la misma hora no se repite, y pasado el tope caen las más viejas. */
fun lbEnqueue(q: List<Listen>, l: Listen): List<Listen> =
    if (q.any { it.listenedAt == l.listenedAt && it.title == l.title && it.artist == l.artist }) q
    else (q + l).takeLast(LB_QUEUE_MAX)

/** Quita de [q] lo enviado, una vez cada una: lo que llegó mientras se enviaba se queda. */
fun lbRemove(q: List<Listen>, done: List<Listen>): List<Listen> {
    if (done.isEmpty()) return q
    val left = done.groupingBy { it }.eachCount().toMutableMap()
    return q.filter { l ->
        val n = left[l] ?: 0
        if (n > 0) { left[l] = n - 1; false } else true
    }
}

enum class LbStatus {
    /** Todo enviado. */
    OK,
    /** 401: el token no vale; se para hasta que el usuario lo cambie. */
    BAD_TOKEN,
    /** Sin red, 429 o 5xx: se reintenta más tarde con lo que queda. */
    RETRY,
}

/** Lo que salió de la cola ([done]: enviadas, o rechazadas por el servidor una a una) y cómo acabó. */
class LbFlush(val done: List<Listen>, val status: LbStatus)

/**
 * Vacía la cola en lotes de [LB_BATCH]: «import» (o «single» si es una). [post] envía un cuerpo y
 * devuelve el código HTTP, o null sin red. Solo sale de la cola lo que recibe 200; la excepción es
 * un 400 (dato que el servidor nunca aceptará): el lote se reenvía una a una para que una escucha
 * mala no atasque la cola para siempre ni arrastre a las buenas, y solo se descarta la rechazada.
 */
fun lbFlush(queue: List<Listen>, post: (String) -> Int?): LbFlush {
    val done = ArrayList<Listen>()
    for (batch in queue.chunked(LB_BATCH)) {
        when (post(lbBody(if (batch.size == 1) "single" else "import", batch))) {
            200 -> done += batch
            401 -> return LbFlush(done, LbStatus.BAD_TOKEN)
            400 -> if (batch.size == 1) done += batch else for (l in batch) {
                when (post(lbBody("single", listOf(l)))) {
                    200, 400 -> done += l
                    401 -> return LbFlush(done, LbStatus.BAD_TOKEN)
                    else -> return LbFlush(done, LbStatus.RETRY)
                }
            }
            else -> return LbFlush(done, LbStatus.RETRY)
        }
    }
    return LbFlush(done, LbStatus.OK)
}

/**
 * Petición a ListenBrainz con el token y plazos cortos (como httpGet de LRCLIB): GET sin [body],
 * POST JSON con él. Devuelve (código, cuerpo) o null si no hay red.
 */
fun lbHttp(url: String, token: String, body: String? = null): Pair<Int, String>? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "$LB_CLIENT/$LB_CLIENT_VERSION")
        c.setRequestProperty("Authorization", "Token $token")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        code to text
    } finally { c.disconnect() }
}.getOrNull()
