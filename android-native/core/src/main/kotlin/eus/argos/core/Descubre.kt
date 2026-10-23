package eus.argos.core

/**
 * Buscar cámaras en la wifi sin saber su IP: WS-Discovery, la búsqueda de
 * ONVIF. Se manda una pregunta por multidifusión (239.255.255.250:3702) y cada
 * cámara contesta con su dirección y su nombre. Aquí solo se escribe la
 * pregunta y se leen las respuestas; quien las envía es la app.
 */
object Descubre {
    const val GRUPO = "239.255.255.250"
    const val PUERTO = 3702

    class Camara(val ip: String, val dispositivo: String, val nombre: String, val modelo: String)

    fun pregunta(id: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" """ +
        """xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" """ +
        """xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:$id</w:MessageID>""" +
        """<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>""" +
        """<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>""" +
        """<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""

    /** Una respuesta ProbeMatches; null si no es de una cámara o no trae dirección. */
    fun lee(xml: String): Camara? {
        if (!Regex("ProbeMatch\\b").containsMatchIn(xml)) return null
        val xaddrs = Regex("<(?:\\w+:)?XAddrs>\\s*(.*?)\\s*</(?:\\w+:)?XAddrs>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        val dirs = xaddrs.split(Regex("\\s+")).filter { it.startsWith("http") }
        // Mejor IPv4 que IPv6, y lo primero que anuncie.
        val disp = dirs.firstOrNull { Regex("//\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(it) } ?: dirs.firstOrNull() ?: return null
        val ip = runCatching { java.net.URI(disp).host }.getOrNull() ?: return null
        val scopes = Regex("<(?:\\w+:)?Scopes[^>]*>\\s*(.*?)\\s*</(?:\\w+:)?Scopes>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1).orEmpty()
        fun scope(k: String) = scopes.split(Regex("\\s+")).firstOrNull { it.contains("/$k/") }?.substringAfter("/$k/")
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()
        return Camara(ip, disp, scope("name"), scope("hardware"))
    }
}
