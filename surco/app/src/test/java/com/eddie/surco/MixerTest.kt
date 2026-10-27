package com.eddie.surco

import com.eddie.surco.audio.MixerOption
import com.eddie.surco.audio.PcmEncoding.PCM_16BIT
import com.eddie.surco.audio.PcmEncoding.PCM_24BIT_PACKED
import com.eddie.surco.audio.PcmEncoding.PCM_32BIT
import com.eddie.surco.audio.PcmEncoding.PCM_FLOAT
import com.eddie.surco.audio.pickMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixerTest {
    // Un DAC USB típico: 44,1/48/96 kHz, estéreo, bit-perfect en 16 y 24 bits.
    private val dac = listOf(44100, 48000, 96000).flatMap { sr ->
        listOf(
            MixerOption(sr, PCM_16BIT, 2, true), MixerOption(sr, PCM_24BIT_PACKED, 2, true),
            MixerOption(sr, PCM_16BIT, 2, false), MixerOption(sr, PCM_24BIT_PACKED, 2, false), MixerOption(sr, PCM_FLOAT, 2, false),
        )
    }

    @Test fun cdEnBitPerfect() {
        val c = pickMixer(dac, 44100, PCM_16BIT, 2)!!
        assertEquals(true, c.bitPerfect); assertEquals(44100, c.option.sampleRate); assertEquals(PCM_16BIT, c.option.encoding)
    }

    @Test fun altaResolucionSinRemuestrearSiNoHayBitPerfectEnFloat() {
        // Surco entrega lo de 24 bits en coma flotante: no casa con el bit-perfect de 24 enteros;
        // se pide el mezclador normal a 96 kHz con al menos 24 bits (sin remuestrear).
        val c = pickMixer(dac, 96000, PCM_FLOAT, 2)!!
        assertEquals(false, c.bitPerfect); assertEquals(96000, c.option.sampleRate)
        assertEquals(24, com.eddie.surco.audio.PcmEncoding.bits(c.option.encoding))
    }

    @Test fun floatBitPerfectSiElDacLoDa() {
        val c = pickMixer(dac + MixerOption(96000, PCM_FLOAT, 2, true), 96000, PCM_FLOAT, 2)!!
        assertEquals(true, c.bitPerfect); assertEquals(PCM_FLOAT, c.option.encoding)
    }

    @Test fun frecuenciaQueElDacNoTiene() {
        assertNull(pickMixer(dac, 192000, PCM_FLOAT, 2))
        assertNull(pickMixer(dac, 0, PCM_16BIT, 2))
    }

    @Test fun monoNoSeMezclaConEstereo() {
        assertNull(pickMixer(dac, 44100, PCM_16BIT, 1))
    }

    @Test fun normalConMenosBitsNoSirve() {
        val solo16 = listOf(MixerOption(96000, PCM_16BIT, 2, false), MixerOption(96000, PCM_32BIT, 2, false))
        assertEquals(PCM_32BIT, pickMixer(solo16, 96000, PCM_FLOAT, 2)!!.option.encoding)
    }
}
