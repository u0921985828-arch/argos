package com.eddie.surco

import com.eddie.surco.data.ByteRange
import com.eddie.surco.data.HttpCall
import com.eddie.surco.data.MiniHttp
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MiniHttpTest {
    private val loop: InetAddress = InetAddress.getByName("127.0.0.1")
    private lateinit var http: MiniHttp
    private var port = 0
    // 200.003 bytes: más que un búfer de copia (64 KB) y que no es múltiplo de nada.
    private val data = ByteArray(200_003) { (it * 31 + 7).toByte() }

    @Before fun arranca() { http = MiniHttp(loop); port = http.start() }
    @After fun para() = http.stop()

    private fun url(path: String) = "http://127.0.0.1:$port$path"
    private fun get(path: String, vararg h: Pair<String, String>) = HttpCall.request("GET", url(path), h.toList())

    @Test fun rangosPuros() {
        assertEquals(ByteRange.Full, MiniHttp.parseRange(null, 100))
        assertEquals(ByteRange.Full, MiniHttp.parseRange("items=0-5", 100))
        assertEquals(ByteRange.Full, MiniHttp.parseRange("bytes=0-1,5-6", 100))
        assertEquals(ByteRange.Full, MiniHttp.parseRange("bytes=9-3", 100))
        assertEquals(ByteRange.Part(0, 99), MiniHttp.parseRange("bytes=0-", 100))
        assertEquals(ByteRange.Part(10, 19), MiniHttp.parseRange("Bytes = 10-19", 100))
        assertEquals(ByteRange.Part(90, 99), MiniHttp.parseRange("bytes=90-500", 100))
        assertEquals(ByteRange.Part(80, 99), MiniHttp.parseRange("bytes=-20", 100))
        assertEquals(ByteRange.Part(0, 99), MiniHttp.parseRange("bytes=-500", 100))
        assertEquals(ByteRange.Unsatisfiable, MiniHttp.parseRange("bytes=100-", 100))
        assertEquals(ByteRange.Unsatisfiable, MiniHttp.parseRange("bytes=-0", 100))
        assertEquals(ByteRange.Unsatisfiable, MiniHttp.parseRange("bytes=0-", 0))
        // Sin longitud no hay rangos.
        assertEquals(ByteRange.Full, MiniHttp.parseRange("bytes=5-", -1))
    }

    @Test fun enteroConCabecerasDlna() {
        val path = http.register(MiniHttp.Source.ofBytes(data, "audio/flac"), "flac")
        assertTrue(path, Regex("/m/[0-9a-f]{32}\\.flac").matches(path))
        val r = get(path)
        assertEquals(200, r.code)
        assertArrayEquals(data, r.body)
        assertEquals("audio/flac", r.headers["content-type"])
        assertEquals("200003", r.headers["content-length"])
        assertEquals("bytes", r.headers["accept-ranges"])
        assertEquals("Streaming", r.headers["transfermode.dlna.org"])
        assertTrue(r.headers["contentfeatures.dlna.org"].orEmpty().contains("DLNA.ORG_OP=01"))
    }

    @Test fun rangos206y416() {
        val path = http.register(MiniHttp.Source.ofBytes(data, "audio/mpeg"), "mp3")
        val mid = get(path, "Range" to "bytes=100000-100009")
        assertEquals(206, mid.code)
        assertEquals("bytes 100000-100009/200003", mid.headers["content-range"])
        assertArrayEquals(data.copyOfRange(100000, 100010), mid.body)
        val tail = get(path, "Range" to "bytes=-3")
        assertEquals(206, tail.code)
        assertArrayEquals(data.copyOfRange(200000, 200003), tail.body)
        val open = get(path, "Range" to "bytes=199999-")
        assertArrayEquals(data.copyOfRange(199999, 200003), open.body)
        val bad = get(path, "Range" to "bytes=200003-")
        assertEquals(416, bad.code)
        assertEquals("bytes */200003", bad.headers["content-range"])
        assertEquals(0, bad.body.size)
    }

    @Test fun headSinCuerpo() {
        val path = http.register(MiniHttp.Source.ofBytes(data, "audio/mpeg"), "mp3")
        // HEAD a mano: el cuerpo no debe llegar aunque se lea hasta que se cierre.
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("HEAD $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val all = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(all, all.startsWith("HTTP/1.1 200 OK"))
            assertTrue(all.contains("Content-Length: 200003"))
            assertTrue(all.endsWith("\r\n\r\n"))
        }
    }

    @Test fun soloLoRegistrado() {
        val path = http.register(MiniHttp.Source.ofBytes(data, "audio/mpeg"), "mp3")
        assertEquals(404, get("/").code)
        assertEquals(404, get("/m/").code)
        assertEquals(404, get("/m/" + "0".repeat(32) + ".mp3").code)
        assertEquals(404, get("/m/../../etc/passwd").code)
        assertEquals(404, get(path.uppercase()).code)
        assertEquals(405, HttpCall.request("POST", url(path), body = ByteArray(1)).code)
        // La extensión no cuenta (hay teles que la cambian); el token sí.
        assertEquals(200, get(path.substringBefore('.') + ".flac").code)
        http.unregister(path)
        assertEquals(404, get(path).code)
    }

    @Test fun fuenteSinLongitudYFuenteRota() {
        val noLen = http.register(MiniHttp.Source("audio/ogg", { -1L }, { ByteArrayInputStream(data) }), "ogg")
        val r = get(noLen, "Range" to "bytes=10-20")
        assertEquals(200, r.code)
        assertArrayEquals(data, r.body)
        // Un archivo que ya no se puede abrir: se corta la conexión, el servidor sigue vivo.
        val broken = http.register(MiniHttp.Source("audio/mpeg", { 10L }, { throw SecurityException("sin permiso") }), "mp3")
        try { get(broken); } catch (_: IOException) { /* esperado: conexión cerrada a mitad */ }
        assertEquals(200, get(noLen).code)
    }

    @Test fun variasALaVez() {
        // Un flujo lento (como una tarjeta SD) no bloquea a los demás.
        val slow = object : InputStream() {
            var i = 0
            override fun read(): Int { if (i % 50_000 == 0) Thread.sleep(20); return if (i < data.size) data[i++].toInt() and 0xff else -1 }
        }
        val pSlow = http.register(MiniHttp.Source("audio/wav", { data.size.toLong() }, { slow }), "wav")
        val pFast = http.register(MiniHttp.Source.ofBytes(data, "audio/flac"), "flac")
        val ex = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 12).map { n ->
                ex.submit(Callable {
                    if (n == 0) get(pSlow).body else get(pFast, "Range" to "bytes=${n * 1000}-${n * 1000 + 4999}").body
                })
            }
            jobs.forEachIndexed { n, f ->
                val body = f.get(20, TimeUnit.SECONDS)
                if (n == 0) assertArrayEquals(data, body) else assertArrayEquals(data.copyOfRange(n * 1000, n * 1000 + 5000), body)
            }
        } finally { ex.shutdownNow() }
    }

    @Test fun cierreLimpio() {
        // Una descarga a medias cuando se para: se corta, y el puerto deja de escuchar.
        val endless = object : InputStream() { override fun read(): Int { Thread.sleep(1); return 1 } }
        val p = http.register(MiniHttp.Source("audio/mpeg", { 1L shl 40 }, { endless }), "mp3")
        val s = Socket("127.0.0.1", port)
        s.getOutputStream().write("GET $p HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        s.getInputStream().read()
        assertTrue(http.running)
        http.stop()
        assertFalse(http.running)
        // Lo que estaba sirviendo se corta: la lectura acaba (fin o error), no se queda colgada.
        s.soTimeout = 5000
        try { while (s.getInputStream().read() >= 0) { /* lo que quedara en el búfer */ } } catch (_: IOException) { }
        s.close()
        try {
            Socket("127.0.0.1", port).use { fail("sigue escuchando") }
        } catch (_: IOException) { /* esperado */ }
        // Se puede volver a arrancar.
        port = http.start()
        assertTrue(http.running)
        assertEquals(404, get("/m/x").code)
    }
}
