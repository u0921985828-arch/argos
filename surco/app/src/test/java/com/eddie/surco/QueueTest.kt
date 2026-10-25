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

    @Test fun idsQueCambianSeRecuperanPorRutaOPorDatos() {
        val keysA = com.eddie.surco.data.stableKeys("/sdcard/Music/a.flac", "Uno", "X", "Disco", 200_400)
        val keysB = com.eddie.surco.data.stableKeys("/sdcard/Music/b.flac", "Dos", "X", "Disco", 180_000)
        // Tras volver a copiar: a.flac tiene id 101 (misma ruta); b.flac se movió de carpeta (id 102).
        val nowKeys = mapOf(
            101L to com.eddie.surco.data.stableKeys("/sdcard/Music/a.flac", "Uno", "X", "Disco", 200_900),
            102L to com.eddie.surco.data.stableKeys("/sdcard/Otra/b.flac", "Dos", "X", "Disco", 180_300),
        )
        val idByKey = nowKeys.flatMap { (id, ks) -> ks.map { it to id } }.toMap()
        val m = com.eddie.surco.data.remapIds(setOf(1L, 2L, 3L, 101L), setOf(101L, 102L), mapOf(1L to keysA, 2L to keysB), idByKey)
        assertEquals(mapOf(1L to 101L, 2L to 102L), m) // 3 no tiene claves: se queda como está
    }

    @Test fun aleatorioEmpiezaPorLaQueSuenaYNoPierdeNinguna() {
        val o = com.eddie.surco.data.currentFirstOrder(10, 4, kotlin.random.Random(1))
        assertEquals(4, o[0])
        assertEquals((0 until 10).toSet(), o.toSet())
        assertEquals(10, o.size)
    }

    @Test fun aContinuacionYALaColaEnAleatorio() {
        // Lista de 5, orden aleatorio 3,0,4,1,2 sonando la 0 (segunda del orden).
        val order = listOf(3, 0, 4, 1, 2)
        // «A continuación»: 2 nuevas en el índice 1 de la lista (detrás de la 0).
        val next = com.eddie.surco.data.insertIntoOrder(order, 1, 2, 0, playNext = true)
        assertEquals(listOf(5, 0, 1, 2, 6, 3, 4), next)
        // «A la cola»: 1 nueva al final de la lista (índice 5).
        val end = com.eddie.surco.data.insertIntoOrder(order, 5, 1, 0, playNext = false)
        assertEquals(listOf(3, 0, 4, 1, 2, 5), end)
    }
}
