package com.eddie.surco

import com.eddie.surco.data.seekSettled
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
        assertEquals(false, seekSettled(90_000, 12_000, 100))
        assertEquals(true, seekSettled(90_000, 90_300, 100))
        // Un archivo que no deja saltar: a los 2 s la barra vuelve a lo que suena.
        assertEquals(true, seekSettled(90_000, 12_000, 2_100))
    }

    @Test fun sentidoDelCambio() {
        assertEquals(1, songDirection(3, 4, 10))
        assertEquals(-1, songDirection(4, 3, 10))
        assertEquals(1, songDirection(9, 0, 10))
        assertEquals(-1, songDirection(0, 9, 10))
        assertEquals(1, songDirection(-1, 0, 10))
    }
}
