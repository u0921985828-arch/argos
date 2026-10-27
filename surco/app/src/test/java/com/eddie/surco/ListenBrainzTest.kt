package com.eddie.surco

import com.eddie.surco.data.Json
import com.eddie.surco.data.LB_BATCH
import com.eddie.surco.data.LB_QUEUE_MAX
import com.eddie.surco.data.LB_VALIDATE
import com.eddie.surco.data.LbStatus
import com.eddie.surco.data.Listen
import com.eddie.surco.data.jsonStr
import com.eddie.surco.data.lbBody
import com.eddie.surco.data.lbCleanToken
import com.eddie.surco.data.lbCounts
import com.eddie.surco.data.lbEnqueue
import com.eddie.surco.data.lbFlush
import com.eddie.surco.data.lbHttp
import com.eddie.surco.data.lbQueueParse
import com.eddie.surco.data.lbQueueText
import com.eddie.surco.data.lbRemove
import com.eddie.surco.data.lbSendable
import com.eddie.surco.data.lbValidUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzTest {
    private fun l(i: Int) = Listen("Artista $i", "Canción $i", "Disco", 200_000L, 1_700_000_000L + i)

    @Test fun escapaTodoLoQueRompeElJson() {
        val raro = "Comillas \" barra \\ / salto\nretorno\rtab\tcontrol\u0001\u001f del\u007f sep\u2028 «ñ» 🎵"
        assertEquals(raro, Json.parse(jsonStr(raro)))
        assertEquals("\"a\\\"b\"", jsonStr("a\"b"))
        assertEquals("\"\\u0001\"", jsonStr("\u0001"))
    }

    @Test fun cuerpoComoPideListenBrainz() {
        val e = Listen("Rosalía", "Malamente \"Cap.1\"", "El Mal Querer", 150_000L, 1_700_000_123L)
        val o = Json.parse(lbBody("single", listOf(e))) as Map<*, *>
        assertEquals("single", o["listen_type"])
        val p = (o["payload"] as List<*>).single() as Map<*, *>
        assertEquals(1_700_000_123.0, p["listened_at"])
        val m = p["track_metadata"] as Map<*, *>
        assertEquals("Rosalía", m["artist_name"])
        assertEquals("Malamente \"Cap.1\"", m["track_name"])
        assertEquals("El Mal Querer", m["release_name"])
        val info = m["additional_info"] as Map<*, *>
        assertEquals(150_000.0, info["duration_ms"])
        assertEquals("Surco", info["media_player"])
        assertEquals("Surco", info["submission_client"])
        assertEquals("2.7", info["submission_client_version"])
    }

    @Test fun sonandoAhoraSinHoraYSinDiscoVacio() {
        val o = Json.parse(lbBody("playing_now", listOf(Listen("A", "B", " ", 60_000L, 0)))) as Map<*, *>
        assertEquals("playing_now", o["listen_type"])
        val p = (o["payload"] as List<*>).single() as Map<*, *>
        assertFalse(p.containsKey("listened_at"))
        assertFalse((p["track_metadata"] as Map<*, *>).containsKey("release_name"))
    }

    @Test fun reglasDeEscucha() {
        assertTrue(lbCounts(100_000, 200_000))      // la mitad
        assertFalse(lbCounts(99_999, 200_000))
        assertTrue(lbCounts(240_000, 1_200_000))    // 4 minutos de una larga
        assertFalse(lbCounts(29_000, 29_000))       // menos de 30 s: nunca
        assertFalse(lbSendable("A", "B", 29_999))
        assertFalse(lbSendable("A", "B", -9_223_372_036_854_775_807L)) // duración desconocida de Media3
        assertFalse(lbSendable(" ", "B", 60_000))
        assertTrue(lbSendable("A", "B", 30_000))
    }

    @Test fun tokenPegado() {
        val t = "0b2a7c9e-1111-4a3b-9c2d-123456789abc"
        assertEquals(t, lbCleanToken("  Token $t\n"))
        assertEquals(t, lbCleanToken("token\t$t "))
        assertEquals(t, lbCleanToken(t))
    }

    @Test fun validarToken() {
        assertEquals("eddie", lbValidUser("""{"code":200,"message":"Token valid.","valid":true,"user_name":"eddie"}"""))
        assertNull(lbValidUser("""{"code":200,"message":"Token invalid.","valid":false}"""))
        assertNull(lbValidUser("no es json"))
    }

    @Test fun colaIdaYVuelta() {
        val q = listOf(Listen("A \"x\"", "B\\c", "", 31_000L, 5), l(1), l(2))
        assertEquals(q, lbQueueParse(lbQueueText(q)))
        assertEquals(emptyList<Listen>(), lbQueueParse("[]"))
        assertNull(lbQueueParse("[{roto"))
        // Entradas a medias se saltan sin tirar el resto.
        assertEquals(listOf(l(1)), lbQueueParse("[{\"x\":1}," + lbQueueText(listOf(l(1))).removePrefix("[")))
    }

    @Test fun colaConTopeYSinRepetidas() {
        var q = emptyList<Listen>()
        for (i in 0 until LB_QUEUE_MAX + 5) q = lbEnqueue(q, l(i))
        assertEquals(LB_QUEUE_MAX, q.size)
        assertEquals(l(5), q.first())                  // caen las más viejas
        assertEquals(q, lbEnqueue(q, l(LB_QUEUE_MAX + 4)))
    }

    @Test fun enviaEnLotesYSoloQuitaLoAceptado() {
        val q = (0 until 250).map { l(it) }
        val bodies = ArrayList<Map<*, *>>()
        val r = lbFlush(q) { b -> bodies += Json.parse(b) as Map<*, *>; 200 }
        assertEquals(LbStatus.OK, r.status)
        assertEquals(listOf(LB_BATCH, LB_BATCH, 50), bodies.map { (it["payload"] as List<*>).size })
        assertTrue(bodies.all { it["listen_type"] == "import" })
        assertEquals(emptyList<Listen>(), lbRemove(q, r.done))

        // Una sola va como «single».
        lbFlush(listOf(l(0))) { b -> assertEquals("single", (Json.parse(b) as Map<*, *>)["listen_type"]); 200 }
    }

    @Test fun sinRedO429Reintenta() {
        val q = (0 until 250).map { l(it) }
        var n = 0
        val r = lbFlush(q) { if (++n == 1) 200 else 429 }
        assertEquals(LbStatus.RETRY, r.status)
        assertEquals(q.drop(LB_BATCH), lbRemove(q, r.done))
        assertEquals(LbStatus.RETRY, lbFlush(q) { null }.status)
        assertEquals(LbStatus.RETRY, lbFlush(q) { 503 }.status)
        assertTrue(lbFlush(q) { 503 }.done.isEmpty())
    }

    @Test fun tokenMaloPara() {
        var n = 0
        val r = lbFlush((0 until 250).map { l(it) }) { n++; 401 }
        assertEquals(LbStatus.BAD_TOKEN, r.status)
        assertEquals(1, n)
        assertTrue(r.done.isEmpty())
    }

    @Test fun unaMalaNoAtascaLaCola() {
        val q = (0 until 5).map { l(it) }
        val r = lbFlush(q) { b ->
            val p = (Json.parse(b) as Map<*, *>)["payload"] as List<*>
            if (p.size > 1 || ((p[0] as Map<*, *>)["listened_at"] as Number).toLong() == l(2).listenedAt) 400 else 200
        }
        assertEquals(LbStatus.OK, r.status)
        assertEquals(q, r.done)
    }

    @Test fun loQueLlegaMientrasSeEnviaSeQueda() {
        val q = listOf(l(1), l(2))
        assertEquals(listOf(l(3)), lbRemove(q + l(3), q))
        assertEquals(q, lbRemove(q, emptyList()))
    }

    /** Contra el servicio de verdad (SURCO_RED=1): un token inventado no vale, pero contesta. */
    @Test fun servicioReal() {
        if (System.getenv("SURCO_RED") != "1") return
        val r = lbHttp(LB_VALIDATE, "00000000-0000-0000-0000-000000000000")
        assertNotNull(r)
        assertNull(lbValidUser(r!!.second))
    }
}
