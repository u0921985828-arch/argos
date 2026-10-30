package com.eddie.surco

import com.eddie.surco.data.parseGainDb
import com.eddie.surco.data.parseLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun lrcConVariasMarcasYOffset() {
        val l = parseLyrics(
            """
            [ti:Canción]
            [ar:Alguien]
            [offset:+500]
            [00:12.30]Primera
            [00:05.00][01:00.5]Estribillo
            [00:20.123] Tercera
            """.trimIndent(), "test",
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(4500L, 11800L, 19623L, 60000L), l.lines.map { it.timeMs })
        assertEquals(listOf("Estribillo", "Primera", "Tercera", "Estribillo"), l.lines.map { it.text })
        assertEquals(-1, l.indexAt(1000))
        assertEquals(0, l.indexAt(4500))
        assertEquals(1, l.indexAt(15000))
        assertEquals(3, l.indexAt(999_999))
    }

    @Test fun letraSinMarcas() {
        val l = parseLyrics("Hola\nque tal\n\nadiós", "x")!!
        assertFalse(l.synced)
        assertEquals(4, l.lines.size)
        assertEquals(-1, l.indexAt(5000))
        assertNull(parseLyrics("   \n ", "x"))
    }

    @Test fun ganancias() {
        assertEquals(-6.48f, parseGainDb("-6.48 dB")!!, 1e-4f)
        assertEquals(1.2f, parseGainDb("+1,2 dB")!!, 1e-4f)
        assertEquals(-3.1f, parseGainDb("−3.1")!!, 1e-4f)
        assertNull(parseGainDb("nada"))
    }
}
