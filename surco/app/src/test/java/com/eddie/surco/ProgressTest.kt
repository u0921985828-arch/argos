package com.eddie.surco

import com.eddie.surco.data.seekShown
import com.eddie.surco.data.shownDuration
import com.eddie.surco.data.songDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTest {
    @Test fun duracionDelReproductorODeLaBiblioteca() {
        assertEquals(215_000L, shownDuration(215_000, 214_000))
        // Al empezar (o un AAC sin cabecera) el reproductor aún no la sabe: la de la biblioteca.
        assertEquals(214_000L, shownDuration(0, 214_000))
        assertEquals(0L, shownDuration(0, -1))
    }

    @Test fun laBarraEsperaAlSalto() {
        // En local el mando ya está en el destino: se enseña lo suyo.
        assertEquals(90_100L, seekShown(90_000, 90_100, 100, playing = true))
        // Un aparato DLNA aún dice la posición de antes: destino más lo que ha sonado.
        assertEquals(90_800L, seekShown(90_000, 12_000, 800, playing = true))
        assertEquals(90_000L, seekShown(90_000, 12_000, 800, playing = false))
    }

    @Test fun sentidoDelCambio() {
        assertEquals(1, songDirection(3, 4, 10))
        assertEquals(-1, songDirection(4, 3, 10))
        assertEquals(1, songDirection(9, 0, 10))
        assertEquals(-1, songDirection(0, 9, 10))
        assertEquals(1, songDirection(-1, 0, 10))
    }
}
