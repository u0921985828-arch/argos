package com.eddie.surco

import com.eddie.surco.audio.DspEngine
import com.eddie.surco.audio.DspParams
import com.eddie.surco.audio.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class PcmTest {
    private fun pack24(v: IntArray, be: Boolean = false): ByteBuffer {
        val b = ByteBuffer.allocate(v.size * 3)
        for (x in v) {
            val bytes = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte())
            if (be) { b.put(bytes[2]); b.put(bytes[1]); b.put(bytes[0]) } else b.put(bytes)
        }
        b.flip(); return b
    }

    @Test fun veinticuatroBitsExactos() {
        val r = Random(7)
        val v = IntArray(4096) { r.nextInt(-8388608, 8388608) } + intArrayOf(-8388608, 8388607, 1, -1, 0)
        for (be in listOf(false, true)) {
            val out = FloatArray(v.size)
            Pcm.toFloat(pack24(v, be), if (be) Pcm.PCM_24_BE else Pcm.PCM_24, out, v.size)
            for (i in v.indices) assertEquals("muestra $i", v[i], (out[i] * 8388608.0).toInt())
        }
    }

    @Test fun elBitMasBajoDe24SobreviveAlMotorNeutro() {
        // ±1 LSB de 24 bits (−138 dBFS): en 16 bits sería silencio.
        val v = IntArray(2000) { if (it % 2 == 0) 1 else -1 }
        val f = FloatArray(v.size)
        Pcm.toFloat(pack24(v), Pcm.PCM_24, f, v.size)
        val e = DspEngine(); e.configure(96_000, 2); e.setParams(DspParams())
        e.process(f, v.size / 2)
        assertTrue(f.all { it != 0f })
        for (i in v.indices) assertEquals(v[i], (f[i] * 8388608.0).toInt())
        assertEquals(0, (f[0] * 32768.0).toInt()) // a 16 bits se habría perdido
    }

    @Test fun dieciseisYTreintaYDosYFloat() {
        val s = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(-32768).putShort(16384).putShort(1).putShort(0)
        s.flip()
        val o = FloatArray(4); Pcm.toFloat(s, Pcm.PCM_16, o, 4)
        assertEquals(listOf(-1f, 0.5f, 1f / 32768, 0f), o.toList())
        val i32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(1 shl 30); i32.flip()
        val o2 = FloatArray(2); Pcm.toFloat(i32, Pcm.PCM_32, o2, 2)
        assertEquals(listOf(-1f, 0.5f), o2.toList())
        val fl = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putFloat(0.123456789f).putFloat(-1e-9f); fl.flip()
        val o3 = FloatArray(2); Pcm.toFloat(fl, Pcm.FLOAT, o3, 2)
        assertEquals(listOf(0.123456789f, -1e-9f), o3.toList())
    }

    @Test fun noMueveLaPosicion() {
        val b = pack24(intArrayOf(5, 6, 7)); b.position(3)
        val o = FloatArray(2); Pcm.toFloat(b, Pcm.PCM_24, o, 2)
        assertEquals(3, b.position())
        assertEquals(6, (o[0] * 8388608).toInt())
    }

    @Test fun altaResolucion() {
        assertTrue(Pcm.isHighRes(Pcm.PCM_24) && Pcm.isHighRes(Pcm.FLOAT) && Pcm.isHighRes(Pcm.PCM_32))
        assertTrue(!Pcm.isHighRes(Pcm.PCM_16))
    }

    @Test fun neutroEnFloatEsIdentico() {
        val r = Random(3)
        val f = FloatArray(9600) { r.nextFloat() * 2 - 1 }
        val g = f.copyOf()
        val e = DspEngine(); e.configure(192_000, 2); e.setParams(DspParams()); e.process(g, g.size / 2)
        assertTrue(f.contentEquals(g))
    }

    @Test fun wavEnComaFlotanteExtensible() {
        fun head(n: String) = javaClass.getResourceAsStream("/rg/$n")!!.use { it.readNBytes(256) }
        assertTrue(com.eddie.surco.audio.isFloatExtensibleWav(head("float-ext.wav")))
        assertTrue(!com.eddie.surco.audio.isFloatExtensibleWav(head("int24-ext.wav")))
        assertTrue(com.eddie.surco.audio.isFloatExtensibleWav(head("float-mono.wav"))) // ffmpeg también usa la extensible en mono
        assertTrue(!com.eddie.surco.audio.isFloatExtensibleWav(head("sin.mp3")))
    }
}
