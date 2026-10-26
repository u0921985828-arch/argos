package com.eddie.surco.data

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Lógica pura del reproductor y de la interfaz (sin Android): se prueba en la JVM.

// ---------- Fundido entre canciones ----------

/**
 * Ganancia del fundido entre canciones en [posMs] de una canción de [durMs]: baja los últimos
 * [fadeMs] y sube los primeros [fadeMs]. No se solapan (ExoPlayer toca una canción cada vez),
 * así que es «baja y sube», no un cruce. Curva de potencia constante (seno): a mitad del fundido
 * suena a −3 dB, no a −6, y el oído no nota el bache.
 */
fun crossfadeGain(posMs: Long, durMs: Long, fadeMs: Long, fadeIn: Boolean, fadeOut: Boolean): Float {
    if (fadeMs <= 0 || durMs <= 0) return 1f
    // En canciones cortas el fundido no pasa de un cuarto de la canción por cada lado.
    val f = min(fadeMs, durMs / 4).coerceAtLeast(1)
    var g = 1.0
    if (fadeIn && posMs < f) g = min(g, curve(posMs.toDouble() / f))
    if (fadeOut && durMs - posMs < f) g = min(g, curve((durMs - posMs).toDouble() / f))
    return g.coerceIn(0.0, 1.0).toFloat()
}

private fun curve(x: Double) = kotlin.math.sin(x.coerceIn(0.0, 1.0) * Math.PI / 2)

/**
 * ¿Se funde el paso de una canción a la siguiente? No dentro del mismo disco cuando se pide
 * respetarlos: los discos en directo y los conceptuales van sin pausas a propósito («sin cortes»).
 * Un disco es el mismo álbum en la misma carpeta (dos «Grandes éxitos» distintos no lo son).
 */
fun fadesBetween(a: AlbumRef?, b: AlbumRef?, keepAlbums: Boolean): Boolean {
    if (a == null || b == null) return true
    if (!keepAlbums) return true
    val same = a.album.isNotBlank() && fold(a.album) == fold(b.album) && a.folder == b.folder
    return !same
}

data class AlbumRef(val album: String, val folder: String)

// ---------- Índice de letras ----------

/** Letra con la que se ordena y se agrupa: sin tildes, «#» para lo que empieza por cifra o símbolo. */
fun indexLetter(label: String): Char {
    val c = Normalizer.normalize(label.trim(), Normalizer.Form.NFD).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return '#'
    if (label.trim().uppercase().startsWith("Ñ")) return 'Ñ'
    return if (c in 'A'..'Z') c else '#'
}

/** Para cada letra presente, la primera posición de la lista (ya ordenada) que empieza por ella. */
fun letterIndex(labels: List<String>): LinkedHashMap<Char, Int> {
    val out = LinkedHashMap<Char, Int>()
    labels.forEachIndexed { i, s -> out.putIfAbsent(indexLetter(s), i) }
    return out
}

// ---------- Color de acento ----------

/** Luminancia relativa WCAG de un color 0xRRGGBB. */
fun luminance(rgb: Int): Double {
    fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * ch(rgb shr 16 and 255) + 0.7152 * ch(rgb shr 8 and 255) + 0.0722 * ch(rgb and 255)
}

fun contrast(a: Int, b: Int): Double {
    val la = luminance(a); val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * Un color sacado de una portada, llevado a uno que sirva de acento sobre el fondo [ink]: se
 * aclara (manteniendo el tono) hasta que el texto de color sobre el fondo y el texto [ink] sobre
 * el color pasen de [min]. Un color apagado o muy oscuro sale con más luz; el tono se conserva.
 */
fun legibleAccent(rgb: Int, ink: Int, min: Double = 4.5): Int {
    var (h, s, l) = rgbToHsl(rgb)
    s = s.coerceAtLeast(0.45) // gris de portada → sin color de acento: se le da algo de saturación
    l = l.coerceIn(0.45, 0.85)
    var c = hslToRgb(h, s, l)
    var guard = 0
    while (contrast(c, ink) < min && guard++ < 60) { l = (l + 0.01).coerceAtMost(0.95); c = hslToRgb(h, s, l) }
    return c
}

fun rgbToHsl(rgb: Int): Triple<Double, Double, Double> {
    val r = (rgb shr 16 and 255) / 255.0; val g = (rgb shr 8 and 255) / 255.0; val b = (rgb and 255) / 255.0
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2
    if (mx == mn) return Triple(0.0, 0.0, l)
    val d = mx - mn
    val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6 else 0))
        g -> ((b - r) / d + 2)
        else -> ((r - g) / d + 4)
    } / 6
    return Triple(h, s, l)
}

fun hslToRgb(h: Double, s: Double, l: Double): Int {
    fun hue(p: Double, q: Double, t0: Double): Double {
        var t = t0; if (t < 0) t += 1.0; if (t > 1) t -= 1.0
        return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
    }
    val (r, g, b) = if (s == 0.0) Triple(l, l, l) else {
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        Triple(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3))
    }
    fun i(v: Double) = (v * 255).toInt().coerceIn(0, 255)
    return (i(r) shl 16) or (i(g) shl 8) or i(b)
}

// ---------- Tus escuchas ----------

data class ListenStats(
    val plays: Int,
    val minutes: Long,
    val songs: Int,
    val topArtists: List<Pair<String, Int>>,
    val topSongs: List<Pair<Long, Int>>,
)

/** Resumen de lo escuchado: [plays] por id; [info] da (artista, duración en ms) de cada id que sigue en el móvil. */
fun listenStats(plays: Map<Long, Int>, top: Int = 5, info: (Long) -> Pair<String, Long>?): ListenStats {
    var total = 0; var ms = 0L; var songs = 0
    val byArtist = HashMap<String, Int>()
    val alive = ArrayList<Pair<Long, Int>>()
    for ((id, n) in plays) {
        if (n <= 0) continue
        val (artist, dur) = info(id) ?: continue
        total += n; ms += dur * n; songs++
        alive += id to n
        if (artist.isNotBlank()) byArtist[artist] = (byArtist[artist] ?: 0) + n
    }
    return ListenStats(
        plays = total, minutes = ms / 60_000, songs = songs,
        topArtists = byArtist.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(top).map { it.key to it.value },
        topSongs = alive.sortedWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first }).take(top),
    )
}
