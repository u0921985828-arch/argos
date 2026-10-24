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
}
