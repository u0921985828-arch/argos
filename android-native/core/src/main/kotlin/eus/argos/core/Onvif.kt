package eus.argos.core

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Mover una cámara IP propia (giro, inclinación y zoom) por ONVIF, el estándar
 * que hablan Tapo, Hikvision, Dahua, Reolink y casi todas: mensajes SOAP por
 * HTTP con usuario y clave.
 *
 * La clave no viaja: se manda su resumen WS-Security (SHA-1 de un número de un
 * solo uso + la hora + la clave). Por eso la hora importa: muchas cámaras
 * rechazan un resumen con la hora desfasada, así que primero se le pregunta la
 * suya y se firma con ella.
 *
 * Aquí solo se escriben y se leen los mensajes; quien los envía es la app.
 */
object Onvif {
    private val HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** PasswordDigest = Base64(SHA-1(nonce + created + clave)). */
    fun resumen(clave: String, nonce: ByteArray, creado: String): String {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(creado.toByteArray(Charsets.UTF_8)); sha.update(clave.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha.digest())
    }

    fun seguridad(usuario: String, clave: String, ahora: Instant, nonce: ByteArray): String {
        val creado = HORA.format(ahora)
        return """<wsse:Security s:mustUnderstand="1" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">""" +
            "<wsse:UsernameToken><wsse:Username>${xml(usuario)}</wsse:Username>" +
            """<wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${resumen(clave, nonce, creado)}</wsse:Password>""" +
            """<wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${Base64.getEncoder().encodeToString(nonce)}</wsse:Nonce>""" +
            "<wsu:Created>$creado</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    fun sobre(cuerpo: String, seguridad: String?): String =
        """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" """ +
        """xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" """ +
        """xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">""" +
        "<s:Header>${seguridad ?: ""}</s:Header><s:Body>$cuerpo</s:Body></s:Envelope>"

    const val HORA_CAMARA = "<tds:GetSystemDateAndTime/>"
    const val CAPACIDADES = "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>"
    const val PERFILES = "<trt:GetProfiles/>"

    /** La dirección RTSP del vídeo de un perfil: así no hay que saberse la ruta de cada marca. */
    fun flujo(perfil: String): String =
        "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>" +
        "</trt:StreamSetup><trt:ProfileToken>${xml(perfil)}</trt:ProfileToken></trt:GetStreamUri>"

    fun leeUri(xml: String): String? =
        Regex("<(?:\\w+:)?Uri>\\s*(.*?)\\s*</(?:\\w+:)?Uri>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.takeIf { it.startsWith("rtsp", ignoreCase = true) }

    /** Velocidades entre -1 y 1: giro (x), inclinación (y) y zoom. */
    fun mueve(perfil: String, x: Double, y: Double, zoom: Double): String =
        "<tptz:ContinuousMove><tptz:ProfileToken>${xml(perfil)}</tptz:ProfileToken><tptz:Velocity>" +
        """<tt:PanTilt x="${v(x)}" y="${v(y)}"/><tt:Zoom x="${v(zoom)}"/></tptz:Velocity></tptz:ContinuousMove>"""

    fun para(perfil: String): String =
        "<tptz:Stop><tptz:ProfileToken>${xml(perfil)}</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>"

    fun aCasa(perfil: String): String =
        "<tptz:GotoHomePosition><tptz:ProfileToken>${xml(perfil)}</tptz:ProfileToken></tptz:GotoHomePosition>"

    private fun v(d: Double) = "%.2f".format(java.util.Locale.ROOT, d.coerceIn(-1.0, 1.0))
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** La hora UTC que dice la cámara, de GetSystemDateAndTimeResponse. */
    fun leeHora(xml: String): Instant? {
        val utc = Regex("UTCDateTime>(.*?)</[\\w:]*UTCDateTime", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        fun n(k: String) = Regex("<[\\w:]*$k>(\\d+)</").find(utc)?.groupValues?.get(1)?.toIntOrNull()
        val y = n("Year") ?: return null; val mo = n("Month") ?: return null; val d = n("Day") ?: return null
        val h = n("Hour") ?: 0; val mi = n("Minute") ?: 0; val s = n("Second") ?: 0
        return runCatching { java.time.LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** La dirección de un servicio («PTZ», «Media») en GetCapabilitiesResponse. */
    fun leeServicio(xml: String, servicio: String): String? =
        Regex("<[\\w:]*$servicio>.*?<[\\w:]*XAddr>\\s*(.*?)\\s*</[\\w:]*XAddr>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    /** El perfil que lleva PTZ (si ninguno lo dice, el primero), de GetProfilesResponse. */
    fun leePerfil(xml: String): String? {
        val perfiles = Regex("<[\\w:]*Profiles\\b([^>]*)>(.*?)</[\\w:]*Profiles>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { m -> Regex("token=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1) to m.groupValues[2] }
            .filter { it.first != null }.toList()
        return (perfiles.firstOrNull { "PTZConfiguration" in it.second } ?: perfiles.firstOrNull())?.first
    }

    /** ¿La respuesta es un error SOAP? Devuelve su motivo para decírselo al usuario. */
    fun fallo(xml: String): String? =
        if (!Regex("<[\\w:]*Fault\\b").containsMatchIn(xml)) null
        else Regex("<[\\w:]*Text[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: "error"
}
