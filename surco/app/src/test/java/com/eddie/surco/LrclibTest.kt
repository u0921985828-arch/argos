package com.eddie.surco

import com.eddie.surco.data.Json
import com.eddie.surco.data.fetchLrclib
import com.eddie.surco.data.httpGet
import com.eddie.surco.data.lrclibGetUrl
import com.eddie.surco.data.lrclibPick
import com.eddie.surco.data.parseLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibTest {
    @Test fun direccionConTildesYEspacios() {
        assertEquals(
            "https://lrclib.net/api/get?artist_name=Rosal%C3%ADa&track_name=Malamente%20%28Cap.1%29&album_name=El%20Mal%20Querer&duration=150",
            lrclibGetUrl("Rosalía", "Malamente (Cap.1)", "El Mal Querer", 150),
        )
        assertEquals("https://lrclib.net/api/get?artist_name=A&track_name=B&duration=9", lrclibGetUrl("A", "B", "", 9))
    }

    @Test fun jsonMinimo() {
        val o = Json.parse("""{"id":1,"instrumental":false,"plainLyrics":"Hola\nmundo á","syncedLyrics":null,"duration":233.5,"x":[1,true,null,"a\"b"]}""") as Map<*, *>
        assertEquals("Hola\nmundo á", o["plainLyrics"])
        assertNull(o["syncedLyrics"])
        assertEquals(233.5, o["duration"])
        assertEquals(listOf(1.0, true, null, "a\"b"), o["x"])
        assertNull(Json.parse("{roto"))
    }

    @Test fun eligeSincronizadaYDuracion() {
        val ficha = mapOf("instrumental" to false, "plainLyrics" to "texto", "syncedLyrics" to "[00:01.00]hola")
        assertEquals("[00:01.00]hola", lrclibPick(ficha, 200))
        assertEquals("", lrclibPick(mapOf("instrumental" to true), 200))
        val lista = listOf(
            mapOf("duration" to 260.0, "syncedLyrics" to "[00:01.00]otra versión", "plainLyrics" to "x"),
            mapOf("duration" to 201.0, "syncedLyrics" to null, "plainLyrics" to "solo texto"),
            mapOf("duration" to 199.0, "syncedLyrics" to "[00:02.00]buena", "plainLyrics" to "y"),
        )
        assertEquals("[00:02.00]buena", lrclibPick(lista, 200))
        assertNull(lrclibPick(listOf(mapOf("duration" to 300.0, "plainLyrics" to "z")), 200))
    }

    @Test fun primeroLaFichaYLuegoLaBusqueda() {
        val pedidas = ArrayList<String>()
        val r = fetchLrclib("A", "B", "C", 200) { url ->
            pedidas += url
            if ("/api/get" in url) 404 to """{"code":404,"name":"TrackNotFound"}"""
            else 200 to """[{"duration":200,"plainLyrics":"encontrada","syncedLyrics":""}]"""
        }
        assertEquals("encontrada", r)
        assertEquals(2, pedidas.size)
        // Sin red no se inventa nada; sin artista o sin duración, ni se pregunta.
        assertNull(fetchLrclib("A", "B", "C", 200) { null })
        assertNull(fetchLrclib("", "B", "C", 200) { error("no debería preguntar") })
        assertNull(fetchLrclib("A", "B", "C", 0) { error("no debería preguntar") })
    }

    /** Contra lrclib.net de verdad: solo con SURCO_RED=1 (en el CI, que tiene red). */
    @Test fun lrclibEnVivo() {
        if (System.getenv("SURCO_RED") != "1") return
        // Sin conexión con el servicio (caído, sin red) no se puede juzgar al cliente: se avisa y ya.
        if (httpGet("https://lrclib.net/") == null) { println("LRCLIB en vivo: sin conexión, no se prueba"); return }
        val raw = fetchLrclib("Queen", "Bohemian Rhapsody", "A Night at the Opera", 354, ::httpGet)
        assertNotNull("LRCLIB no devolvió la letra", raw)
        val l = parseLyrics(raw!!, "LRCLIB")
        assertNotNull(l)
        assertTrue("letra: ${raw.take(200)}", l!!.lines.any { it.text.contains("real life", ignoreCase = true) })
        println("LRCLIB en vivo: ${l.lines.size} líneas, sincronizada=${l.synced}")
    }
}
