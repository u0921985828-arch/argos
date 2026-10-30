package com.eddie.surco.data

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

// Sonoridad integrada según ITU-R BS.1770-4 / EBU R128 (la misma medida que ReplayGain 2.0), sin
// Android: se prueba en la JVM con las señales de EBU Tech 3341. Sirve para igualar el volumen de
// las canciones que no traen ReplayGain en las etiquetas.

/** Nivel de referencia de ReplayGain 2.0: una canción a −18 LUFS no se toca. */
const val RG2_REFERENCE_LUFS = -18.0

/** Ganancia de ReplayGain (dB) para una canción de [lufs], acotada a lo razonable. */
fun replayGainFromLufs(lufs: Double): Float = (RG2_REFERENCE_LUFS - lufs).coerceIn(-30.0, 20.0).toFloat()

/**
 * Ganancia de álbum a partir de las de sus pistas: la sonoridad del disco es la media de la
 * ENERGÍA de las pistas (no de los dB), como si se midiera el disco entero de una vez.
 */
fun albumReplayGain(trackGains: List<Float>): Float {
    if (trackGains.isEmpty()) return 0f
    val energy = trackGains.map { 10.0.pow((RG2_REFERENCE_LUFS - it) / 10) }.average()
    return replayGainFromLufs(10 * log10(energy))
}

/**
 * Medidor de sonoridad integrada. Las muestras entran intercaladas por canal con [push] (en
 * escala ±1) y [integrated] da los LUFS con las dos puertas de BS.1770 (−70 LUFS y −10 LU), o
 * null si no ha llegado ni un bloque de 400 ms con sonido. Un archivo mono suena por los dos
 * altavoces: cuenta como dos canales iguales (como «dual mono» en libebur128). En 5.1 y 7.1, con el
 * orden de canales de Android, el LFE no cuenta y los de detrás y los laterales pesan 1,41.
 */
class LoudnessMeter(rate: Int, private val channels: Int) {
    // Filtro K: estante de agudos (la cabeza) y paso alto (RLB), calculados para esta frecuencia
    // con las mismas fórmulas que libebur128: valen para 44,1, 48, 96 kHz o lo que llegue.
    private val sb0: Double; private val sb1: Double; private val sb2: Double; private val sa1: Double; private val sa2: Double
    private val ha1: Double; private val ha2: Double

    init {
        var k = tan(PI * 1681.974450955533 / rate)
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(0.4996667741545416)
        var q = 0.7071752369554196
        var a0 = 1 + k / q + k * k
        sb0 = (vh + vb * k / q + k * k) / a0
        sb1 = 2 * (k * k - vh) / a0
        sb2 = (vh - vb * k / q + k * k) / a0
        sa1 = 2 * (k * k - 1) / a0
        sa2 = (1 - k / q + k * k) / a0
        k = tan(PI * 38.13547087602444 / rate)
        q = 0.5003270373238773
        a0 = 1 + k / q + k * k
        ha1 = 2 * (k * k - 1) / a0
        ha2 = (1 - k / q + k * k) / a0
    }

    // Estado de los dos filtros por canal (forma directa II).
    private val s1 = DoubleArray(channels); private val s2 = DoubleArray(channels)
    private val weight = DoubleArray(channels) { c ->
        when {
            channels == 1 -> 2.0
            channels >= 6 && c == 3 -> 0.0
            channels >= 6 && c >= 4 -> 1.41
            else -> 1.0
        }
    }
    private val h1 = DoubleArray(channels); private val h2 = DoubleArray(channels)

    /** Muestras por trozo de 100 ms: un bloque de 400 ms son cuatro trozos (solape del 75 %). */
    private val step = (rate / 10).coerceAtLeast(1)
    private var ch = 0
    private var inStep = 0
    private var acc = 0.0
    private val last = DoubleArray(4)
    private var steps = 0
    /** Energía media (suma de canales) de cada bloque de 400 ms. */
    private val blocks = ArrayList<Double>()

    fun push(v: Double) {
        val c = ch
        // Estante.
        val w = v - sa1 * s1[c] - sa2 * s2[c]
        val y = sb0 * w + sb1 * s1[c] + sb2 * s2[c]
        s2[c] = s1[c]; s1[c] = w
        // Paso alto (numerador 1, −2, 1).
        val u = y - ha1 * h1[c] - ha2 * h2[c]
        val z = u - 2 * h1[c] + h2[c]
        h2[c] = h1[c]; h1[c] = u
        acc += weight[c] * z * z
        if (++ch == channels) {
            ch = 0
            if (++inStep == step) {
                last[steps % 4] = acc / step
                steps++
                if (steps >= 4) blocks += last.sum() / 4
                acc = 0.0; inStep = 0
            }
        }
    }

    fun integrated(): Double? {
        val abs = blocks.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return null
        val gate = lufs(abs.average()) - 10.0
        val rel = abs.filter { lufs(it) > gate }
        return if (rel.isEmpty()) null else lufs(rel.average())
    }

    private fun lufs(energy: Double) = if (energy <= 0) -200.0 else -0.691 + 10 * log10(energy)
}
