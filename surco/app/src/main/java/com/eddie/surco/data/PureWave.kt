package com.eddie.surco.data

import kotlin.math.log10

// Forma de onda de la barra de progreso, sin Android (se prueba en la JVM).

/** Barras de la forma de onda de una canción: cuántas y la altura mínima (silencio). */
const val WAVE_BARS = 64
const val WAVE_FLOOR = 0.12f

/**
 * Alturas 0–1 de la forma de onda a partir del nivel eficaz (RMS, escala ±1) de cada tramo:
 * en decibelios relativos al tramo más fuerte, con 30 dB de recorrido (así se ven las partes
 * suaves y las fuertes sin que todo quede igual de alto), y un mínimo para que el silencio aún
 * se vea como barra. NaN (un tramo que no se pudo leer) toma el valor del vecino.
 */
fun waveBars(rms: FloatArray): FloatArray {
    if (rms.isEmpty()) return rms
    val v = rms.copyOf()
    for (i in v.indices) if (v[i].isNaN()) v[i] = (v.getOrNull(i - 1)?.takeUnless { it.isNaN() } ?: 0f)
    val peak = v.maxOrNull() ?: 0f
    if (peak <= 1e-5f) return FloatArray(v.size) { WAVE_FLOOR }
    return FloatArray(v.size) { i ->
        val db = if (v[i] <= 0f) -100.0 else 20 * log10((v[i] / peak).toDouble())
        val h = ((db + 30.0) / 30.0).coerceIn(0.0, 1.0).toFloat()
        WAVE_FLOOR + (1f - WAVE_FLOOR) * h
    }
}
