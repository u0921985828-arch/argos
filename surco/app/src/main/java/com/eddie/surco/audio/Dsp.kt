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
    val eqActive: Boolean
        get() = (eqOn && bands.any { abs(it) > 0.01f }) || abs(if (eqOn || toneActive) preampDb else 0f) > 0.01f
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

/**
 * Preamplificación que de verdad se aplica: con el ecualizador encendido o con el tono movido
 * (la automática compensa también el tono). Con todo apagado, ninguna.
 */
fun effectivePreampDb(p: DspParams): Float = if (p.eqOn || p.toneActive) p.preampDb else 0f

private fun slotsActive(p: DspParams) = p.toneActive || (p.eqOn && p.bands.any { abs(it) > 0.01f })

/** Respuesta total en dB (preamplificación incluida) en cada frecuencia de [freqs]. */
fun responseDb(p: DspParams, freqs: DoubleArray, fs: Double = 48_000.0): DoubleArray {
    val filters = buildFilters(p, fs)
    val pre = effectivePreampDb(p).toDouble()
    return DoubleArray(freqs.size) { i -> pre + filters.sumOf { it.magnitudeDb(freqs[i], fs) } }
}

/** Pico de la curva de respuesta: lo que habría que bajar la preamplificación para no saturar. */
fun peakGainDb(p: DspParams): Double {
    val freqs = DoubleArray(200) { 20.0 * 1000.0.pow(it / 199.0) }
    return responseDb(p, freqs).max()
}

/**
 * Una configuración concreta de la cadena con su estado. Estructura fija: 10 bandas, graves y
 * agudos siempre en el mismo hueco, así el estado de cada filtro se puede pasar a la
 * configuración siguiente sin mezclar el de una banda con el de otra.
 */
private class Chain(val p: DspParams, private val fs: Int, private val ch: Int) {
    private val slots: Array<Biquad?> = arrayOfNulls(EQ_FREQS.size + 2)
    private val z1 = DoubleArray(slots.size * ch)
    private val z2 = DoubleArray(slots.size * ch)
    private val active: IntArray
    private val preamp = 10.0.pow(effectivePreampDb(p) / 20.0)

    private var cfOn = false
    private var cfA0Lo = 0.0; private var cfB1Lo = 0.0
    private var cfA0Hi = 0.0; private var cfA1Hi = 0.0; private var cfB1Hi = 0.0
    private var cfGain = 1.0
    private val cfLo = DoubleArray(2); private val cfHi = DoubleArray(2); private val cfAsis = DoubleArray(2)

    // Limitador con anticipación: solo si la cadena puede pasar de 0 dB (bandas o tono que suben
    // más de lo que baja la preamplificación). Mono y balance nunca suben: no lo activan.
    // Con filtros el pico puede crecer aunque la curva no pase de 0 dB (el desfase sube la cresta
    // de una onda cuadrada): el limitador vigila siempre que haya filtros, y solo actúa si se pasa.
    private val limiting = p.limiter && (slotsActive(p) || peakGainDb(p) > 0.05)
    // Sin anticipación: un retardo solo cuando limita cambiaría la latencia al encender o apagar
    // efectos, y el fundido entre configuraciones chasquearía (medido: ×8,7 la segunda diferencia).
    private val look = 0
    private val delay = DoubleArray((look + 1) * ch)
    // Cola monótona del máximo de la ventana: posición absoluta y pico de cada trama.
    private val dqPos = LongArray(look + 1)
    private val dqVal = DoubleArray(look + 1)
    private var dqHead = 0; private var dqSize = 0
    private var pos = 0L
    private var gain = 1.0
    private var hold = 0
    private val holdFrames = (0.06 * fs).toInt()
    private val attack = 1 - exp(-1.0 / (0.0005 * fs)) // 0,5 ms; el tope duro de abajo evita pasarse
    private val release = 1 - exp(-1.0 / (0.12 * fs)) // 120 ms

    init {
        val f = fs.toDouble()
        if (p.eqOn) EQ_FREQS.forEachIndexed { i, fc ->
            val g = p.bands.getOrElse(i) { 0f }.toDouble()
            if (abs(g) > 0.01 && fc < f * 0.45) slots[i] = Biquad.peaking(fc.toDouble(), EQ_Q, g, f)
        }
        if (abs(p.bassDb) > 0.01f) slots[EQ_FREQS.size] = Biquad.lowShelf(BASS_FREQ, p.bassDb.toDouble(), f)
        if (abs(p.trebleDb) > 0.01f && TREBLE_FREQ < f * 0.45) slots[EQ_FREQS.size + 1] = Biquad.highShelf(TREBLE_FREQ, p.trebleDb.toDouble(), f)
        active = slots.indices.filter { slots[it] != null }.toIntArray()

        val (fc, feed) = CROSSFEED_LEVELS[p.crossfeed.coerceIn(0, CROSSFEED_LEVELS.lastIndex)]
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
    }

    /** Hereda el estado de [src] en los huecos que allí estaban activos; los demás, a cero. */
    fun inheritFrom(src: Chain) {
        for (k in slots.indices) {
            val live = src.slots[k] != null
            for (c in 0 until ch) {
                val i = k * ch + c
                z1[i] = if (live) src.z1[i] else 0.0
                z2[i] = if (live) src.z2[i] else 0.0
            }
        }
        if (src.cfOn && cfOn) { src.cfLo.copyInto(cfLo); src.cfHi.copyInto(cfHi); src.cfAsis.copyInto(cfAsis) }
        if (limiting && src.limiting && src.look == look) {
            src.delay.copyInto(delay); src.dqPos.copyInto(dqPos); src.dqVal.copyInto(dqVal)
            dqHead = src.dqHead; dqSize = src.dqSize; pos = src.pos; gain = src.gain; hold = src.hold
        }
    }

    fun reset() {
        z1.fill(0.0); z2.fill(0.0)
        cfLo.fill(0.0); cfHi.fill(0.0); cfAsis.fill(0.0)
        delay.fill(0.0); dqHead = 0; dqSize = 0; pos = 0; gain = 1.0; hold = 0
    }

    /**
     * Mete la trama [frame] (ya procesada) en la línea de retardo y deja en [frame] la que sale,
     * con la ganancia del limitador (con [look] = 0, la misma trama).
     */
    private fun limitFrame(frame: DoubleArray) {
        val n = look + 1
        var pk = 0.0
        for (c in 0 until ch) pk = max(pk, abs(frame[c]))
        while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % n] <= pk) dqSize--
        val tail = (dqHead + dqSize) % n
        dqPos[tail] = pos; dqVal[tail] = pk; dqSize++
        while (dqPos[dqHead] < pos - look) { dqHead = (dqHead + 1) % n; dqSize-- }
        val winMax = dqVal[dqHead]
        val target = if (winMax > LIMIT_THR) LIMIT_THR / winMax else 1.0
        // Mantener 60 ms antes de soltar: más que un ciclo de 20 Hz, así no «bombea» dentro de cada
        // ciclo de un grave (eso es distorsión, no limitación).
        if (target < gain) { gain += (target - gain) * attack; hold = holdFrames }
        else if (hold > 0) hold--
        else gain += (target - gain) * release
        // Sale la trama de hace `look` y entra la nueva.
        val outSlot = ((pos + 1) % n).toInt()
        var opk = 0.0
        for (c in 0 until ch) opk = max(opk, abs(if (look == 0) frame[c] else delay[outSlot * ch + c]))
        if (opk * gain > LIMIT_THR) gain = LIMIT_THR / opk
        val inSlot = (pos % n).toInt()
        for (c in 0 until ch) {
            val out = if (look == 0) frame[c] else delay[outSlot * ch + c]
            delay[inSlot * ch + c] = frame[c]
            frame[c] = out * gain
        }
        pos++
    }

    private val frameBuf = DoubleArray(ch)

    fun process(buf: FloatArray, frames: Int) {
        val stereo = ch == 2
        val balL = if (stereo) min(1.0, 1.0 - p.balance) else 1.0
        val balR = if (stereo) min(1.0, 1.0 + p.balance) else 1.0
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x = buf[i + c] * preamp
                for (k in active) {
                    val q = slots[k]!!
                    val idx = k * ch + c
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
                if (limiting) {
                    frameBuf[0] = l; frameBuf[1] = r
                    limitFrame(frameBuf)
                    l = frameBuf[0]; r = frameBuf[1]
                }
                buf[i] = l.coerceIn(-1.0, 1.0).toFloat()
                buf[i + 1] = r.coerceIn(-1.0, 1.0).toFloat()
            } else {
                if (limiting) {
                    for (c in 0 until ch) frameBuf[c] = buf[i + c].toDouble()
                    limitFrame(frameBuf)
                    for (c in 0 until ch) buf[i + c] = frameBuf[c].toFloat()
                } else {
                    for (c in 0 until ch) buf[i + c] = buf[i + c].coerceIn(-1f, 1f)
                }
            }
            i += ch
        }
    }
}

/**
 * Procesa audio intercalado en coma flotante (-1..1). Un objeto por flujo de audio;
 * no es seguro entre hilos: los parámetros se cambian desde el mismo hilo que procesa.
 *
 * Un cambio de parámetros no se aplica de golpe: el siguiente bloque se procesa con la
 * configuración vieja y con la nueva y se funde de una a otra (también al encender o apagar
 * el ecualizador). Sin eso, mover un deslizador o cambiar de preajuste da un chasquido.
 */
private const val FADE_FRAMES = 1024
private const val LIMIT_THR = 0.98

/**
 * Preamplificación que deja la curva sin pasar de 0 dB: lo que suben bandas y tono, en negativo,
 * redondeado a medio decibelio hacia abajo. Con ella el limitador no tiene nada que hacer.
 */
fun autoPreampDb(p: DspParams): Float {
    val peak = peakGainDb(p.copy(preampDb = 0f))
    return if (peak <= 0.0) 0f else -(kotlin.math.ceil(peak * 2) / 2).toFloat()
}

class DspEngine {
    private var fs = 48_000
    private var ch = 2
    private var cur = Chain(DspParams(), fs, ch)
    private var pending: Chain? = null
    private var dry = FloatArray(0)

    val isNeutral: Boolean get() = pending == null && cur.p.neutral

    fun configure(sampleRate: Int, channels: Int) {
        fs = sampleRate; ch = channels
        cur = Chain((pending ?: cur).p, fs, ch)
        pending = null
    }

    fun setParams(p: DspParams) {
        if (p == (pending ?: cur).p) return
        pending = Chain(p, fs, ch).also { it.inheritFrom(cur) }
    }

    fun reset() { cur.reset(); pending = null }

    /** Procesa [frames] tramas de [buf] en su sitio. */
    fun process(buf: FloatArray, frames: Int) {
        val next = pending
        if (next == null) {
            if (!cur.p.neutral) cur.process(buf, frames)
            return
        }
        pending = null
        val n = frames * ch
        if (dry.size < n) dry = FloatArray(n)
        System.arraycopy(buf, 0, dry, 0, n)
        if (!cur.p.neutral) cur.process(dry, frames)          // salida con lo viejo
        if (next.p.neutral) next.reset() else next.process(buf, frames) // salida con lo nuevo
        // Fundido de ~20 ms (o lo que dure el bloque, si es más corto).
        val fade = min(frames, FADE_FRAMES)
        var i = 0
        for (f in 0 until fade) {
            val t = (f + 1).toFloat() / fade
            for (c in 0 until ch) { buf[i] = dry[i] * (1 - t) + buf[i] * t; i++ }
        }
        cur = next
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
