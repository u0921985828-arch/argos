package com.eddie.surco

import com.eddie.surco.data.TagKey
import com.eddie.surco.data.changedValues
import com.eddie.surco.data.invalidKeys
import com.eddie.surco.data.isValidValue
import com.eddie.surco.data.isWritableFormat
import com.eddie.surco.data.normalizeValue
import com.eddie.surco.data.readTagValues
import com.eddie.surco.data.splitValues
import com.eddie.surco.data.writeTagValues
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.logging.Level
import java.util.logging.Logger

class TagEditTest {
    init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    /** Copia de un archivo de prueba en un directorio temporal: se escribe sobre la copia. */
    private fun copy(name: String): File {
        val src = File(javaClass.getResource("/rg/$name")!!.toURI())
        val dir = Files.createTempDirectory("surco-tags").toFile().apply { deleteOnExit() }
        return src.copyTo(File(dir, name)).apply { deleteOnExit() }
    }

    @Test fun mapeoAFieldKey() {
        assertEquals(FieldKey.TITLE, TagKey.TITLE.fieldKey)
        assertEquals(FieldKey.ALBUM_ARTIST, TagKey.ALBUM_ARTIST.fieldKey)
        assertEquals(FieldKey.DISC_NO, TagKey.DISC.fieldKey)
        assertEquals(FieldKey.TRACK, TagKey.TRACK.fieldKey)
        assertEquals(FieldKey.KEY, TagKey.KEY.fieldKey)
        assertEquals(FieldKey.MOOD, TagKey.MOOD.fieldKey)
        assertEquals(13, TagKey.entries.size)
        assertEquals(setOf(TagKey.YEAR, TagKey.TRACK, TagKey.DISC, TagKey.BPM), TagKey.entries.filter { it.numeric }.toSet())
    }

    @Test fun numerosValidados() {
        assertTrue(isValidValue(TagKey.YEAR, "1999"))
        assertTrue(isValidValue(TagKey.YEAR, ""))
        assertFalse(isValidValue(TagKey.YEAR, "99"))
        assertFalse(isValidValue(TagKey.YEAR, "19a9"))
        assertFalse(isValidValue(TagKey.YEAR, "0999"))
        assertTrue(isValidValue(TagKey.TRACK, "7"))
        assertFalse(isValidValue(TagKey.TRACK, "0"))
        assertFalse(isValidValue(TagKey.TRACK, "1000"))
        assertFalse(isValidValue(TagKey.DISC, "-1"))
        assertFalse(isValidValue(TagKey.DISC, "1/2"))
        assertTrue(isValidValue(TagKey.BPM, "120"))
        assertTrue(isValidValue(TagKey.BPM, "120,5"))
        assertTrue(isValidValue(TagKey.BPM, "128.25"))
        assertFalse(isValidValue(TagKey.BPM, "0"))
        assertFalse(isValidValue(TagKey.BPM, "120.125"))
        assertFalse(isValidValue(TagKey.BPM, "rápido"))
        assertTrue(isValidValue(TagKey.TITLE, "lo que sea / 12"))
    }

    @Test fun normalizado() {
        assertEquals("120.5", normalizeValue(TagKey.BPM, " 120,5 "))
        assertEquals("0.5", normalizeValue(TagKey.BPM, "0,5"))
        assertEquals("7", normalizeValue(TagKey.TRACK, "007"))
        assertEquals("Hola", normalizeValue(TagKey.TITLE, "  Hola "))
        // Lo que no vale se deja tal cual (la pantalla no deja guardarlo).
        assertEquals("abc", normalizeValue(TagKey.YEAR, "abc"))
    }

    @Test fun soloLoCambiado() {
        val before = mapOf(TagKey.TITLE to "Uno", TagKey.YEAR to "2001-05-03", TagKey.GENRE to "Rock", TagKey.BPM to "")
        val after = before + mapOf(TagKey.TITLE to "Uno ", TagKey.BPM to "128,0", TagKey.ARTIST to "")
        // «Uno » es «Uno»; el año raro sin tocar no se reescribe; un campo vacío que sigue vacío, tampoco.
        assertEquals(mapOf(TagKey.BPM to "128.0"), changedValues(before, after))
        assertEquals(mapOf(TagKey.TITLE to ""), changedValues(before, before + (TagKey.TITLE to "  ")))
        // El año leído no cuenta como inválido mientras no se toque; tocado, sí.
        assertTrue(invalidKeys(before, after).isEmpty())
        assertEquals(setOf(TagKey.YEAR), invalidKeys(before, before + (TagKey.YEAR to "2001-05")))
    }

    @Test fun variosValores() {
        assertEquals(listOf("Ana", "Luis"), splitValues(" Ana ;Luis;; Ana"))
        assertEquals(emptyList<String>(), splitValues(" ; "))
    }

    @Test fun formatos() {
        assertTrue(isWritableFormat("/m/a.MP3"))
        assertTrue(isWritableFormat("/m/a.flac"))
        assertTrue(isWritableFormat("/m/a.m4a"))
        assertTrue(isWritableFormat("/m/a.wav"))
        assertFalse(isWritableFormat("/m/a.opus"))
        assertFalse(isWritableFormat("/m/sin-extension"))
        // Opus no se abre: la pantalla no ofrece guardar.
        assertNull(readTagValues(copy("r128.opus")))
    }

    private val edits = mapOf(
        TagKey.TITLE to "Título nuevo", TagKey.ARTIST to "Ana; Luis", TagKey.ALBUM to "Disco",
        TagKey.ALBUM_ARTIST to "Varios", TagKey.GENRE to "Jazz", TagKey.YEAR to "1999",
        TagKey.TRACK to "7", TagKey.DISC to "2", TagKey.COMPOSER to "Compositora",
        TagKey.BPM to "128", TagKey.KEY to "Am", TagKey.MOOD to "Tranquila", TagKey.COMMENT to "Línea uno",
    )

    private fun roundTrip(name: String) {
        val f = copy(name)
        val before = readTagValues(f)
        assertNotNull("$name se abre", before)
        writeTagValues(f, edits)
        val after = readTagValues(f)!!
        for ((k, v) in edits) assertEquals("$name: $k", v, after[k])
        // Sigue siendo audio que jaudiotagger abre, con la misma duración.
        assertEquals(AudioFileIO.read(copy(name)).audioHeader.trackLength, AudioFileIO.read(f).audioHeader.trackLength)
        // Borrar: un campo vacío quita la etiqueta.
        writeTagValues(f, mapOf(TagKey.COMMENT to "", TagKey.MOOD to ""))
        val gone = readTagValues(f)!!
        assertEquals("", gone[TagKey.COMMENT])
        assertEquals("", gone[TagKey.MOOD])
        assertEquals("Título nuevo", gone[TagKey.TITLE])
    }

    @Test fun mp3SinEtiqueta() = roundTrip("sin.mp3")
    @Test fun mp3Id3v23() = roundTrip("v23.mp3")
    @Test fun mp3Id3v24() = roundTrip("v24.mp3")
    @Test fun flac() = roundTrip("vorbis.flac")
    @Test fun wav() = roundTrip("int24-ext.wav")

    @Test fun bpmConDecimalesEnMp3() {
        val f = copy("v24.mp3")
        writeTagValues(f, mapOf(TagKey.BPM to "120.5"))
        assertEquals("120.5", readTagValues(f)!![TagKey.BPM])
    }

    @Test fun tonalidadCamelotEnMp3() {
        val f = copy("v23.mp3")
        writeTagValues(f, mapOf(TagKey.KEY to "8A"))
        assertEquals("8A", readTagValues(f)!![TagKey.KEY])
    }
}
