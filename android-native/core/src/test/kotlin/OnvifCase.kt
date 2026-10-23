package eus.argos.paridad

import eus.argos.core.Onvif
import java.time.Instant

/** ONVIF: el resumen de la clave, los mensajes y la lectura de respuestas como las de una Tapo. */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val nonce = ByteArray(16) { it.toByte() }
    // Calculado aparte: base64(sha1(nonce + "2026-10-23T09:00:00Z" + "clave secreta")).
    pide("resumen WS-Security", Onvif.resumen("clave secreta", nonce, "2026-10-23T09:00:00Z") == "S8rd+FZY9yZno9y/piuHhkey+nA=")
    val seg = Onvif.seguridad("eddie", "clave secreta", Instant.parse("2026-10-23T09:00:00Z"), nonce)
    pide("la clave no viaja en claro", "clave secreta" !in seg && "S8rd+FZY9yZno9y/piuHhkey+nA=" in seg && "<wsse:Username>eddie<" in seg)
    pide("usuario con caracteres raros, escapado", "a&amp;b&lt;" in Onvif.seguridad("a&b<", "x", Instant.EPOCH, nonce))
    val m = Onvif.sobre(Onvif.mueve("perfil_1", 0.5, -2.0, 0.0), seg)
    pide("movimiento: velocidades dentro de [-1, 1]", """<tt:PanTilt x="0.50" y="-1.00"/>""" in m && "<tptz:ProfileToken>perfil_1<" in m)

    val hora = """<SOAP-ENV:Envelope><SOAP-ENV:Body><tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>
        <tt:UTCDateTime><tt:Time><tt:Hour>8</tt:Hour><tt:Minute>59</tt:Minute><tt:Second>7</tt:Second></tt:Time>
        <tt:Date><tt:Year>2026</tt:Year><tt:Month>10</tt:Month><tt:Day>23</tt:Day></tt:Date></tt:UTCDateTime>
        <tt:LocalDateTime><tt:Time><tt:Hour>10</tt:Hour></tt:Time></tt:LocalDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
    pide("hora de la cámara (UTC, no la local)", Onvif.leeHora(hora) == Instant.parse("2026-10-23T08:59:07Z"))
    val cap = """<tds:Capabilities><tt:Device><tt:XAddr>http://192.168.1.50:2020/onvif/device_service</tt:XAddr></tt:Device>
        <tt:Media><tt:XAddr>http://192.168.1.50:2020/onvif/service</tt:XAddr></tt:Media>
        <tt:PTZ><tt:XAddr> http://192.168.1.50:2020/onvif/ptz </tt:XAddr></tt:PTZ></tds:Capabilities>"""
    pide("servicios PTZ y Media", Onvif.leeServicio(cap, "PTZ") == "http://192.168.1.50:2020/onvif/ptz" &&
        Onvif.leeServicio(cap, "Media") == "http://192.168.1.50:2020/onvif/service")
    pide("sin PTZ: nada", Onvif.leeServicio("<tt:Media><tt:XAddr>x</tt:XAddr></tt:Media>", "PTZ") == null)
    val perf = """<trt:GetProfilesResponse><trt:Profiles token="perfil_2" fixed="true"><tt:Name>sub</tt:Name></trt:Profiles>
        <trt:Profiles fixed="true" token="perfil_1"><tt:Name>main</tt:Name><tt:PTZConfiguration token="ptz0"/></trt:Profiles></trt:GetProfilesResponse>"""
    pide("perfil con PTZ, aunque no sea el primero", Onvif.leePerfil(perf) == "perfil_1")
    pide("error SOAP: su motivo", Onvif.fallo("""<s:Fault><s:Reason><s:Text xml:lang="en">Sender not Authorized</s:Text></s:Reason></s:Fault>""") == "Sender not Authorized" &&
        Onvif.fallo(cap) == null)

    pide("dirección del vídeo, con &amp; desescapado", Onvif.leeUri("""<trt:MediaUri><tt:Uri>rtsp://192.168.1.50:554/cam/realmonitor?channel=1&amp;subtype=0</tt:Uri></trt:MediaUri>""") ==
        "rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=0" && Onvif.leeUri("<tt:Uri>http://x</tt:Uri>") == null)
    pide("pedir el vídeo de un perfil", "<trt:ProfileToken>perfil_1<" in Onvif.flujo("perfil_1"))

    println(if (fallos == 0) "ONVIF: CUMPLE" else "ONVIF: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
