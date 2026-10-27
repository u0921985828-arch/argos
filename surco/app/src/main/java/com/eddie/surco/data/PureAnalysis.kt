package com.eddie.surco.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Análisis de BPM y tonalidad de una canción, y el catálogo armónico (rueda Camelot), sin
// Android: se prueba en la JVM con señales sintéticas. Entra audio mono ya decodificado.

// ---------- Tonalidad ----------

/** Tonalidad: [pitch] 0 = Do … 11 = Si; [minor] menor o mayor. */
data class MusicKey(val pitch: Int, val minor: Boolean) {
    /** Número de la rueda Camelot (1–12): las vecinas se mezclan sin choques. */
    val camelot: Int get() = camelotOfMajor(if (minor) (pitch + 3) % 12 else pitch)
    /** «8A» (menor) o «8B» (mayor). */
    val code: String get() = "$camelot${if (minor) 'A' else 'B'}"
    /** «La menor · 8A» (en el idioma de la app: «A minor · 8A»). */
    val label: String get() {
        val names = Texts.t("data_note_names", NOTE_NAMES.joinToString(",")).split(',')
        val note = names.getOrNull(pitch)?.trim() ?: NOTE_NAMES[pitch]
        val mode = if (minor) Texts.t("data_key_minor", "%1\$s menor") else Texts.t("data_key_major", "%1\$s mayor")
        return "${mode.format(note)} · $code"
    }
    /** Orden en la rueda: 1A, 1B, 2A, 2B… */
    val order: Int get() = camelot * 2 + if (minor) 0 else 1
}

val NOTE_NAMES = listOf("Do", "Do♯", "Re", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")

private fun camelotOfMajor(p: Int) = ((p * 7) % 12 + 7) % 12 + 1

private val LETTER = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
private val SOLFEO = listOf("sol" to 7, "do" to 0, "re" to 2, "mi" to 4, "fa" to 5, "la" to 9, "si" to 11)

/**
 * Lee la tonalidad de una etiqueta tal como la escriben los programas: «Am», «A minor», «C#m»,
 * «Dbmaj», «F♯ min», «8A» (Camelot), «1m»/«1d» (Open Key de Traktor), «La menor», «Mi♭ mayor».
 */
fun parseKey(raw: String): MusicKey? {
    val s = raw.trim().replace('♯', '#').replace('♭', 'b')
    if (s.isEmpty()) return null
    Regex("""^0?(\d{1,2})\s*([ABab])$""").matchEntire(s)?.let { m ->
        val n = m.groupValues[1].toInt(); if (n !in 1..12) return null
        return fromCamelot(n, m.groupValues[2].uppercase() == "A")
    }
    Regex("""^(\d{1,2})\s*([mdMD])$""").matchEntire(s)?.let { m ->
        val n = m.groupValues[1].toInt(); if (n !in 1..12) return null
        return fromCamelot((n + 6) % 12 + 1, m.groupValues[2].lowercase() == "m")
    }
    val low = s.lowercase()
    var pitch: Int
    var rest: String
    val sol = SOLFEO.firstOrNull { low.startsWith(it.first) }
    if (sol != null) {
        pitch = sol.second; rest = s.substring(sol.first.length)
    } else {
        pitch = LETTER[s[0].uppercaseChar()] ?: return null; rest = s.substring(1)
    }
    if (rest.startsWith("#")) { pitch++; rest = rest.substring(1) } else if (rest.startsWith("b")) { pitch--; rest = rest.substring(1) }
    val mode = rest.trim().lowercase().replace(".", "")
    val minor = when {
        mode.isEmpty() || mode.startsWith("maj") || mode.startsWith("mayor") || mode == "dur" -> false
        mode == "m" || mode.startsWith("min") || mode.startsWith("menor") || mode == "moll" -> true
        else -> return null
    }
    return MusicKey(((pitch % 12) + 12) % 12, minor)
}

fun fromCamelot(n: Int, minor: Boolean): MusicKey {
    val major = (0..11).first { camelotOfMajor(it) == n }
    return if (minor) MusicKey((major + 9) % 12, true) else MusicKey(major, false)
}

/** ¿Suenan bien seguidas? Misma casilla, una más o una menos en la rueda, o su relativa. */
fun harmonic(a: MusicKey, b: MusicKey): Boolean {
    if (a.minor == b.minor) { val d = abs(a.camelot - b.camelot); return d == 0 || d == 1 || d == 11 }
    return a.camelot == b.camelot
}

/** ¿Se pueden mezclar a tempo? Dentro de ±[pct] %, también a mitad o al doble. */
fun tempoMatch(a: Float, b: Float, pct: Float = 6f): Boolean {
    if (a <= 0 || b <= 0) return false
    return listOf(b, b * 2, b / 2).any { abs(it - a) / a * 100 <= pct }
}

// Perfiles de Krumhansl–Kessler: cuánto «pertenece» cada grado a una tonalidad mayor o menor.
private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

/** Tonalidad estimada y su confianza (correlación del perfil ganador menos la del segundo). */
data class KeyEstimate(val key: MusicKey, val confidence: Double)

/**
 * Tonalidad por cromagrama: energía de cada semitono de La2 (110 Hz) a Si6 (~1976 Hz) con
 * Goertzel en ventanas de Hann, plegada a 12 clases y comparada con los 24 perfiles.
 */
fun estimateKey(x: FloatArray, sr: Int): KeyEstimate? {
    val n = 4096
    if (x.size < n * 2) return null
    val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    val chroma = DoubleArray(12)
    val notes = (0 until 52).map { 110.0 * 2.0.pow(it / 12.0) }.filter { it < sr / 2.2 }
    val coefs = notes.map { 2 * cos(2 * PI * it / sr) }
    var start = 0
    while (start + n <= x.size) {
        val frame = DoubleArray(12)
        for ((k, c) in coefs.withIndex()) {
            var s1 = 0.0; var s2 = 0.0
            for (i in 0 until n) { val s0 = x[start + i] * window[i] + c * s1 - s2; s2 = s1; s1 = s0 }
            val p = s1 * s1 + s2 * s2 - c * s1 * s2
            // Clase de la nota: La (110 Hz) es la 9.
            frame[(k + 9) % 12] += sqrt(max(p, 0.0))
        }
        // Cada ventana pesa por igual: un golpe fuerte no decide la tonalidad.
        val tot = frame.sum()
        if (tot > 0) for (i in 0..11) chroma[i] += frame[i] / tot
        start += n
    }
    if (chroma.sum() <= 0) return null
    var best = -2.0; var second = -2.0; var bestKey = MusicKey(0, false)
    for (minor in listOf(false, true)) for (tonic in 0..11) {
        val prof = if (minor) MINOR_PROFILE else MAJOR_PROFILE
        val r = pearson(DoubleArray(12) { chroma[(it + tonic) % 12] }, prof)
        if (r > best) { second = best; best = r; bestKey = MusicKey(tonic, minor) } else if (r > second) second = r
    }
    return KeyEstimate(bestKey, best - second)
}

private fun pearson(a: DoubleArray, b: DoubleArray): Double {
    val ma = a.average(); val mb = b.average()
    var num = 0.0; var da = 0.0; var db = 0.0
    for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; num += x * y; da += x * x; db += y * y }
    return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
}

// ---------- BPM ----------

/**
 * Tempo por autocorrelación de la envolvente de ataques (subidas de energía, en ventanas de
 * ~5 ms). Entre 60 y 200 BPM; ante la duda entre un tempo y su mitad (el «doble tiempo»), gana
 * el más rápido si su pico es casi tan bueno: con golpes en cada pulso, la mitad también encaja.
 */
fun estimateBpm(x: FloatArray, sr: Int): Float? {
    val hop = max(1, sr / 200)
    val frames = x.size / hop
    if (frames < 200 * 8) return null // menos de 8 s no da un tempo fiable
    val raw = DoubleArray(frames) { f ->
        var e = 0.0
        val o = f * hop
        for (i in 0 until hop) { val v = x[o + i].toDouble(); e += v * v }
        e
    }
    // Compresión suave respecto a la energía media: con logaritmo puro, un charles sobre silencio
    // sube tanto como un bombo y el tempo sale al doble.
    val meanE = raw.average().takeIf { it > 0 } ?: return null
    val energy = DoubleArray(frames) { ln(1 + 10 * raw[it] / meanE) }
    // Ataques: lo que sube la energía respecto a la media reciente.
    val onset = DoubleArray(frames)
    for (f in 1 until frames) onset[f] = max(0.0, energy[f] - energy[f - 1])
    val mean = onset.average()
    for (f in onset.indices) onset[f] -= mean
    val rate = sr.toDouble() / hop
    val minLag = (rate * 60 / 200).toInt().coerceAtLeast(1)
    val maxLag = (rate * 60 / 60).toInt()
    fun acf(lag: Int): Double {
        if (lag >= frames) return 0.0
        var s = 0.0
        for (i in 0 until frames - lag) s += onset[i] * onset[i + lag]
        return s / (frames - lag)
    }
    val acf = DoubleArray(maxLag * 4 + 2) { acf(it) }
    // Un periodo no cae justo en una ventana: el pico se busca a ±1.
    fun near(l: Int) = if (l + 1 >= acf.size) 0.0 else max(acf[l], max(acf[l - 1], acf[l + 1]))
    // Peine: el pulso verdadero también tiene picos en 2 y 3 periodos.
    fun comb(lag: Int) = near(lag) + 0.5 * near(lag * 2) + 0.33 * near(lag * 3)
    val scores = (minLag..maxLag).associateWith { comb(it) }
    val top = scores.values.maxOrNull() ?: return null
    if (top <= 0) return null
    val lag = scores.filter { it.value >= 0.8 * top && isPeak(acf, it.key) }.keys.minOrNull() ?: scores.maxByOrNull { it.value }!!.key
    // Afinado entre ventanas (interpolación parabólica del pico).
    val y0 = acf[lag - 1]; val y1 = acf[lag]; val y2 = acf[lag + 1]
    val d = (y0 - y2) / (2 * (y0 - 2 * y1 + y2)).let { if (it == 0.0) 1.0 else it }
    val exact = lag + d.coerceIn(-0.5, 0.5)
    val bpm = 60 * rate / exact
    return ((bpm * 2).roundToInt() / 2.0).toFloat()
}

private fun isPeak(a: DoubleArray, i: Int) = i > 0 && i + 1 < a.size && a[i] >= a[i - 1] && a[i] >= a[i + 1]

/** Texto del BPM para la etiqueta: «128» o «128,5» («128.5» en inglés). */
fun bpmText(b: Float): String = if (b % 1f == 0f) "${b.toInt()}" else "%.1f".format(Texts.locale(), b)
