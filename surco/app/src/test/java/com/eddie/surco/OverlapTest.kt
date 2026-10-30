package com.eddie.surco

import com.eddie.surco.data.crossfadeGain
import com.eddie.surco.data.overlapOutGain
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class OverlapTest {
    @Test fun laQueSeVaBajaDeUnoACero() {
        assertEquals(1f, overlapOutGain(0, 3000), 1e-6f)
        assertEquals(0f, overlapOutGain(3000, 3000), 1e-6f)
        assertEquals(0f, overlapOutGain(5000, 3000), 1e-6f)
        assertEquals(0f, overlapOutGain(10, 0), 1e-6f)
    }

    @Test fun potenciaConstanteEnElCruce() {
        // La que entra sube con crossfadeGain y la que se va baja con overlapOutGain: la suma de
        // potencias se queda en 1 en todo el cruce (sin bache ni subidón a mitad).
        for (t in 0..3000 step 250) {
            val inG = crossfadeGain(t.toLong(), 20_000, 3000, fadeIn = true, fadeOut = false)
            val outG = overlapOutGain(t.toLong(), 3000)
            assertEquals("en $t ms", 1.0, inG.toDouble().pow(2) + outG.toDouble().pow(2), 1e-4)
        }
    }
}
