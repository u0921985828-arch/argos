package com.eddie.surco

import com.eddie.surco.data.AlbumRef
import com.eddie.surco.data.contrast
import com.eddie.surco.data.crossfadeGain
import com.eddie.surco.data.fadesBetween
import com.eddie.surco.data.indexLetter
import com.eddie.surco.data.legibleAccent
import com.eddie.surco.data.letterIndex
import com.eddie.surco.data.listenStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTest {
    private val ink = 0x101216
    /** El fondo más claro sobre el que va el acento (pestaña activa, píldoras): el caso peor. */
    private val panel2 = 0x20242C

    @Test fun fundidoBajaAlFinalYSubeAlEmpezar() {
        val dur = 200_000L; val f = 6_000L
        assertEquals(0f, crossfadeGain(0, dur, f, fadeIn = true, fadeOut = true), 1e-4f)
        assertEquals(1f, crossfadeGain(100_000, dur, f, true, true), 1e-4f)
        assertEquals(0f, crossfadeGain(dur, dur, f, true, true), 1e-4f)
        // Potencia constante: a mitad del fundido −3 dB (0,707), no −6 dB (0,5).
        assertEquals(0.7071f, crossfadeGain(3_000, dur, f, true, true), 1e-3f)
        assertEquals(0.7071f, crossfadeGain(dur - 3_000, dur, f, true, true), 1e-3f)
        // Sin fundido de entrada (la anterior era del mismo disco), el principio suena entero.
        assertEquals(1f, crossfadeGain(0, dur, f, fadeIn = false, fadeOut = true), 1e-4f)
        // Nunca sube y baja a saltos: la curva es monótona a cada lado.
        var prev = -1f
        for (p in 0..6_000 step 100) { val g = crossfadeGain(p.toLong(), dur, f, true, true); assertTrue(g >= prev); prev = g }
    }

    @Test fun fundidoEnCancionCortaNoSeComeLaCancion() {
        // 8 s de fundido en una canción de 10 s: como mucho un cuarto por cada lado.
        assertEquals(1f, crossfadeGain(5_000, 10_000, 8_000, true, true), 1e-4f)
        assertTrue(crossfadeGain(2_400, 10_000, 8_000, true, true) < 1f)
        // Sin duración aún: solo sube al empezar.
        assertEquals(0f, crossfadeGain(0, 0, 8_000, true, true), 1e-4f)
        assertEquals(1f, crossfadeGain(9_000, 0, 8_000, true, true), 1e-4f)
        // Duración corta por error (MP3 VBR): pasada la duración no se queda en silencio.
        assertEquals(1f, crossfadeGain(205_000, 200_000, 6_000, true, true), 1e-4f)
        assertEquals(1f, crossfadeGain(0, 10_000, 0, true, true), 1e-4f)
    }

    @Test fun losDiscosSinPausasNoSeFunden() {
        val a = AlbumRef("The Dark Side of the Moon", "Pink Floyd/1973")
        val b = AlbumRef("the dark side of the moon", "Pink Floyd/1973")
        val otraCarpeta = AlbumRef("Grandes éxitos", "Otro")
        val otraCarpeta2 = AlbumRef("Grandes exitos", "Otro más")
        assertFalse(fadesBetween(a, b, keepAlbums = true))
        assertTrue(fadesBetween(a, b, keepAlbums = false))
        assertTrue(fadesBetween(otraCarpeta, otraCarpeta2, keepAlbums = true))
        assertTrue(fadesBetween(AlbumRef("", "x"), AlbumRef("", "x"), keepAlbums = true))
        assertTrue(fadesBetween(a, null, keepAlbums = true))
    }

    @Test fun indiceDeLetras() {
        assertEquals('A', indexLetter("Ábaco"))
        assertEquals('E', indexLetter("  éxito"))
        assertEquals('Ñ', indexLetter("Ñu"))
        assertEquals('#', indexLetter("1999"))
        assertEquals('#', indexLetter(""))
        assertEquals('B', indexLetter("¡Bravo!"))
        val idx = letterIndex(listOf("Ábaco", "Abeja", "Bravo", "Burro", "Zorro"))
        assertEquals(listOf('A', 'B', 'Z'), idx.keys.toList())
        assertEquals(listOf(0, 2, 4), idx.values.toList())
        // En orden de alfabeto aunque la lista ponga antes un «(» o deje «¿» y «Ñ» al final.
        val raro = letterIndex(listOf("(Intro)", "Abeja", "Nube", "Zorro", "¿Qué?", "Ñandú", "1999"))
        assertEquals(listOf('#', 'A', 'I', 'N', 'Ñ', 'Q', 'Z'), raro.keys.toList())
        assertEquals(6, raro['#']); assertEquals(0, raro['I']); assertEquals(5, raro['Ñ'])
    }

    @Test fun acentosLegiblesDeCualquierPortada() {
        // Colores reales de portadas: negro, gris, azul marino, rojo oscuro, amarillo, blanco.
        for (c in listOf(0x000000, 0x777777, 0x0A1A4A, 0x5A0A0A, 0xF2D21B, 0xFFFFFF, 0x1E7F3A, 0x6A00FF)) {
            val a = legibleAccent(c, panel2)
            assertTrue("acento ${Integer.toHexString(a)} de ${Integer.toHexString(c)}: ${contrast(a, panel2)}", contrast(a, panel2) >= 4.5)
            assertTrue(contrast(a, ink) >= 4.5)
        }
        // Blanco, negro y gris no tienen tono: el acento de siempre, no un salmón inventado.
        assertEquals(0xE08A4B, legibleAccent(0x000000, panel2))
        assertEquals(0xE08A4B, legibleAccent(0x808080, panel2))
    }

    @Test fun acentosFijosLegibles() {
        // Los de Ajustes → Interfaz: texto del acento sobre el fondo y texto del fondo sobre el acento.
        for (c in listOf(0xE08A4B, 0x5FA8E8, 0x5DBB7E, 0xE8789F, 0xA98BEB, 0xE2B13C)) {
            assertTrue(Integer.toHexString(c), contrast(c, ink) >= 4.5 && contrast(c, panel2) >= 4.5)
        }
    }

    @Test fun tusEscuchas() {
        val info = mapOf(1L to ("A" to 180_000L), 2L to ("B" to 240_000L), 3L to ("A" to 60_000L))
        val s = listenStats(mapOf(1L to 3, 2L to 1, 3L to 2, 99L to 7)) { info[it] }
        assertEquals(6, s.plays) // la 99 ya no está en el móvil: no cuenta
        assertEquals((3 * 180 + 240 + 2 * 60) / 60L, s.minutes)
        assertEquals(3, s.songs)
        assertEquals(listOf("A" to 5, "B" to 1), s.topArtists)
        assertEquals(1L, s.topSongs.first().first)
    }
}
