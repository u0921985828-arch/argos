package com.eddie.surco

import com.eddie.surco.data.LoudnessMeter
import com.eddie.surco.data.MusicKey
import com.eddie.surco.data.RadioSong
import com.eddie.surco.data.WAVE_FLOOR
import com.eddie.surco.data.radio
import com.eddie.surco.data.replayGainFromLufs
import com.eddie.surco.data.spreadShuffle
import com.eddie.surco.data.waveBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Radio, aleatorio que reparte artistas, sonoridad (EBU R128) y forma de onda. */
class RadioTest {
    private fun adjacent(keys: List<String>, order: IntArray) =
        (1 until order.size).count { keys[order[it]].isNotEmpty() && keys[order[it]] == keys[order[it - 1]] }

    @Test fun aleatorioSinArtistasSeguidosCuandoSePuede() {
        val rnd = Random(1)
        repeat(2000) { run ->
            val artists = 1 + rnd.nextInt(8)
            val n = 1 + rnd.nextInt(60)
            val keys = List(n) { "A${rnd.nextInt(artists)}" }
            val first = if (run % 2 == 0) -1 else rnd.nextInt(n)
            val order = spreadShuffle(keys, first, Random(run))
            assertEquals("es una permutación", (0 until n).toList(), order.sorted())
            if (first >= 0) assertEquals(first, order[0])
            // Se puede sin choques si nadie pasa de la mitad; con la primera fijada, los demás
            // artistas solo tienen los huecos pares (la mitad redondeada hacia abajo).
            val counts = keys.groupingBy { it }.eachCount()
            val possible = counts.all { (k, c) -> c <= if (first < 0 || k == keys[first]) (n + 1) / 2 else n / 2 }
            if (possible) assertEquals("seguidos con $keys desde $first", 0, adjacent(keys, order))
        }
    }

    @Test fun conUnArtistaQueLoLlenaLoRepartePorIgual() {
        val keys = List(12) { "A" } + List(4) { "B" }
        val order = spreadShuffle(keys, -1, Random(3))
        // 12 de A y 4 de B: como mucho 13 huecos... lo mejor posible deja 12 − 5 = 7 pares de A seguidos.
        assertEquals(7, adjacent(keys, order))
    }

    @Test fun sinArtistaNoChoca() {
        val keys = List(10) { "" }
        val order = spreadShuffle(keys, 4, Random(5))
        assertEquals((0 until 10).toList(), order.sorted())
        assertEquals(4, order[0])
    }

    @Test fun mezclaDeVerdad() {
        // Con artistas distintos, el orden cambia con la semilla y no es el de entrada.
        val keys = List(30) { "A$it" }
        val a = spreadShuffle(keys, -1, Random(1)).toList()
        val b = spreadShuffle(keys, -1, Random(2)).toList()
        assertTrue(a != b && a != (0 until 30).toList())
    }

    @Test fun diezMilCancionesRapido() {
        val rnd = Random(9)
        val keys = List(10_000) { if (it % 7 == 0) "" else "A${rnd.nextInt(2500)}" }
        val t = System.nanoTime()
        val order = spreadShuffle(keys, 0, rnd)
        val ms = (System.nanoTime() - t) / 1e6
        assertEquals(10_000, order.toSet().size)
        assertEquals(0, adjacent(keys, order))
        assertTrue("tardó $ms ms", ms < 3000)
    }

    private fun song(id: Long, artist: String, genre: String = "", year: Int = 0, bpm: Float? = null, key: MusicKey? = null, dir: String = "") =
        RadioSong(id, artist, genre, year, bpm, key, dir)

    @Test fun radioPareceLaSemilla() {
        val am = MusicKey(9, true)
        val rnd = Random(4)
        val lib = ArrayList<RadioSong>()
        var id = 1L
        repeat(60) { lib += song(id++, "Rock${it % 15}", "Rock", 1990 + it % 10, 118f + it % 5, am) }
        repeat(200) { lib += song(id++, "Pop${it % 40}", "Pop", 2015 + it % 8, 95f, MusicKey(1, false)) }
        repeat(100) { lib += song(id++, "Jazz${it % 20}", "Jazz", 1960, 160f) }
        repeat(20) { lib += song(id++, "Seed", "Rock", 1994, 120f, am) }
        val seed = lib.last()
        val r = radio(seed, lib, rnd)
        val byId = lib.associateBy { it.id }
        assertEquals(seed.id, r.first())
        assertEquals(50, r.size)
        assertEquals("sin repetidas", r.size, r.toSet().size)
        val songs = r.map { byId.getValue(it) }
        assertTrue("casi todo rock: ${songs.count { it.genre == "Rock" }}", songs.count { it.genre == "Rock" } >= 48)
        assertTrue("como mucho 4 del artista", songs.count { it.artist == "Seed" } <= 4)
        assertTrue(songs.groupingBy { it.artist }.eachCount().values.all { it <= 4 })
        assertEquals(0, adjacent(songs.map { it.artist }, IntArray(songs.size) { it }))
        // Otra radio de la misma canción no es la misma.
        assertTrue(radio(seed, lib, Random(99)) != r)
    }

    @Test fun radioSinEtiquetasSuenaIgual() {
        val lib = List(30) { song(it.toLong(), "") }
        val r = radio(lib[0], lib, Random(1), size = 20)
        assertEquals(20, r.size)
        assertEquals(0L, r.first())
        assertEquals(20, r.toSet().size)
    }

    @Test fun radioDeUnaSolaCancion() {
        val s = song(7, "X", "Rock")
        assertEquals(listOf(7L), radio(s, listOf(s), Random(1)))
    }

    // ---------- Sonoridad (EBU Tech 3341) ----------

    private fun sine(meter: LoudnessMeter, rate: Int, channels: Int, dbfs: Double, seconds: Double, hz: Double = 1000.0) {
        val a = 10.0.pow(dbfs / 20)
        val n = (rate * seconds).toInt()
        for (i in 0 until n) { val v = a * sin(2 * PI * hz * i / rate); repeat(channels) { meter.push(v) } }
    }

    @Test fun senoA23EnEstereoDa23Lufs() {
        for (rate in listOf(44100, 48000, 96000)) {
            val m = LoudnessMeter(rate, 2)
            sine(m, rate, 2, -23.0, 20.0)
            assertEquals("a $rate Hz", -23.0, m.integrated()!!, 0.1)
        }
    }

    @Test fun senoA33Da33() {
        val m = LoudnessMeter(48000, 2)
        sine(m, 48000, 2, -33.0, 20.0)
        assertEquals(-33.0, m.integrated()!!, 0.1)
    }

    @Test fun lasPuertasIgnoranElSilencioYLoMuySuave() {
        // Tech 3341, caso 3: −36, −23 y −36 dBFS durante 10/60/10 s → −23 LUFS (la puerta relativa).
        val m = LoudnessMeter(48000, 2)
        sine(m, 48000, 2, -36.0, 10.0); sine(m, 48000, 2, -23.0, 60.0); sine(m, 48000, 2, -36.0, 10.0)
        assertEquals(-23.0, m.integrated()!!, 0.1)
        // Silencio delante y detrás: no cuenta (puerta absoluta).
        val s = LoudnessMeter(48000, 2)
        repeat(48000 * 2 * 5) { s.push(0.0) }
        sine(s, 48000, 2, -23.0, 20.0)
        repeat(48000 * 2 * 5) { s.push(0.0) }
        assertEquals(-23.0, s.integrated()!!, 0.1)
    }

    @Test fun silencioNoDaNada() {
        val m = LoudnessMeter(44100, 2)
        repeat(44100 * 2 * 3) { m.push(0.0) }
        assertNull(m.integrated())
        assertNull(LoudnessMeter(44100, 2).integrated())
    }

    @Test fun monoSuenaTresDecibeliosMenosQueEstereo() {
        // BS.1770 suma la energía de los canales: lo mismo en un solo canal son −3 dB.
        val m = LoudnessMeter(44100, 1)
        sine(m, 44100, 1, -23.0, 10.0)
        assertEquals(-26.0, m.integrated()!!, 0.1)
    }

    @Test fun graveYAgudoPesanDistinto() {
        // El filtro K: a 100 Hz casi no cambia y a 10 kHz sube unos 4 dB respecto a 1 kHz.
        fun at(hz: Double) = LoudnessMeter(48000, 2).also { sine(it, 48000, 2, -23.0, 10.0, hz) }.integrated()!!
        assertEquals(-23.0, at(1000.0), 0.1)
        assertTrue(at(10000.0) - at(1000.0) in 3.0..5.0)
        assertTrue(at(30.0) < at(1000.0) - 1.0)
    }

    @Test fun ganancia() {
        assertEquals(5f, replayGainFromLufs(-23.0), 0.001f)
        assertEquals(-8f, replayGainFromLufs(-10.0), 0.001f)
        assertEquals(20f, replayGainFromLufs(-80.0), 0.001f)
    }

    // ---------- Forma de onda ----------

    @Test fun ondaRelativaAlMasFuerte() {
        val b = waveBars(floatArrayOf(0.5f, 0.05f, 0.0158f, 0f, Float.NaN))
        assertEquals(1f, b[0], 1e-4f)
        assertEquals(WAVE_FLOOR + (1 - WAVE_FLOOR) * (10f / 30f), b[1], 1e-3f) // −20 dB
        assertEquals(WAVE_FLOOR, b[2], 1e-2f) // −30 dB
        assertEquals(WAVE_FLOOR, b[3], 1e-6f)
        assertEquals(b[3], b[4], 1e-6f) // NaN toma el vecino
        assertTrue(waveBars(FloatArray(8)).all { it == WAVE_FLOOR })
        assertEquals(0, waveBars(FloatArray(0)).size)
    }
}
