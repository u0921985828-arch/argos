package com.eddie.surco

import com.eddie.surco.audio.DspParams
import com.eddie.surco.audio.EQ_FREQS
import com.eddie.surco.audio.SoundProfile
import com.eddie.surco.audio.outputKey
import com.eddie.surco.audio.switchOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEqTest {
    private fun prof(db: Float, name: String) = SoundProfile(DspParams(bands = List(EQ_FREQS.size) { db }), name)

    @Test fun cadaSalidaRecuerdaLaSuya() {
        val speaker = outputKey("SPEAKER", ""); val bt = outputKey("BLUETOOTH", "WH-1000XM4")
        // En el altavoz con «Graves» se conectan los cascos por primera vez: siguen con lo de ahora.
        var (profiles, now) = switchOutput(emptyMap(), prof(3f, "Graves"), speaker, bt)
        assertEquals(prof(3f, "Graves"), now)
        // Con los cascos se cambia a «Voz» y se desconectan: vuelve «Graves» al altavoz.
        val r1 = switchOutput(profiles, prof(-2f, "Voz"), bt, speaker); profiles = r1.first; now = r1.second
        assertEquals(prof(3f, "Graves"), now)
        // Se vuelven a conectar: «Voz», lo suyo.
        val r2 = switchOutput(profiles, now, speaker, bt)
        assertEquals(prof(-2f, "Voz"), r2.second)
    }

    @Test fun dosCascosBluetoothSonDistintos() {
        assertEquals(false, outputKey("BLUETOOTH", "Buds") == outputKey("BLUETOOTH", "WH-1000XM4"))
        assertEquals("wired", outputKey("WIRED", "USB-C to 3.5mm"))
    }

    @Test fun sinSalidaAnteriorNoSeGuardaNada() {
        val (profiles, now) = switchOutput(emptyMap(), prof(1f, "Rock"), null, "speaker")
        assertEquals(prof(1f, "Rock"), now)
        assertEquals(setOf("speaker"), profiles.keys)
    }
}
