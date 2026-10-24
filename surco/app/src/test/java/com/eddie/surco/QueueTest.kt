package com.eddie.surco

import com.eddie.surco.data.keptQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueTest {
    private val ids = listOf(10L, 20L, 30L, 40L)

    @Test fun todasExisten() {
        assertEquals(Triple(listOf(0, 1, 2, 3), 1, true), keptQueue(ids, 1) { true })
    }

    @Test fun faltaUnaAntesDeLaQueSonaba() {
        // [A falta, B, C, D] sonando B (índice 1): se reanuda B, no C.
        val (kept, start, same) = keptQueue(ids, 1) { it != 10L }!!
        assertEquals(listOf(1, 2, 3), kept)
        assertEquals(20L, ids[kept[start]])
        assertEquals(true, same)
    }

    @Test fun faltaLaQueSonaba() {
        val (kept, start, same) = keptQueue(ids, 1) { it != 20L }!!
        assertEquals(30L, ids[kept[start]])
        assertEquals(false, same)
    }

    @Test fun faltaLaUltimaYSonaba() {
        val (kept, start, same) = keptQueue(ids, 3) { it != 40L }!!
        assertEquals(10L, ids[kept[start]])
        assertEquals(false, same)
    }

    @Test fun noQuedaNinguna() {
        assertNull(keptQueue(ids, 0) { false })
    }
}
