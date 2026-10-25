package com.eddie.surco

import com.eddie.surco.data.readReplayGain
import org.jaudiotagger.audio.AudioFileIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class ReplayGainTest {
    init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    private fun rg(name: String) = readReplayGain(AudioFileIO.read(File(javaClass.getResource("/rg/$name")!!.toURI())).tag)

    @Test fun id3v24() {
        val g = rg("v24.mp3")
        assertEquals(-6.48f, g.trackDb!!, 1e-3f)
        assertEquals(-7.10f, g.albumDb!!, 1e-3f)
    }

    @Test fun id3v23Positiva() {
        val g = rg("v23.mp3")
        assertEquals(2.0f, g.trackDb!!, 1e-3f)
        assertNull(g.albumDb)
    }

    @Test fun flacVorbis() {
        val g = rg("vorbis.flac")
        assertEquals(-3.21f, g.trackDb!!, 1e-3f)
        assertEquals(-4.5f, g.albumDb!!, 1e-3f)
    }

    @Test fun sinEtiquetas() {
        val g = rg("sin.mp3")
        assertNull(g.trackDb); assertNull(g.albumDb)
    }

    @Test fun opusComentariosYR128() {
        val c = javaClass.getResourceAsStream("/rg/r128.opus")!!.use { com.eddie.surco.data.readOpusTags(it) }!!
        assertEquals("Canción Opus", c["TITLE"]!!.first())
        assertEquals("Banda Ñ", c["ARTIST"]!!.first())
        assertEquals("calma", c["MOOD"]!!.first())
        val g = com.eddie.surco.data.replayGainFromComments(c)
        assertEquals(-5f, g.trackDb!!, 1e-3f)
        assertEquals(0f, g.albumDb!!, 1e-3f)
        // Un MP3 no es Ogg Opus.
        assertNull(javaClass.getResourceAsStream("/rg/v24.mp3")!!.use { com.eddie.surco.data.readOpusTags(it) })
    }

    @Test fun r128Conversion() {
        assertEquals(5f, com.eddie.surco.data.r128ToDb("0")!!, 1e-4f)
        assertNull(com.eddie.surco.data.r128ToDb("x"))
    }
}
