package com.eddie.surco.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Motor de sonido de Surco. Kotlin puro, sin Android: se prueba en la JVM.
 *
 * Orden de la cadena: preamplificación → ecualizador → tono (graves/agudos) →
 * crossfeed → mono → balance → limitador. Con todo en neutro no se toca la
 * muestra (salida idéntica a la entrada).
 */

/** Centros del ecualizador gráfico de 10 bandas, una por octava. */
val EQ_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
const val EQ_MAX_DB = 12f

/** Crossfeed para auriculares (bs2b): 0 apagado, 1 suave, 2 medio, 3 fuerte. */
val CROSSFEED_LEVELS = listOf(
    0 to 0f,         // apagado
    700 to 4.5f,     // suave (bs2b por defecto)
    700 to 6.0f,     // medio (Chu Moy)
    650 to 9.5f,     // fuerte (Jan Meier)
)

data class DspParams(
    val eqOn: Boolean = true,
    val bands: List<Float> = List(EQ_FREQS.size) { 0f },
    val preampDb: Float = 0f,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** -1 todo a la izquierda, 1 todo a la derecha. */
    val balance: Float = 0f,
    val mono: Boolean = false,
    val crossfeed: Int = 0,
    val limiter: Boolean = true,
) {
    val eqActive: Boolean get() = eqOn && (bands.any { abs(it) > 0.01f } || abs(preampDb) > 0.01f)
    val toneActive: Boolean get() = abs(bassDb) > 0.01f || abs(trebleDb) > 0.01f

    /** Sin ningún efecto: la señal pasa intacta. */
    val neutral: Boolean
        get() = !eqActive && !toneActive && abs(balance) < 0.001f && !mono && crossfeed == 0
}

/** Biquad en forma directa II transpuesta; coeficientes del «Audio EQ Cookbook» de R. Bristow-Johnson. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** Ganancia en dB a la frecuencia [f] con frecuencia de muestreo [fs]. */
    fun magnitudeDb(f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * log10(max(mag2, 1e-24))
    }

    companion object {
        private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun peaking(f0: Double, q: Double, gainDb: Double, fs: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f0 / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return norm(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun lowShelf(f0: Double, gainDb: Double, fs: Double, slope: Double = 1.0): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f0 / fs
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val sa = 2 * sqrt(a) * alpha
            return norm(
                a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa,
            )
        }

        fun highShelf(f0: Double, gainDb: Double, fs: Double, slope: Double = 1.0): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f0 / fs
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val sa = 2 * sqrt(a) * alpha
            return norm(
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa,
            )
        }
    }
}

/** Una octava de ancho de banda. */
private const val EQ_Q = 1.41

const val BASS_FREQ = 100.0
const val TREBLE_FREQ = 8000.0

/** Filtros que corresponden a unos parámetros, en orden. Sirve para procesar y para dibujar la curva. */
fun buildFilters(p: DspParams, fs: Double): List<Biquad> = buildList {
    if (p.eqOn) {
        EQ_FREQS.forEachIndexed { i, f ->
            val g = p.bands.getOrElse(i) { 0f }.toDouble()
            if (abs(g) > 0.01 && f < fs * 0.45) add(Biquad.peaking(f.toDouble(), EQ_Q, g, fs))
        }
    }
    if (abs(p.bassDb) > 0.01f) add(Biquad.lowShelf(BASS_FREQ, p.bassDb.toDouble(), fs))
    if (abs(p.trebleDb) > 0.01f && TREBLE_FREQ < fs * 0.45) add(Biquad.highShelf(TREBLE_FREQ, p.trebleDb.toDouble(), fs))
}

/** Respuesta total en dB (preamplificación incluida) en cada frecuencia de [freqs]. */
fun responseDb(p: DspParams, freqs: DoubleArray, fs: Double = 48_000.0): DoubleArray {
    val filters = buildFilters(p, fs)
    val pre = if (p.eqOn) p.preampDb.toDouble() else 0.0
    return DoubleArray(freqs.size) { i -> pre + filters.sumOf { it.magnitudeDb(freqs[i], fs) } }
}

/** Pico de la curva de respuesta: lo que habría que bajar la preamplificación para no saturar. */
fun peakGainDb(p: DspParams): Double {
    val freqs = DoubleArray(200) { 20.0 * 1000.0.pow(it / 199.0) }
    return responseDb(p, freqs).max()
}

/**
 * Procesa audio intercalado en coma flotante (-1..1). Un objeto por flujo de audio;
 * no es seguro entre hilos: los parámetros se cambian desde el mismo hilo que procesa.
 */
class DspEngine {
    private var fs = 48_000
    private var ch = 2
    private var params = DspParams()
    private var filters: Array<Biquad> = emptyArray()
    // Estado de cada filtro por canal: z1, z2.
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)
    private var preamp = 1.0

    // Crossfeed (bs2b)
    private var cfOn = false
    private var cfA0Lo = 0.0; private var cfB1Lo = 0.0
    private var cfA0Hi = 0.0; private var cfA1Hi = 0.0; private var cfB1Hi = 0.0
    private var cfGain = 1.0
    private val cfLo = DoubleArray(2); private val cfHi = DoubleArray(2); private val cfAsis = DoubleArray(2)

    // Limitador
    private var env = 0.0
    private var release = 0.0

    val isNeutral: Boolean get() = params.neutral

    fun configure(sampleRate: Int, channels: Int) {
        fs = sampleRate; ch = channels
        release = exp(-1.0 / (0.12 * fs)) // 120 ms
        rebuild(resetState = true)
    }

    fun setParams(p: DspParams) {
        if (p == params) return
        params = p
        rebuild(resetState = false)
    }

    fun reset() {
        z1.fill(0.0); z2.fill(0.0)
        cfLo.fill(0.0); cfHi.fill(0.0); cfAsis.fill(0.0)
        env = 0.0
    }

    private fun rebuild(resetState: Boolean) {
        val nf = buildFilters(params, fs.toDouble()).toTypedArray()
        // Si el número de filtros no cambia se conserva su estado: mover un deslizador no chasquea.
        if (resetState || nf.size != filters.size || z1.size != nf.size * ch) {
            z1 = DoubleArray(nf.size * ch); z2 = DoubleArray(nf.size * ch)
        }
        filters = nf
        preamp = if (params.eqOn) 10.0.pow(params.preampDb / 20.0) else 1.0

        val (fc, feed) = CROSSFEED_LEVELS[params.crossfeed.coerceIn(0, CROSSFEED_LEVELS.lastIndex)]
        cfOn = ch == 2 && fc > 0
        if (cfOn) {
            val gbLo = feed * -5.0 / 6.0 - 3.0
            val gbHi = feed / 6.0 - 3.0
            val gLo = 10.0.pow(gbLo / 20)
            val gHi = 1.0 - 10.0.pow(gbHi / 20)
            val fcHi = fc * 2.0.pow((gbLo - 20 * log10(gHi)) / 12)
            var x = exp(-2 * PI * fc / fs)
            cfB1Lo = x; cfA0Lo = gLo * (1 - x)
            x = exp(-2 * PI * fcHi / fs)
            cfB1Hi = x; cfA0Hi = 1 - gHi * (1 - x); cfA1Hi = -x
            cfGain = 1.0 / (1.0 - gHi + gLo)
        }
        if (resetState) reset()
    }

    /** Procesa [frames] tramas de [buf] en su sitio. */
    fun process(buf: FloatArray, frames: Int) {
        if (params.neutral) return
        val p = params
        val nf = filters.size
        val stereo = ch == 2
        val balL = if (stereo) min(1.0, 1.0 - p.balance) else 1.0
        val balR = if (stereo) min(1.0, 1.0 + p.balance) else 1.0
        val limit = p.limiter
        val thr = 0.98
        var i = 0
        for (f in 0 until frames) {
            // Preamplificación y filtros, canal a canal
            for (c in 0 until ch) {
                var x = buf[i + c] * preamp
                for (n in 0 until nf) {
                    val q = filters[n]
                    val idx = n * ch + c
                    val y = q.b0 * x + z1[idx]
                    z1[idx] = q.b1 * x - q.a1 * y + z2[idx]
                    z2[idx] = q.b2 * x - q.a2 * y
                    x = y
                }
                buf[i + c] = x.toFloat()
            }
            if (stereo) {
                var l = buf[i].toDouble(); var r = buf[i + 1].toDouble()
                if (cfOn) {
                    cfLo[0] = cfA0Lo * l + cfB1Lo * cfLo[0]
                    cfLo[1] = cfA0Lo * r + cfB1Lo * cfLo[1]
                    cfHi[0] = cfA0Hi * l + cfA1Hi * cfAsis[0] + cfB1Hi * cfHi[0]
                    cfHi[1] = cfA0Hi * r + cfA1Hi * cfAsis[1] + cfB1Hi * cfHi[1]
                    cfAsis[0] = l; cfAsis[1] = r
                    l = (cfHi[0] + cfLo[1]) * cfGain
                    r = (cfHi[1] + cfLo[0]) * cfGain
                }
                if (p.mono) { val m = (l + r) * 0.5; l = m; r = m }
                l *= balL; r *= balR
                if (limit) {
                    val peak = max(abs(l), abs(r))
                    env = if (peak > env) peak else env * release + peak * (1 - release)
                    if (env > thr) { val g = thr / env; l *= g; r *= g }
                }
                buf[i] = l.coerceIn(-1.0, 1.0).toFloat()
                buf[i + 1] = r.coerceIn(-1.0, 1.0).toFloat()
            } else {
                var peak = 0.0
                for (c in 0 until ch) peak = max(peak, abs(buf[i + c].toDouble()))
                var g = 1.0
                if (limit) {
                    env = if (peak > env) peak else env * release + peak * (1 - release)
                    if (env > thr) g = thr / env
                }
                for (c in 0 until ch) buf[i + c] = (buf[i + c] * g).coerceIn(-1.0, 1.0).toFloat()
            }
            i += ch
        }
    }
}

/** Ajustes de fábrica del ecualizador (dB por banda, de 31 Hz a 16 kHz). */
val BUILTIN_PRESETS: List<Pair<String, List<Float>>> = listOf(
    "Plano" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Graves" to listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f),
    "Graves y agudos" to listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
    "Agudos" to listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5f, 6f),
    "Voz" to listOf(-3f, -2f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -1f),
    "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 4f),
    "Pop" to listOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, 0f, -1f),
    "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
    "Clásica" to listOf(4f, 3f, 2f, 1f, 0f, 0f, 0f, 1f, 2f, 3f),
    "Electrónica" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
    "Acústica" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f),
    "Nocturno" to listOf(-4f, -3f, -1f, 0f, 1f, 1f, 0f, -1f, -3f, -4f),
)
