package com.eddie.surco

import com.eddie.surco.audio.DspParams
import com.eddie.surco.audio.EQ_FREQS
import com.eddie.surco.audio.OutputState
import com.eddie.surco.audio.SoundProfile
import com.eddie.surco.audio.arrive
import com.eddie.surco.audio.audibleChange
import com.eddie.surco.audio.enablePerOutput
import com.eddie.surco.audio.forgetPreset
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

    @Test fun sinInterruptorSoloSeApuntaLaSalida() {
        val s = OutputState(mapOf("bt:B" to prof(-2f, "Voz")), "speaker", prof(3f, "Graves"))
        val off = arrive(s, "bt:B", perOutput = false)
        assertEquals(prof(3f, "Graves"), off.sound); assertEquals("bt:B", off.key); assertEquals(s.profiles, off.profiles)
        assertEquals(prof(-2f, "Voz"), arrive(s, "bt:B", perOutput = true).sound)
    }

    @Test fun alEncenderloVuelveLoDeEstaSalida() {
        // Con el interruptor apagado se tocó «Graves» con los cascos puestos: al encenderlo, vuelve «Voz», la suya.
        val s = OutputState(mapOf("bt:B" to prof(-2f, "Voz")), "bt:B", prof(3f, "Graves"))
        assertEquals(prof(-2f, "Voz"), enablePerOutput(s).sound)
        // Una salida sin nada guardado se queda con lo de ahora.
        val nueva = enablePerOutput(s.copy(key = "wired"))
        assertEquals(prof(3f, "Graves"), nueva.profiles["wired"])
    }

    @Test fun avisoSoloSiSeOye() {
        val plano = DspParams(eqOn = false, bands = List(EQ_FREQS.size) { 4f })
        assertEquals(false, audibleChange(plano, DspParams(eqOn = false)))
        assertEquals(true, audibleChange(DspParams(), prof(3f, "G").dsp))
    }

    @Test fun borrarUnPreajusteLoOlvidaEnLasSalidas() {
        val out = forgetPreset(mapOf("a" to prof(1f, "Mío"), "b" to prof(2f, "Rock")), "Mío", "Personalizado")
        assertEquals("Personalizado", out.getValue("a").preset); assertEquals(prof(1f, "Mío").dsp, out.getValue("a").dsp)
        assertEquals("Rock", out.getValue("b").preset)
    }
}
