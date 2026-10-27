package com.eddie.surco.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// «Enviar a» (DLNA y Chromecast): la música está en el móvil, así que Surco la sirve por HTTP en la
// wifi y le dice al aparato qué dirección tocar. Sin Android: se prueba en la JVM con sockets de
// verdad. En Android los archivos se abren por ContentResolver: la fuente es una función.

/** Lo que dice un servidor DLNA de cómo se puede pedir el archivo: por bytes (OP=01), en flujo. */
const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/** Qué parte del archivo pide un «Range». */
sealed interface ByteRange {
    /** Entero (sin Range, o uno que no se entiende: el RFC permite ignorarlo). */
    data object Full : ByteRange
    /** Bytes [start]..[end], los dos incluidos. */
    data class Part(val start: Long, val end: Long) : ByteRange
    /** Fuera del archivo: 416. */
    data object Unsatisfiable : ByteRange
}

/**
 * Servidor HTTP mínimo con hilo propio. Solo sirve lo registrado, por un nombre aleatorio
 * (/m/<token>.<ext>): nada de rutas ni de archivos por su nombre, así que en la wifi nadie puede
 * pedir otra cosa que lo que suena. GET y HEAD, Range (lo usan las teles para saltar y para leer
 * la cabecera y el final del archivo) y las cabeceras DLNA que algunas teles exigen.
 */
class MiniHttp(private val bind: InetAddress? = null, private val maxClients: Int = 16) {

    /**
     * Un archivo servible. [length] se pide al servir (fuera del hilo principal) y puede ser -1 si
     * no se sabe: entonces va entero, sin Range. [open] abre el contenido desde el principio.
     */
    class Source(val mime: String, val length: () -> Long, val open: () -> InputStream) {
        companion object {
            fun ofFile(file: File, mime: String) = Source(mime, { file.length() }, { FileInputStream(file) })
            fun ofBytes(bytes: ByteArray, mime: String) = Source(mime, { bytes.size.toLong() }, { ByteArrayInputStream(bytes) })
        }
    }

    private val files = ConcurrentHashMap<String, Source>()
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val active = AtomicInteger()
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    private var acceptor: Thread? = null

    /** Puerto en el que escucha (0 si está parado). */
    val port: Int get() = server?.localPort ?: 0
    val running: Boolean get() = server?.isClosed == false

    /** Arranca en un puerto libre (o en [port]) y devuelve el puerto. Arrancar dos veces no hace nada. */
    @Synchronized
    fun start(port: Int = 0): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bind, port), 32)
        val counter = AtomicInteger()
        val p = Executors.newCachedThreadPool { r -> Thread(r, "surco-http-" + counter.incrementAndGet()).apply { isDaemon = true } }
        server = s; pool = p
        acceptor = Thread({ acceptLoop(s, p) }, "surco-http").apply { isDaemon = true; start() }
        return s.localPort
    }

    /** Para: cierra el puerto y corta lo que se esté sirviendo. */
    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        pool?.shutdownNow(); pool = null
        acceptor?.let { if (it !== Thread.currentThread()) it.join(2000) }
        acceptor = null
    }

    /** Registra [src] y devuelve su ruta (/m/<token>.<ext>). La extensión solo ayuda a las teles quisquillosas. */
    fun register(src: Source, ext: String): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val e = ext.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }.take(5)
        val path = "/m/$token" + if (e.isNotEmpty()) ".$e" else ""
        files[token] = src
        return path
    }

    fun unregister(path: String) { tokenOf(path)?.let { files.remove(it) } }
    fun clear() = files.clear()
    val registered: Int get() = files.size

    private fun acceptLoop(s: ServerSocket, p: ExecutorService) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            if (active.incrementAndGet() > maxClients) {
                // Demasiadas a la vez: se dice y se cierra, sin hilo nuevo.
                runCatching { c.getOutputStream().write(head(503, "Service Unavailable", listOf("Content-Length" to "0"))); c.close() }
                active.decrementAndGet()
                continue
            }
            clients += c
            try {
                p.execute {
                    try { serve(c) } finally { clients -= c; active.decrementAndGet(); runCatching { c.close() } }
                }
            } catch (_: Exception) {
                // Parando: el grupo de hilos ya no acepta trabajo.
                clients -= c; active.decrementAndGet(); runCatching { c.close() }
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 20_000
            c.tcpNoDelay = true
            val input = c.getInputStream()
            val out = c.getOutputStream()
            val req = readHead(input) ?: return
            val lines = req.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) { out.write(head(400, "Bad Request", listOf("Content-Length" to "0"))); return }
            val method = parts[0]
            val headers = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
            }
            if (method != "GET" && method != "HEAD") {
                out.write(head(405, "Method Not Allowed", listOf("Allow" to "GET, HEAD", "Content-Length" to "0"))); return
            }
            val src = tokenOf(parts[1].substringBefore('?'))?.let { files[it] }
            if (src == null) { out.write(head(404, "Not Found", listOf("Content-Length" to "0"))); return }
            send(out, method == "HEAD", src, headers["range"])
        } catch (_: IOException) {
            // El aparato colgó a mitad (lo hacen al sondear el archivo o al saltar): no es un error.
        } catch (_: RuntimeException) {
            // La fuente falló al abrirse (archivo borrado, sin permiso): la conexión se cierra sin más.
        }
    }

    private fun send(out: OutputStream, headOnly: Boolean, src: Source, range: String?) {
        val len = runCatching { src.length() }.getOrDefault(-1L)
        val common = listOf(
            "Content-Type" to src.mime,
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to DLNA_FEATURES,
        )
        if (len < 0) {
            // Sin longitud no hay Range: entero y se cierra al acabar.
            out.write(head(200, "OK", common))
            if (!headOnly) src.open().use { it.copyTo(out, 64 * 1024) }
            out.flush()
            return
        }
        when (val r = parseRange(range, len)) {
            ByteRange.Unsatisfiable ->
                out.write(head(416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$len", "Content-Length" to "0")))
            ByteRange.Full -> {
                out.write(head(200, "OK", common + listOf("Accept-Ranges" to "bytes", "Content-Length" to "$len")))
                if (!headOnly) copy(src, 0, len, out)
            }
            is ByteRange.Part -> {
                val n = r.end - r.start + 1
                out.write(head(206, "Partial Content", common + listOf(
                    "Accept-Ranges" to "bytes", "Content-Range" to "bytes ${r.start}-${r.end}/$len", "Content-Length" to "$n",
                )))
                if (!headOnly) copy(src, r.start, n, out)
            }
        }
        out.flush()
    }

    private fun copy(src: Source, start: Long, count: Long, out: OutputStream) {
        src.open().use { input ->
            skipFully(input, start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    companion object {
        private val TOKEN = Regex("^/m/([0-9a-f]{32})(\\.[a-z0-9]{1,5})?$")

        private fun tokenOf(path: String): String? = TOKEN.matchEntire(path)?.groupValues?.get(1)

        /** Interpreta «Range» para un archivo de [length] bytes. Varios tramos o unidades raras: entero. */
        fun parseRange(header: String?, length: Long): ByteRange {
            if (header.isNullOrBlank() || length < 0) return ByteRange.Full
            val h = header.trim()
            if (!h.startsWith("bytes=", ignoreCase = true)) return ByteRange.Full
            val spec = h.substring(6).trim()
            if (spec.contains(',')) return ByteRange.Full
            val dash = spec.indexOf('-')
            if (dash < 0) return ByteRange.Full
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (a.isEmpty()) {
                // «-n»: los últimos n bytes (las teles leen así las etiquetas del final).
                val n = b.toLongOrNull() ?: return ByteRange.Full
                if (n <= 0 || length == 0L) return ByteRange.Unsatisfiable
                return ByteRange.Part(maxOf(0, length - n), length - 1)
            }
            val start = a.toLongOrNull() ?: return ByteRange.Full
            val end = if (b.isEmpty()) length - 1 else b.toLongOrNull() ?: return ByteRange.Full
            if (end < start) return ByteRange.Full
            if (start >= length) return ByteRange.Unsatisfiable
            return ByteRange.Part(start, minOf(end, length - 1))
        }

        /** Cabeceras de respuesta. Siempre «Connection: close»: una petición por conexión, sin estado. */
        internal fun head(code: Int, reason: String, headers: List<Pair<String, String>>): ByteArray {
            val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
            sb.append("Server: Surco UPnP/1.0 DLNADOC/1.50\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        /** Cabecera de la petición hasta la línea en blanco (máximo 16 KB). */
        private fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var last4 = 0
            while (buf.size() < 16 * 1024) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) return buf.toString("ISO-8859-1").trimEnd()
            }
            return null
        }

        private fun skipFully(input: InputStream, n: Long) {
            var left = n
            val scratch = ByteArray(16 * 1024)
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) { left -= s; continue }
                // Hay flujos que no saltan (devuelven 0): se lee y se tira.
                val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                if (r < 0) throw IOException("fin del archivo antes de $n")
                left -= r
            }
        }
    }
}

/** Respuesta de [HttpCall]: código, cabeceras (en minúsculas) y cuerpo. */
class HttpReply(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun text(): String = String(body, Charsets.UTF_8)
}

/**
 * Cliente HTTP/1.1 mínimo sobre un socket, para hablar con los aparatos de la wifi (descripción y
 * SOAP). No usa HttpURLConnection a propósito: Android (targetSdk 28+) prohíbe ahí el HTTP sin
 * cifrar salvo que se abra en el manifiesto para toda la app, y los aparatos UPnP solo hablan HTTP.
 * Entiende Content-Length, «chunked» y hasta-cerrar, que de las tres hay en las teles.
 */
object HttpCall {
    fun request(
        method: String,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        timeoutMs: Int = 4000,
        maxBytes: Int = 2 shl 20,
        newSocket: () -> Socket = { Socket() },
    ): HttpReply {
        val u = URI(url)
        if (!u.scheme.equals("http", ignoreCase = true)) throw IOException("solo http: $url")
        val host = u.host ?: throw IOException("sin host: $url")
        val port = if (u.port > 0) u.port else 80
        val path = (u.rawPath?.ifEmpty { null } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        newSocket().use { s ->
            s.connect(InetSocketAddress(host.removePrefix("[").removeSuffix("]"), port), timeoutMs)
            s.soTimeout = timeoutMs
            val sb = StringBuilder("$method $path HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append(if (u.port > 0) ":$port" else "").append("\r\n")
            sb.append("User-Agent: Surco UPnP/1.1\r\nConnection: close\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()
            val input = s.getInputStream().buffered()
            val status = readLine(input) ?: throw IOException("sin respuesta")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("respuesta rara: $status")
            val hs = HashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) hs[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
            }
            val data = when {
                method == "HEAD" || code == 204 || code == 304 -> ByteArray(0)
                hs["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input, maxBytes)
                hs["content-length"] != null -> {
                    val n = hs["content-length"]?.toLongOrNull() ?: throw IOException("Content-Length raro")
                    if (n > maxBytes) throw IOException("respuesta demasiado grande: $n")
                    readExactly(input, n.toInt())
                }
                else -> readAll(input, maxBytes)
            }
            return HttpReply(code, hs, data)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            if (buf.size() > 16 * 1024) throw IOException("línea demasiado larga")
            buf.write(b)
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw SocketException("cortado a $off de $n")
            off += r
        }
        return out
    }

    private fun readAll(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > max) throw IOException("respuesta demasiado grande")
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("trozo raro: $line")
            if (size == 0) {
                // Cabeceras finales (casi nunca) hasta la línea en blanco.
                while (true) { val t = readLine(input) ?: break; if (t.isEmpty()) break }
                break
            }
            out.write(readExactly(input, size))
            if (out.size() > max) throw IOException("respuesta demasiado grande")
            readLine(input) // el \r\n tras cada trozo
        }
        return out.toByteArray()
    }
}
