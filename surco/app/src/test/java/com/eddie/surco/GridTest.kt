package com.eddie.surco

import com.eddie.surco.data.GRID_COLUMNS
import com.eddie.surco.data.gridColumns
import com.eddie.surco.data.gridSpacing
import com.eddie.surco.data.gridStep
import org.junit.Assert.assertEquals
import org.junit.Test

class GridTest {
    @Test fun fibonacciDeDosAOcho() {
        assertEquals(listOf(2, 3, 5, 8), GRID_COLUMNS)
        assertEquals(3, gridStep(2, more = true)); assertEquals(5, gridStep(3, more = true)); assertEquals(8, gridStep(5, more = true))
        assertEquals(8, gridStep(8, more = true)) // no pasa de 8
        assertEquals(5, gridStep(8, more = false)); assertEquals(2, gridStep(2, more = false)) // ni baja de 2
    }

    @Test fun valoresGuardadosDeAntesSeRecolocan() {
        // La 2.6 guardaba 2–5: el 4 pasa al paso más cercano.
        assertEquals(3, gridColumns(4)); assertEquals(5, gridColumns(5)); assertEquals(2, gridColumns(0)); assertEquals(8, gridColumns(40))
    }

    @Test fun huecosEnProporcion() {
        val gaps = GRID_COLUMNS.map { gridSpacing(it).first }
        assertEquals(gaps.sortedDescending(), gaps) // más portadas, menos hueco
        GRID_COLUMNS.forEach { assertEquals(true, gridSpacing(it).second > gridSpacing(it).first) }
    }
}
