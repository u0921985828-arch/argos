package com.eddie.surco.data

import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

// UPnP/DLNA sin Android: buscar renderizadores (SSDP), leer su descripción y hablarles por SOAP.
// Lo universal para «Enviar a»: teles, Sonos, receptores, también en móviles sin servicios de
// Google. Se prueba en la JVM con respuestas reales de ejemplo y un renderizador falso.

/** Lo que contesta un aparato a la búsqueda SSDP (o anuncia con NOTIFY). */
data class SsdpReply(val location: String, val st: String, val usn: String, val server: String)

/** Para el icono: tele, altavoz o receptor (amplificador con red). */
enum class RendererKind { TV, SPEAKER, RECEIVER }

/** Un renderizador DLNA con las direcciones de control ya resueltas. */
data class UpnpDevice(
    val udn: String,
    val name: String,
    val location: String,
    val manufacturer: String,
    val model: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val renderingUrl: String?,
    val renderingType: String?,
) {
    val kind: RendererKind get() = Upnp.guessKind(name, manufacturer, model)
}

/** Una petición SOAP lista para mandar: dirección de control, cabecera SOAPACTION y cuerpo. */
data class SoapRequest(val url: String, val soapAction: String, val body: String)

/** Respuesta SOAP: los valores de salida por nombre, o el fallo UPnP (errorCode). */
sealed interface SoapResult {
    data class Ok(val values: Map<String, String>) : SoapResult
    data class Fault(val code: Int, val description: String) : SoapResult
}

/** Posición según el aparato: null cuando no la sabe (NOT_IMPLEMENTED, vacío). */
data class UpnpPosition(val relMs: Long?, val durationMs: Long?, val trackUri: String)

/** Lo que se cuenta del archivo en DIDL-Lite: con eso la tele enseña título, artista y portada. */
data class DidlItem(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val url: String,
    val artUrl: String? = null,
    val size: Long = -1,
)

/** Qué hacer tras sondear el aparato. */
enum class RemoteStep { NOTHING, RENDERER_ADVANCED, ENDED }

object Upnp {
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    // Fallos UPnP que dicen «esa acción no la tengo» (SetNextAVTransportURI es opcional).
    const val ERR_INVALID_ACTION = 401
    const val ERR_NOT_IMPLEMENTED = 602

    // ---------- SSDP ----------

    /** Mensaje de búsqueda. MX: segundos que puede tardar cada aparato en contestar. */
    fun mSearch(st: String = MEDIA_RENDERER, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $st\r\n" +
            "USER-AGENT: Android UPnP/1.1 Surco/2.6\r\n\r\n"

    /** Respuesta a M-SEARCH o anuncio NOTIFY. Cabeceras sin distinguir mayúsculas; byebye o sin LOCATION: null. */
    fun parseSsdp(text: String): SsdpReply? {
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim().orEmpty()
        val ok = first.startsWith("HTTP/1.", ignoreCase = true) && first.split(' ').getOrNull(1) == "200"
        val notify = first.startsWith("NOTIFY", ignoreCase = true)
        if (!ok && !notify) return null
        val h = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) h[l.substring(0, i).trim().uppercase(Locale.ROOT)] = l.substring(i + 1).trim()
        }
        if (notify && h["NTS"]?.contains("byebye", ignoreCase = true) == true) return null
        val loc = h["LOCATION"]?.takeIf { it.startsWith("http://", ignoreCase = true) } ?: return null
        return SsdpReply(loc, h["ST"] ?: h["NT"].orEmpty(), h["USN"].orEmpty(), h["SERVER"].orEmpty())
    }

    /**
     * Manda [message] a [to] por [socket] y recoge respuestas durante [windowMs]. [socket] ya va
     * atado a la wifi (en Android). Las repetidas por LOCATION se dan una vez.
     */
    fun search(socket: DatagramSocket, to: SocketAddress, message: String, windowMs: Long, onReply: (SsdpReply) -> Unit) {
        val data = message.toByteArray(Charsets.US_ASCII)
        // UDP se pierde: dos envíos separados, como hacen todos los mandos UPnP.
        socket.send(DatagramPacket(data, data.size, to))
        val seen = HashSet<String>()
        val buf = ByteArray(4096)
        val end = System.currentTimeMillis() + windowMs
        var resent = false
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            if (!resent && left < windowMs / 2) { socket.send(DatagramPacket(data, data.size, to)); resent = true }
            socket.soTimeout = left.coerceIn(1, 300).toInt()
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
            val r = parseSsdp(String(p.data, p.offset, p.length, Charsets.UTF_8)) ?: continue
            if (seen.add(r.location)) onReply(r)
        }
    }

    // ---------- Descripción del aparato ----------

    /**
     * Lee la descripción (XML) de un aparato. Busca el servicio AVTransport en cualquier nivel
     * (Sonos lo tiene en un aparato anidado) y el RenderingControl del mismo aparato. Las URL
     * relativas se resuelven contra URLBase o, si no hay, contra la LOCATION. Sin AVTransport: null.
     */
    fun parseDescription(xml: String, location: String): UpnpDevice? {
        val doc = parseXml(xml) ?: return null
        val root = doc.documentElement ?: return null
        val top = children(root, "device").firstOrNull() ?: return null
        val base = children(root, "URLBase").firstOrNull()?.textContent?.trim()?.takeIf { it.startsWith("http", true) } ?: location
        val services = descendants(root, "service")
        val avt = services.firstOrNull { text(it, "serviceType").contains(":service:AVTransport:") } ?: return null
        val avtUrl = text(avt, "controlURL").takeIf { it.isNotEmpty() } ?: return null
        // El RenderingControl del mismo aparato que el AVTransport (en Sonos hay varios).
        val owner = avt.parentNode?.parentNode as? Element
        val rc = owner?.let { o -> children(o, "serviceList").flatMap { children(it, "service") } }
            ?.firstOrNull { text(it, "serviceType").contains(":service:RenderingControl:") }
        // Sonos pone la IP en friendlyName; su nombre de verdad es la habitación.
        val name = text(top, "roomName").ifEmpty { text(top, "friendlyName") }.ifEmpty { location }
        return UpnpDevice(
            udn = text(top, "UDN").ifEmpty { location },
            name = name,
            location = location,
            manufacturer = text(top, "manufacturer"),
            model = text(top, "modelName"),
            avTransportUrl = resolve(base, avtUrl),
            avTransportType = text(avt, "serviceType"),
            renderingUrl = rc?.let { text(it, "controlURL") }?.takeIf { it.isNotEmpty() }?.let { resolve(base, it) },
            renderingType = rc?.let { text(it, "serviceType") },
        )
    }

    /** Une [ref] a [base] como un navegador. Java no pone la «/» si la base no tiene ruta: se añade. */
    fun resolve(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        return try {
            val b = URI(base.trim())
            val fixed = if (b.rawPath.isNullOrEmpty()) URI(b.scheme, b.rawAuthority, "/", null, null) else b
            fixed.resolve(URI(r)).toString()
        } catch (e: Exception) {
            // Una dirección con espacios u otra rareza: al menos el anfitrión de la base y la ruta.
            base.trim().substringBefore("://") + "://" + base.trim().substringAfter("://").substringBefore('/') + "/" + r.removePrefix("/")
        }
    }

    fun guessKind(name: String, manufacturer: String, model: String): RendererKind {
        val s = "$name $manufacturer $model".lowercase(Locale.ROOT)
        return when {
            listOf("[tv]", " tv", "tv ", "bravia", "webos", "television", "smart tv", "roku", "android tv", "fire tv", "hisense", "tcl", "philips tv", "vizio")
                .any { it in " $s " } -> RendererKind.TV
            listOf("denon", "marantz", "yamaha", "onkyo", "pioneer", "avr", "receiver", "integra", "harman kardon", "cambridge", "naim", "bluesound node")
                .any { it in s } -> RendererKind.RECEIVER
            else -> RendererKind.SPEAKER
        }
    }

    // ---------- SOAP ----------

    private fun soap(url: String, service: String, action: String, args: List<Pair<String, String>>): SoapRequest {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        sb.append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(esc(service)).append("\">")
        for ((k, v) in args) sb.append('<').append(k).append('>').append(esc(v)).append("</").append(k).append('>')
        sb.append("</u:").append(action).append("></s:Body></s:Envelope>")
        return SoapRequest(url, "\"$service#$action\"", sb.toString())
    }

    private fun avt(d: UpnpDevice, action: String, vararg args: Pair<String, String>) =
        soap(d.avTransportUrl, d.avTransportType.ifEmpty { AV_TRANSPORT }, action, listOf("InstanceID" to "0") + args)

    private fun rc(d: UpnpDevice, action: String, vararg args: Pair<String, String>): SoapRequest? {
        val url = d.renderingUrl ?: return null
        return soap(url, d.renderingType ?: RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)
    }

    fun setUri(d: UpnpDevice, url: String, didl: String) = avt(d, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to didl)
    fun setNextUri(d: UpnpDevice, url: String, didl: String) = avt(d, "SetNextAVTransportURI", "NextURI" to url, "NextURIMetaData" to didl)
    fun play(d: UpnpDevice) = avt(d, "Play", "Speed" to "1")
    fun pause(d: UpnpDevice) = avt(d, "Pause")
    fun stop(d: UpnpDevice) = avt(d, "Stop")
    fun seek(d: UpnpDevice, ms: Long) = avt(d, "Seek", "Unit" to "REL_TIME", "Target" to msToHms(ms))
    fun getPositionInfo(d: UpnpDevice) = avt(d, "GetPositionInfo")
    fun getTransportInfo(d: UpnpDevice) = avt(d, "GetTransportInfo")
    /** Volumen 0–100. Sin RenderingControl: null (el volumen se lleva en el propio aparato). */
    fun setVolume(d: UpnpDevice, volume: Int) = rc(d, "SetVolume", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString())
    fun getVolume(d: UpnpDevice) = rc(d, "GetVolume", "Channel" to "Master")
    fun setMute(d: UpnpDevice, mute: Boolean) = rc(d, "SetMute", "Channel" to "Master", "DesiredMute" to if (mute) "1" else "0")

    /** Manda [req] y lee la respuesta. Un fallo SOAP (500 con UPnPError) vuelve como [SoapResult.Fault]. */
    fun send(req: SoapRequest, timeoutMs: Int = 4000, newSocket: () -> java.net.Socket = { java.net.Socket() }): SoapResult {
        val reply = HttpCall.request(
            "POST", req.url,
            listOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to req.soapAction),
            req.body.toByteArray(Charsets.UTF_8), timeoutMs, newSocket = newSocket,
        )
        val text = reply.text()
        if (reply.code in 200..299) return parseSoap(text)
        // 500 con cuerpo SOAP: el fallo UPnP; otro código sin cuerpo útil: fallo HTTP.
        return (parseSoap(text) as? SoapResult.Fault) ?: SoapResult.Fault(-reply.code, "HTTP ${reply.code}")
    }

    /** Lee una respuesta SOAP: los hijos de la «…Response» por nombre, o el fallo con su errorCode. */
    fun parseSoap(xml: String): SoapResult {
        val doc = parseXml(xml) ?: return SoapResult.Fault(-1, "XML")
        val root = doc.documentElement ?: return SoapResult.Fault(-1, "XML")
        val fault = descendants(root, "Fault").firstOrNull()
        if (fault != null) {
            val code = descendants(fault, "errorCode").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: -1
            val desc = descendants(fault, "errorDescription").firstOrNull()?.textContent?.trim()
                ?: descendants(fault, "faultstring").firstOrNull()?.textContent?.trim().orEmpty()
            return SoapResult.Fault(code, desc)
        }
        val body = descendants(root, "Body").firstOrNull() ?: return SoapResult.Fault(-1, "sin Body")
        val resp = elements(body).firstOrNull() ?: return SoapResult.Ok(emptyMap())
        return SoapResult.Ok(elements(resp).associate { local(it) to it.textContent.orEmpty().trim() })
    }

    fun position(values: Map<String, String>) = UpnpPosition(
        relMs = hmsToMs(values["RelTime"].orEmpty()),
        durationMs = hmsToMs(values["TrackDuration"].orEmpty())?.takeIf { it > 0 },
        trackUri = values["TrackURI"].orEmpty(),
    )

    fun transportState(values: Map<String, String>): String = values["CurrentTransportState"].orEmpty().uppercase(Locale.ROOT)
    fun volume(values: Map<String, String>): Int? = values["CurrentVolume"]?.trim()?.toIntOrNull()

    /**
     * Tras un sondeo: si el aparato ya va por la siguiente que se le dio (SetNextAVTransportURI),
     * ha avanzado él solo; si se ha parado después de haber sonado, la canción ha acabado.
     */
    fun judge(state: String, trackUri: String, nextUrl: String?, sawPlaying: Boolean): RemoteStep = when {
        nextUrl != null && trackUri.isNotEmpty() && sameUrl(trackUri, nextUrl) -> RemoteStep.RENDERER_ADVANCED
        sawPlaying && (state == "STOPPED" || state == "NO_MEDIA_PRESENT") -> RemoteStep.ENDED
        else -> RemoteStep.NOTHING
    }

    // Hay aparatos que devuelven la dirección con el XML escapado o con espacios.
    private fun sameUrl(a: String, b: String) = a.trim().replace("&amp;", "&") == b.trim()

    /**
     * Siguiente canción al acabar una, según la repetición (los valores de Player de Media3:
     * 0 sin repetir, 1 una, 2 todas). null: era la última.
     */
    fun nextIndex(current: Int, count: Int, repeatMode: Int): Int? = when {
        count <= 0 -> null
        repeatMode == 1 -> current
        current + 1 < count -> current + 1
        repeatMode == 2 -> 0
        else -> null
    }

    // ---------- Tiempos ----------

    /** «h:mm:ss», «h:mm:ss.fff» o «h:mm:ss.F0/F1» a milisegundos. NOT_IMPLEMENTED o vacío: null. */
    fun hmsToMs(s: String): Long? {
        val t = s.trim()
        val parts = t.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secPart = parts[2]
        val whole = secPart.substringBefore('.').toLongOrNull() ?: return null
        val frac = secPart.substringAfter('.', "")
        val fracMs = when {
            frac.isEmpty() -> 0L
            '/' in frac -> {
                val num = frac.substringBefore('/').toDoubleOrNull() ?: 0.0
                val den = frac.substringAfter('/').toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                (num / den * 1000).toLong()
            }
            else -> (("0." + frac.filter { it.isDigit() }).toDoubleOrNull() ?: 0.0).times(1000).toLong()
        }
        if (h < 0 || m !in 0..59 || whole !in 0..59) return null
        return ((h * 60 + m) * 60 + whole) * 1000 + fracMs
    }

    /** Para Seek: «hh:mm:ss» (dos cifras de hora: lo aceptan todos, también las teles quisquillosas). */
    fun msToHms(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    /** Duración de DIDL-Lite: «h:mm:ss.fff». */
    fun didlDuration(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val s = v / 1000
        return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", s / 3600, (s / 60) % 60, s % 60, v % 1000)
    }

    // ---------- DIDL-Lite y tipos ----------

    /** Metadatos de la canción para SetAVTransportURI. Todo escapado (va otra vez escapado dentro del SOAP). */
    fun didl(item: DidlItem): String {
        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        sb.append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
        sb.append("<dc:title>").append(esc(item.title)).append("</dc:title>")
        if (item.artist.isNotBlank()) {
            sb.append("<dc:creator>").append(esc(item.artist)).append("</dc:creator>")
            sb.append("<upnp:artist>").append(esc(item.artist)).append("</upnp:artist>")
        }
        if (item.album.isNotBlank()) sb.append("<upnp:album>").append(esc(item.album)).append("</upnp:album>")
        if (item.artUrl != null) sb.append("<upnp:albumArtURI>").append(esc(item.artUrl)).append("</upnp:albumArtURI>")
        sb.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        sb.append("<res protocolInfo=\"http-get:*:").append(esc(item.mime)).append(':').append(DLNA_FEATURES).append('"')
        if (item.durationMs > 0) sb.append(" duration=\"").append(didlDuration(item.durationMs)).append('"')
        if (item.size >= 0) sb.append(" size=\"").append(item.size).append('"')
        sb.append('>').append(esc(item.url)).append("</res>")
        sb.append("</item></DIDL-Lite>")
        return sb.toString()
    }

    /** Tipo MIME por el formato de Surco (MP3, FLAC, AAC, OPUS…, ver LibraryScanner). null: no se sabe. */
    fun mimeForFormat(format: String): String? = when (format.trim().uppercase(Locale.ROOT)) {
        "MP3", "MPEG" -> "audio/mpeg"
        "FLAC" -> "audio/flac"
        "AAC", "M4A", "MP4", "ALAC", "M4B" -> "audio/mp4"
        "OGG", "OGA", "VORBIS", "OPUS" -> "audio/ogg"
        "WAV", "WAVE" -> "audio/wav"
        "AIFF", "AIF" -> "audio/aiff"
        "WMA" -> "audio/x-ms-wma"
        "DSD", "DSF" -> "audio/x-dsf"
        "APE" -> "audio/x-ape"
        else -> null
    }

    /** Extensión para la dirección servida (hay teles que miran la extensión antes que el tipo). */
    fun extForMime(mime: String): String = when (mime.lowercase(Locale.ROOT).substringBefore(';').trim()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> "m4a"
        "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        "audio/x-ms-wma" -> "wma"
        "audio/x-dsf" -> "dsf"
        "audio/x-ape", "audio/ape" -> "ape"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> "bin"
    }

    fun esc(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    // ---------- XML ----------

    /**
     * Sin espacios de nombres a propósito: hay teles que usan prefijos sin declararlos (pnpx:, sec:)
     * y un analizador estricto se negaría a leerlas. Los nombres se comparan sin prefijo.
     * Sin DTD: una descripción no la necesita y así no se leen entidades externas.
     */
    private fun parseXml(xml: String): Document? {
        val clean = xml.trim().removePrefix("\uFEFF").trim()
        if (clean.isEmpty()) return null
        return try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            // Android solo admite unas pocas funciones; las que no, lanzan: se prueban una a una.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { f.isExpandEntityReferences = false }
            val b = f.newDocumentBuilder()
            // Sin el gestor por defecto, que escribe cada XML roto en la consola.
            b.setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) = Unit
                override fun error(e: SAXParseException) = Unit
                override fun fatalError(e: SAXParseException) = throw e
            })
            b.parse(InputSource(StringReader(clean)))
        } catch (e: Exception) {
            // IOException, SAXException, ParserConfigurationException… (en Android, también RuntimeException de KXml).
            null
        }
    }

    private fun local(n: Node): String = n.nodeName.substringAfter(':')

    private fun elements(parent: Node): List<Element> {
        val out = ArrayList<Element>()
        val nl = parent.childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun children(parent: Node, name: String) = elements(parent).filter { local(it) == name }

    private fun descendants(parent: Node, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Node) { for (e in elements(n)) { if (local(e) == name) out += e; walk(e) } }
        walk(parent)
        return out
    }

    private fun text(parent: Node, name: String): String = children(parent, name).firstOrNull()?.textContent?.trim().orEmpty()
}
