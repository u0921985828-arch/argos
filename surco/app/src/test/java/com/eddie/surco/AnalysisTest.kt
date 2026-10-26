package com.eddie.surco

import com.eddie.surco.data.MusicKey
import com.eddie.surco.data.bpmText
import com.eddie.surco.data.estimateBpm
import com.eddie.surco.data.estimateKey
import com.eddie.surco.data.fromCamelot
import com.eddie.surco.data.harmonic
import com.eddie.surco.data.parseKey
import com.eddie.surco.data.tempoMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class AnalysisTest {
    private val sr = 11025

    /** Batería sintética: bombo en cada pulso, caja en 2 y 4, charles en corcheas, y ruido. */
    private fun beat(bpm: Double, seconds: Int = 30): FloatArray {
        val rnd = Random(7)
        val x = FloatArray(sr * seconds) { (rnd.nextFloat() - 0.5f) * 0.02f }
        val period = 60.0 / bpm
        var t = 0.0; var k = 0
        while (t < seconds) {
            val o = (t * sr).toInt()
            for (i in 0 until (0.15 * sr).toInt()) {
                if (o + i >= x.size) break
                val tt = i.toDouble() / sr
                x[o + i] += (0.8 * sin(2 * PI * 60 * tt) * exp(-tt * 25)).toFloat() // bombo
                if (k % 2 == 1) x[o + i] += ((rnd.nextFloat() - 0.5f) * 0.6 * exp(-tt * 30)).toFloat() // caja
            }
            val h = ((t + period / 2) * sr).toInt() // charles a contratiempo, flojo
            for (i in 0 until (0.03 * sr).toInt()) if (h + i < x.size) x[h + i] += ((rnd.nextFloat() - 0.5f) * 0.15 * exp(-i / (0.005 * sr))).toFloat()
            t += period; k++
        }
        return x
    }

    @Test fun bpmDeBaterias() {
        for (bpm in listOf(90.0, 100.0, 120.0, 128.0, 140.0, 174.0)) {
            val got = estimateBpm(beat(bpm), sr)!!
            assertTrue("$bpm → $got", abs(got - bpm) <= 1.0)
        }
        // Menos de 8 s no da tempo; el silencio tampoco.
        assertNull(estimateBpm(beat(120.0, 5), sr))
        assertNull(estimateBpm(FloatArray(sr * 20), sr))
        assertEquals("128", bpmText(128f)); assertEquals("128,5", bpmText(128.5f))
    }

    /** Acordes con armónicos, 2 s cada uno. */
    private fun chords(vararg cs: List<Int>): FloatArray {
        val len = 2 * sr
        val x = FloatArray(len * cs.size * 3)
        repeat(3) { rep ->
            cs.forEachIndexed { ci, notes ->
                val o = (rep * cs.size + ci) * len
                for (i in 0 until len) {
                    val tt = i.toDouble() / sr
                    var v = 0.0
                    for (m in notes) {
                        val f = 220.0 * 2.0.pow((m - 9) / 12.0) // MIDI relativo: 9 = La3 (220 Hz)
                        for (h in 1..4) v += sin(2 * PI * f * h * tt) / h
                    }
                    x[o + i] = (v * 0.08 * exp(-tt * 0.6)).toFloat()
                }
            }
        }
        return x
    }

    @Test fun tonalidadDeAcordes() {
        // Do mayor: C – F – G – C.
        val doMayor = estimateKey(chords(listOf(0, 4, 7), listOf(5, 9, 12), listOf(7, 11, 14), listOf(0, 4, 7)), sr)!!
        assertEquals(MusicKey(0, false), doMayor.key)
        // La menor armónica: Am – Dm – E – Am (el Sol♯ la delata).
        val laMenor = estimateKey(chords(listOf(9, 12, 16), listOf(2, 5, 9), listOf(4, 8, 11), listOf(9, 12, 16)), sr)!!
        assertEquals(MusicKey(9, true), laMenor.key)
        // Re mayor: D – G – A – D.
        val reMayor = estimateKey(chords(listOf(2, 6, 9), listOf(7, 11, 14), listOf(9, 13, 16), listOf(2, 6, 9)), sr)!!
        assertEquals(MusicKey(2, false), reMayor.key)
    }

    @Test fun camelotYEtiquetas() {
        assertEquals("8A", MusicKey(9, true).code)   // La menor
        assertEquals("8B", MusicKey(0, false).code)  // Do mayor
        assertEquals("11B", MusicKey(9, false).code) // La mayor
        assertEquals("1A", MusicKey(8, true).code)   // La♭ menor
        assertEquals("La menor · 8A", MusicKey(9, true).label)
        for (n in 1..12) for (m in listOf(true, false)) assertEquals("$n${if (m) 'A' else 'B'}", fromCamelot(n, m).code)
        val esperadas = mapOf(
            "Am" to MusicKey(9, true), "A minor" to MusicKey(9, true), "C#m" to MusicKey(1, true), "Dbmaj" to MusicKey(1, false),
            "F♯ min" to MusicKey(6, true), "Bb" to MusicKey(10, false), "Ab maj" to MusicKey(8, false), "Ebm" to MusicKey(3, true),
            "8A" to MusicKey(9, true), "08B" to MusicKey(0, false), "12a" to MusicKey(1, true), "1m" to MusicKey(9, true), "1d" to MusicKey(0, false),
            "La menor" to MusicKey(9, true), "Mi♭ mayor" to MusicKey(3, false), "Sol#m" to MusicKey(8, true), "Do" to MusicKey(0, false),
            "E moll" to MusicKey(4, true),
        )
        for ((txt, k) in esperadas) assertEquals(txt, k, parseKey(txt))
        for (malo in listOf("", "H", "13A", "x", "Am7sus")) assertNull(malo, parseKey(malo))
    }

    @Test fun mezclaArmonica() {
        val am = MusicKey(9, true)
        assertTrue(harmonic(am, fromCamelot(8, true)))
        assertTrue(harmonic(am, fromCamelot(9, true)))
        assertTrue(harmonic(am, fromCamelot(7, true)))
        assertTrue(harmonic(am, fromCamelot(8, false))) // su relativa, Do mayor
        assertTrue(harmonic(fromCamelot(12, true), fromCamelot(1, true))) // la rueda da la vuelta
        assertFalse(harmonic(am, fromCamelot(10, true)))
        assertFalse(harmonic(am, fromCamelot(9, false)))
        assertTrue(tempoMatch(128f, 124f)); assertTrue(tempoMatch(128f, 64f)); assertTrue(tempoMatch(87f, 174f))
        assertFalse(tempoMatch(128f, 110f)); assertFalse(tempoMatch(0f, 120f))
    }
}
