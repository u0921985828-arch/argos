package com.eddie.surco

import com.eddie.surco.data.fold
import com.eddie.surco.data.matchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun sinTildes() {
        assertEquals("electronica", fold("Electrónica"))
        assertEquals("nu metal", fold("Ñu Metal"))
    }

    @Test fun porPalabras() {
        val t = listOf("Hijo de la Luna", "Mecano", "Entre el cielo y el suelo")
        assertTrue(matchesQuery(t, "mecano luna"))
        assertTrue(matchesQuery(t, "MECANO"))
        assertFalse(matchesQuery(t, "mecano sol"))
        assertFalse(matchesQuery(t, "   "))
    }
}
