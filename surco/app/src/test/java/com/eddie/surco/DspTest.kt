package com.eddie.surco

import com.eddie.surco.audio.BUILTIN_PRESETS
import com.eddie.surco.audio.Biquad
import com.eddie.surco.audio.DspEngine
import com.eddie.surco.audio.DspParams
import com.eddie.surco.audio.EQ_FREQS
import com.eddie.surco.audio.peakGainDb
import com.eddie.surco.audio.responseDb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class DspTest {
    private val fs = 48_000

    /** Estéreo: seno de [freq] Hz y amplitud [amp] en los canales indicados. */
    private fun sine(freq: Double, amp: Float, frames: Int, left: Boolean = true, right: Boolean = true) =
        FloatArray(frames * 2) { i ->
            val v = (amp * sin(2 * PI * freq * (i / 2) / fs)).toFloat()
            if ((i % 2 == 0 && left) || (i % 2 == 1 && right)) v else 0f
        }

    /** RMS de un canal saltando el transitorio inicial. */
    private fun rms(buf: FloatArray, ch: Int, skip: Int = 4800): Double {
        var s = 0.0; var n = 0
        var i = skip * 2 + ch
        while (i < buf.size) { s += buf[i] * buf[i].toDouble(); n++; i += 2 }
        return sqrt(s / n)
    }

    private fun db(x: Double) = 20 * log10(x)

    private fun run(p: DspParams, buf: FloatArray): FloatArray {
        val e = DspEngine(); e.configure(fs, 2); e.setParams(p)
        e.process(FloatArray(2048 * 2), 2048) // el cambio de parámetros se funde en el primer bloque
        val out = buf.copyOf(); e.process(out, out.size / 2); return out
    }

    @Test fun neutralEsTransparente() {
        val r = Random(1)
        val buf = FloatArray(9600) { r.nextFloat() * 2 - 1 }
        assertArrayEquals(buf, run(DspParams(), buf), 0f)
        assertTrue(DspParams().neutral)
    }

    @Test fun bandaDaSuGananciaEnSuCentro() {
        for ((i, f) in EQ_FREQS.withIndex()) {
            val bands = MutableList(10) { 0f }.also { it[i] = 6f }
            val pk = Biquad.peaking(f.toDouble(), 1.41, 6.0, fs.toDouble())
            assertEquals("banda $f", 6.0, pk.magnitudeDb(f.toDouble(), fs.toDouble()), 0.05)
            val resp = responseDb(DspParams(bands = bands), doubleArrayOf(f.toDouble()))
            assertEquals(6.0, resp[0], 0.05)
        }
    }

    @Test fun senoAMilHerciosSubeSeisDecibelios() {
        val bands = MutableList(10) { 0f }.also { it[5] = 6f }
        val inp = sine(1000.0, 0.1f, 24_000)
        val out = run(DspParams(bands = bands, limiter = false), inp)
        assertEquals(6.0, db(rms(out, 0) / rms(inp, 0)), 0.15)
        // Lejos de la banda apenas cambia.
        val inp2 = sine(60.0, 0.1f, 48_000)
        val out2 = run(DspParams(bands = bands, limiter = false), inp2)
        assertEquals(0.0, db(rms(out2, 0) / rms(inp2, 0)), 0.3)
    }

    @Test fun gravesSubenLoBajoYNoLoAlto() {
        val p = DspParams(bassDb = 6f)
        val r = responseDb(p, doubleArrayOf(20.0, 5000.0))
        assertEquals(6.0, r[0], 0.3)
        assertEquals(0.0, r[1], 0.1)
        val t = responseDb(DspParams(trebleDb = -6f), doubleArrayOf(18000.0, 100.0))
        assertEquals(-6.0, t[0], 0.4)
        assertEquals(0.0, t[1], 0.1)
    }

    @Test fun limitadorNoDejaSaturar() {
        val out = run(DspParams(preampDb = 12f), sine(440.0, 1f, 9600))
        assertTrue(out.maxOf { abs(it) } <= 0.98f + 1e-4f)
    }

    @Test fun monoYBalance() {
        val buf = FloatArray(200) { if (it % 2 == 0) 0.5f else -0.5f }
        assertTrue(run(DspParams(mono = true), buf).all { abs(it) < 1e-6f })
        val left = run(DspParams(balance = -1f), sine(440.0, 0.3f, 2400))
        assertEquals(0.0, rms(left, 1, skip = 0), 1e-9)
        assertEquals(rms(sine(440.0, 0.3f, 2400), 0, skip = 0), rms(left, 0, skip = 0), 1e-6)
    }

    @Test fun crossfeedPasaGravesAlOtroOido() {
        for (level in 1..3) {
            val lowIn = sine(100.0, 0.3f, 48_000, right = false)
            val low = run(DspParams(crossfeed = level, limiter = false), lowIn)
            val hi = run(DspParams(crossfeed = level, limiter = false), sine(10_000.0, 0.3f, 48_000, right = false))
            assertTrue("nivel $level graves", rms(low, 1) > 0.25 * rms(low, 0))
            assertTrue("nivel $level agudos", rms(hi, 1) < 0.1 * rms(hi, 0))
        }
    }

    @Test fun picoDeLaCurva() {
        assertEquals(0.0, peakGainDb(DspParams()), 1e-9)
        val p = DspParams(bands = List(10) { if (it == 0) 9f else 0f })
        assertTrue(peakGainDb(p) in 8.0..9.2)
        assertTrue(peakGainDb(p.copy(preampDb = -9f)) < 0.3)
    }

    @Test fun ajustesDeFabrica() {
        assertTrue(BUILTIN_PRESETS.all { it.second.size == 10 && it.second.all { g -> abs(g) <= 12f } })
    }

    @Test fun cambiarParametrosAMitadNoDisparaNada() {
        val e = DspEngine(); e.configure(fs, 2)
        val buf = sine(500.0, 0.5f, 48_000)
        for (k in 0 until 100) {
            e.setParams(DspParams(bands = List(10) { (k % 25 - 12).toFloat() }))
            e.process(buf, 480) // procesa en trozos el principio, otra y otra vez
        }
        assertTrue(buf.all { it.isFinite() && abs(it) <= 1f })
    }

    // ---- Cambios mientras suena: sin chasquidos ----

    private fun tone(freqs: List<Double>, amp: Float, frames: Int, sr: Int) = FloatArray(frames * 2) { i ->
        val t = (i / 2).toDouble() / sr
        (freqs.sumOf { sin(2 * PI * it * t) } * amp / freqs.size).toFloat()
    }

    /** Máximo de |x[n] - 2x[n-1] + x[n-2]| en el canal izquierdo, desde [from]. */
    private fun d2(buf: FloatArray, from: Int): Double {
        var m = 0.0
        for (f in maxOf(from, 2) until buf.size / 2) {
            m = maxOf(m, abs(buf[f * 2] - 2.0 * buf[(f - 1) * 2] + buf[(f - 2) * 2]))
        }
        return m
    }

    /** Procesa en bloques de 1024 cambiando a [steps][k] en el bloque 8 + 4k; devuelve salto / régimen. */
    private fun clickRatio(sig: FloatArray, sr: Int, steps: List<DspParams>): Double {
        val e = DspEngine(); e.configure(sr, 2); e.setParams(steps[0])
        val out = sig.copyOf()
        val block = 1024
        val frames = out.size / 2
        var b = 0
        var k = 1
        while (b * block < frames) {
            if (k < steps.size && b == 8 + 4 * (k - 1)) { e.setParams(steps[k]); k++ }
            val n = minOf(block, frames - b * block)
            val chunk = out.copyOfRange(b * block * 2, (b * block + n) * 2)
            e.process(chunk, n)
            chunk.copyInto(out, b * block * 2)
            b++
        }
        // Régimen: el mayor de procesar todo con cada configuración fija.
        val steady = steps.maxOf { p ->
            val o = sig.copyOf(); val en = DspEngine(); en.configure(sr, 2); en.setParams(p)
            en.process(FloatArray(4096), 2048); en.process(o, frames); d2(o, 4096)
        }
        return d2(out, 4096) / steady
    }

    @Test fun moverUnaBandaPorCeroNoChasquea() {
        val sr = 44_100
        val sig = tone(listOf(100.0), 0.5f, 60_000, sr)
        fun band(g: Float) = DspParams(bassDb = 3f, bands = List(10) { if (it == 2) g else 0f })
        val r = clickRatio(sig, sr, listOf(band(0.5f), band(0f), band(-0.5f), band(0f), band(6f)))
        assertTrue("salto ×$r", r < 3.0)
    }

    @Test fun cambiarDePreajusteNoChasquea() {
        val sr = 44_100
        val sig = tone(listOf(55.0, 440.0, 3000.0), 0.6f, 60_000, sr)
        val presets = BUILTIN_PRESETS.toMap()
        fun pr(n: String) = DspParams(bands = presets.getValue(n))
        val r = clickRatio(sig, sr, listOf(pr("Graves"), pr("Rock"), pr("Agudos"), pr("Graves")))
        assertTrue("salto ×$r", r < 3.0)
    }

    @Test fun apagarYEncenderNoChasquea() {
        val sr = 44_100
        val sig = tone(listOf(55.0, 440.0), 0.5f, 60_000, sr)
        val g = DspParams(bands = BUILTIN_PRESETS.toMap().getValue("Graves"))
        val r = clickRatio(sig, sr, listOf(g, g.copy(eqOn = false), g, DspParams(), g))
        assertTrue("salto ×$r", r < 3.0)
    }

    // ---- Distorsión: el limitador no debe tocar lo que no sube ----

    /** THD+N de un canal: lo que queda tras quitar el seno de [f] Hz ajustado por mínimos cuadrados. */
    private fun thd(buf: FloatArray, chs: Int, c: Int, f: Double, sr: Int, from: Int): Double {
        val n = buf.size / chs - from
        var ss = 0.0; var sc = 0.0; var cc = 0.0; var ys = 0.0; var yc = 0.0; var y1 = 0.0; var s1 = 0.0; var c1 = 0.0
        for (k in 0 until n) {
            val w = 2 * PI * f * (from + k) / sr
            val sn = sin(w); val cs = kotlin.math.cos(w); val y = buf[(from + k) * chs + c].toDouble()
            ss += sn * sn; sc += sn * cs; cc += cs * cs; ys += y * sn; yc += y * cs; y1 += y; s1 += sn; c1 += cs
        }
        // Sistema 3×3 (seno, coseno, continua) por Cramer.
        val m = arrayOf(doubleArrayOf(ss, sc, s1), doubleArrayOf(sc, cc, c1), doubleArrayOf(s1, c1, n.toDouble()))
        val v = doubleArrayOf(ys, yc, y1)
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        val sol = DoubleArray(3) { j -> det(Array(3) { r -> DoubleArray(3) { q -> if (q == j) v[r] else m[r][q] } }) / d }
        var res = 0.0; var fund = 0.0
        for (k in 0 until n) {
            val w = 2 * PI * f * (from + k) / sr
            val fit = sol[0] * sin(w) + sol[1] * kotlin.math.cos(w)
            val y = buf[(from + k) * chs + c] - sol[2]
            res += (y - fit) * (y - fit); fund += fit * fit
        }
        return sqrt(res / fund)
    }

    private fun runBlocks(p: DspParams, sig: FloatArray, sr: Int): FloatArray {
        val e = DspEngine(); e.configure(sr, 2); e.setParams(p)
        val out = sig.copyOf(); val frames = out.size / 2
        var f0 = 0
        while (f0 < frames) {
            val n = minOf(1024, frames - f0)
            val chunk = out.copyOfRange(f0 * 2, (f0 + n) * 2); e.process(chunk, n); chunk.copyInto(out, f0 * 2); f0 += n
        }
        return out
    }

    @Test fun monoYBalanceNoDistorsionan() {
        val sr = 48_000
        val sig = tone(listOf(60.0), 0.995f, 48_000, sr)
        val mono = runBlocks(DspParams(mono = true), sig, sr)
        assertTrue("mono ${thd(mono, 2, 0, 60.0, sr, 8000)}", thd(mono, 2, 0, 60.0, sr, 8000) < 1e-5)
        val bal = runBlocks(DspParams(balance = 0.2f), sig, sr)
        assertTrue(thd(bal, 2, 0, 60.0, sr, 8000) < 1e-5 && thd(bal, 2, 1, 60.0, sr, 8000) < 1e-5)
    }

    @Test fun preajusteConPreampAutomaticaNoDistorsiona() {
        val sr = 48_000
        val g = DspParams(bands = BUILTIN_PRESETS.toMap().getValue("Graves"))
        val p = g.copy(preampDb = com.eddie.surco.audio.autoPreampDb(g))
        assertTrue(p.preampDb <= -6f)
        assertTrue(peakGainDb(p) <= 0.0)
        val sig = tone(listOf(40.0), 0.708f, 96_000, sr) // −3 dBFS
        val out = runBlocks(p, sig, sr)
        val t = thd(out, 2, 0, 40.0, sr, 16_000)
        assertTrue("THD $t", t < 1e-4)
        assertTrue(out.maxOf { abs(it) } <= 1f)
    }

    @Test fun conSubidaElLimitadorContieneSinRecortarCadaPico() {
        val sr = 48_000
        val p = DspParams(bands = BUILTIN_PRESETS.toMap().getValue("Graves")) // sin bajar la preamplificación
        val out = runBlocks(p, tone(listOf(40.0), 0.708f, 96_000, sr), sr)
        assertTrue(out.maxOf { abs(it) } <= 0.98f + 1e-4f)
        val t = thd(out, 2, 0, 40.0, sr, 24_000)
        assertTrue("THD $t", t < 0.01) // antes: 1,3 % (auditoría)
    }
}
