package com.eddie.surco

import com.eddie.surco.data.DidlItem
import com.eddie.surco.data.HttpCall
import com.eddie.surco.data.MiniHttp
import com.eddie.surco.data.RemoteStep
import com.eddie.surco.data.RendererKind
import com.eddie.surco.data.SoapResult
import com.eddie.surco.data.Upnp
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource

class UpnpTest {
    private fun <T : Any> must(x: T?): T { assertNotNull(x); return x!! }

    // ---------- Descripciones de aparatos reales (recortadas) ----------

    // Samsung (Tizen): prefijos sin declarar (pnpx:, df:), sin URLBase, rutas absolutas.
    private val samsung = """<?xml version="1.0"?>
<root xmlns='urn:schemas-upnp-org:device-1-0' xmlns:sec='http://www.sec.co.kr/dlna' xmlns:dlna='urn:schemas-dlna-org:device-1-0'>
 <specVersion><major>1</major><minor>0</minor></specVersion>
 <device>
  <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
  <pnpx:X_compatibleId>MS_DigitalMediaDeviceClass_DMR_V001</pnpx:X_compatibleId>
  <df:X_deviceCategory>Display.TV.LCD Multimedia.DMR</df:X_deviceCategory>
  <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
  <friendlyName>[TV] Samsung 6 Series (55)</friendlyName>
  <manufacturer>Samsung Electronics</manufacturer>
  <modelName>UE55MU6100</modelName>
  <UDN>uuid:0ee4dd44-1d5c-4a42-8b5b-5b5c5d5e5f60</UDN>
  <sec:ProductCap>Y2017,WebURIPlayable,NavigateInPause,vdProductType=TV</sec:ProductCap>
  <serviceList>
   <service>
    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
    <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
    <controlURL>/upnp/control/RenderingControl1</controlURL>
    <eventSubURL>/upnp/event/RenderingControl1</eventSubURL>
    <SCPDURL>RenderingControl_1.xml</SCPDURL>
   </service>
   <service>
    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
    <controlURL>/upnp/control/ConnectionManager1</controlURL>
   </service>
   <service>
    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
    <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
    <controlURL>/upnp/control/AVTransport1</controlURL>
    <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
    <SCPDURL>AVTransport_1.xml</SCPDURL>
   </service>
  </serviceList>
 </device>
</root>"""

    // LG webOS: espacio de nombres por defecto, todo en una línea, rutas con el UDN.
    private val lg = """<?xml version="1.0" encoding="utf-8"?><root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[LG] webOS TV OLED55C9PLA</friendlyName><manufacturer>LG Electronics</manufacturer><modelName>LG TV</modelName><UDN>uuid:2c4ef3b0-a5d3-11e9-a2a3-2a2ae2dbcce4</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/AVTransport/2c4ef3b0/scpd.xml</SCPDURL><controlURL>/AVTransport/2c4ef3b0/control.xml</controlURL><eventSubURL>/AVTransport/2c4ef3b0/event.xml</eventSubURL></service><service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ConnectionManager/2c4ef3b0/control.xml</controlURL></service><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RenderingControl/2c4ef3b0/control.xml</controlURL></service></serviceList></device></root>"""

    // Sonos: el renderizador va anidado; la IP en friendlyName y el nombre de verdad en roomName.
    private val sonos = """<?xml version="1.0" encoding="utf-8" ?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
<friendlyName>192.168.1.40 - Sonos One - RINCON_48A6B8000000001400</friendlyName>
<manufacturer>Sonos, Inc.</manufacturer>
<modelName>Sonos One</modelName>
<UDN>uuid:RINCON_48A6B8000000001400</UDN>
<roomName>Salón &amp; cocina</roomName>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AlarmClock:1</serviceType><controlURL>/AlarmClock/Control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:DeviceProperties:1</serviceType><controlURL>/DeviceProperties/Control</controlURL></service>
</serviceList>
<deviceList>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
<friendlyName>192.168.1.40 - Sonos One Media Server</friendlyName>
<UDN>uuid:RINCON_48A6B8000000001400_MS</UDN>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/MediaServer/ContentDirectory/Control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/MediaServer/ConnectionManager/Control</controlURL></service>
</serviceList>
</device>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Salón - Sonos One Media Renderer</friendlyName>
<UDN>uuid:RINCON_48A6B8000000001400_MR</UDN>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/MediaRenderer/RenderingControl/Control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/MediaRenderer/ConnectionManager/Control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/MediaRenderer/AVTransport/Control</controlURL></service>
<service><serviceType>urn:schemas-sonos-com:service:Queue:1</serviceType><controlURL>/MediaRenderer/Queue/Control</controlURL></service>
</serviceList>
</device>
</deviceList>
</device>
</root>"""

    // Receptor Denon (HEOS): URLBase sin barra final y rutas relativas sin barra; con BOM delante.
    private val denon = "﻿" + """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<URLBase>http://192.168.1.30:49152</URLBase>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Denon AVR-X1600H</friendlyName>
<manufacturer>Denon</manufacturer>
<modelName>AVR-X1600H</modelName>
<UDN>uuid:5f9ec1b3-ed59-1900-4530-0005cdaa0000</UDN>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType><controlURL>upnp/control/AVTransport</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:2</serviceType><controlURL>upnp/control/RenderingControl</controlURL></service>
</serviceList>
</device>
</root>"""

    // Altavoz genérico sin URLBase con rutas relativas sin barra: se resuelven contra la LOCATION.
    private val generic = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Cocina</friendlyName><manufacturer>Linkplay</manufacturer><modelName>WiiM Mini</modelName><UDN>uuid:abcd</UDN>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/rendertransport1</controlURL></service></serviceList>
</device></root>"""

    @Test fun samsungSinUrlBase() {
        val d = must(Upnp.parseDescription(samsung, "http://192.168.1.20:9197/dmr"))
        assertEquals("[TV] Samsung 6 Series (55)", d.name)
        assertEquals("uuid:0ee4dd44-1d5c-4a42-8b5b-5b5c5d5e5f60", d.udn)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", d.avTransportUrl)
        assertEquals("http://192.168.1.20:9197/upnp/control/RenderingControl1", d.renderingUrl)
        assertEquals(Upnp.AV_TRANSPORT, d.avTransportType)
        assertEquals(RendererKind.TV, d.kind)
    }

    @Test fun lgWebos() {
        val d = must(Upnp.parseDescription(lg, "http://192.168.1.21:1471/"))
        assertEquals("http://192.168.1.21:1471/AVTransport/2c4ef3b0/control.xml", d.avTransportUrl)
        assertEquals("http://192.168.1.21:1471/RenderingControl/2c4ef3b0/control.xml", d.renderingUrl)
        assertEquals(RendererKind.TV, d.kind)
    }

    @Test fun sonosAnidado() {
        val d = must(Upnp.parseDescription(sonos, "http://192.168.1.40:1400/xml/device_description.xml"))
        assertEquals("Salón & cocina", d.name)
        assertEquals("uuid:RINCON_48A6B8000000001400", d.udn)
        assertEquals("http://192.168.1.40:1400/MediaRenderer/AVTransport/Control", d.avTransportUrl)
        // El RenderingControl del renderizador, no otro del árbol.
        assertEquals("http://192.168.1.40:1400/MediaRenderer/RenderingControl/Control", d.renderingUrl)
        assertEquals(RendererKind.SPEAKER, d.kind)
    }

    @Test fun conUrlBaseSinBarra() {
        val d = must(Upnp.parseDescription(denon, "http://192.168.1.30:60006/upnp/desc/aios_device/aios_device.xml"))
        assertEquals("http://192.168.1.30:49152/upnp/control/AVTransport", d.avTransportUrl)
        assertEquals("http://192.168.1.30:49152/upnp/control/RenderingControl", d.renderingUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", d.avTransportType)
        assertEquals(RendererKind.RECEIVER, d.kind)
        // La acción SOAP lleva el tipo de servicio que anuncia el aparato.
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:2#Play\"", Upnp.play(d).soapAction)
    }

    @Test fun sinUrlBaseRelativa() {
        val d = must(Upnp.parseDescription(generic, "http://192.168.1.50:49152/description.xml"))
        assertEquals("http://192.168.1.50:49152/upnp/control/rendertransport1", d.avTransportUrl)
        assertNull(d.renderingUrl)
        assertNull(Upnp.setVolume(d, 30))
        assertEquals(RendererKind.SPEAKER, d.kind)
    }

    @Test fun descripcionesRotas() {
        assertNull(Upnp.parseDescription("", "http://x/"))
        assertNull(Upnp.parseDescription("<root><device>", "http://x/"))
        // Un servidor de medios (sin AVTransport) no es un renderizador.
        assertNull(Upnp.parseDescription(sonos.replace("AVTransport", "Nada"), "http://x/"))
        // Con DOCTYPE (entidades externas): no se lee.
        assertNull(Upnp.parseDescription("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>", "http://x/"))
    }

    @Test fun resolverUrls() {
        assertEquals("http://h:1/a/b", Upnp.resolve("http://h:1", "a/b"))
        assertEquals("http://h:1/a/b", Upnp.resolve("http://h:1/", "/a/b"))
        assertEquals("http://h:1/x/a", Upnp.resolve("http://h:1/x/desc.xml", "a"))
        assertEquals("http://h:1/a", Upnp.resolve("http://h:1/x/desc.xml", "/a"))
        assertEquals("http://otro/a", Upnp.resolve("http://h:1/x/", "http://otro/a"))
    }

    // ---------- SSDP ----------

    @Test fun busquedaSsdp() {
        val m = Upnp.mSearch()
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue(m.contains("\r\nMAN: \"ssdp:discover\"\r\n"))
        assertTrue(m.contains("\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(m.endsWith("\r\n\r\n"))
    }

    @Test fun respuestasSsdp() {
        val samsungReply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: Tue, 27 Oct 2026 10:00:00 GMT\r\nEXT:\r\n" +
            "LOCATION: http://192.168.1.20:9197/dmr\r\nSERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:0ee4dd44::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val r = must(Upnp.parseSsdp(samsungReply))
        assertEquals("http://192.168.1.20:9197/dmr", r.location)
        assertEquals(Upnp.MEDIA_RENDERER, r.st)
        assertEquals("uuid:0ee4dd44::urn:schemas-upnp-org:device:MediaRenderer:1", r.usn)
        // Sonos: cabeceras en minúsculas y mezcladas, finales de línea \n.
        val sonosReply = "HTTP/1.1 200 OK\nCache-Control: max-age = 1800\nLocation: http://192.168.1.40:1400/xml/device_description.xml\n" +
            "server: Linux UPnP/1.0 Sonos/79.1-56030 (ZPS27)\nst: urn:schemas-upnp-org:device:MediaRenderer:1\nusn: uuid:RINCON_48A6B8000000001400_MR::urn:schemas-upnp-org:device:MediaRenderer:1\n\n"
        assertEquals("http://192.168.1.40:1400/xml/device_description.xml", Upnp.parseSsdp(sonosReply)?.location)
        val notify = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nNTS: ssdp:alive\r\nLOCATION: http://10.0.0.5:8080/d.xml\r\nUSN: uuid:x\r\n\r\n"
        assertEquals(Upnp.MEDIA_RENDERER, Upnp.parseSsdp(notify)?.st)
        assertNull(Upnp.parseSsdp(notify.replace("ssdp:alive", "ssdp:byebye")))
        assertNull(Upnp.parseSsdp("HTTP/1.1 404 Not Found\r\nLOCATION: http://x/\r\n\r\n"))
        assertNull(Upnp.parseSsdp("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
        assertNull(Upnp.parseSsdp("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"))
    }

    // ---------- SOAP ----------

    private fun xml(s: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(s)))

    @Test fun sobreSetUriConDidlEscapado() {
        val d = Upnp.parseDescription(samsung, "http://192.168.1.20:9197/dmr")!!
        val meta = Upnp.didl(DidlItem("Rock & <Roll>", "AC/DC \"Live\"", "Álbum 'raro'", 205_500, "audio/flac", "http://192.168.1.10:41000/m/abc.flac?x=1&y=2", "http://192.168.1.10:41000/m/def.jpg", 1234))
        val req = Upnp.setUri(d, "http://192.168.1.10:41000/m/abc.flac", meta)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", req.soapAction)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", req.url)
        // El sobre es XML válido, y dentro va el DIDL como texto: al leerlo sale entero y legible.
        val doc = xml(req.body)
        val didl = doc.getElementsByTagName("CurrentURIMetaData").item(0).textContent
        assertEquals(meta, didl)
        val item = xml(didl)
        assertEquals("Rock & <Roll>", item.getElementsByTagName("dc:title").item(0).textContent)
        assertEquals("AC/DC \"Live\"", item.getElementsByTagName("upnp:artist").item(0).textContent)
        assertEquals("Álbum 'raro'", item.getElementsByTagName("upnp:album").item(0).textContent)
        assertEquals("object.item.audioItem.musicTrack", item.getElementsByTagName("upnp:class").item(0).textContent)
        val res = item.getElementsByTagName("res").item(0)
        assertEquals("http://192.168.1.10:41000/m/abc.flac?x=1&y=2", res.textContent)
        assertEquals("0:03:25.500", res.attributes.getNamedItem("duration").nodeValue)
        assertEquals("1234", res.attributes.getNamedItem("size").nodeValue)
        assertTrue(res.attributes.getNamedItem("protocolInfo").nodeValue.startsWith("http-get:*:audio/flac:DLNA.ORG_OP=01"))
        assertEquals("0", doc.getElementsByTagName("InstanceID").item(0).textContent)
    }

    @Test fun demasSobres() {
        val d = Upnp.parseDescription(lg, "http://192.168.1.21:1471/")!!
        fun args(r: com.eddie.surco.data.SoapRequest): Map<String, String> {
            val action = xml(r.body).getElementsByTagName("s:Body").item(0).firstChild
            val out = LinkedHashMap<String, String>()
            var n = action.firstChild
            while (n != null) { out[n.nodeName] = n.textContent; n = n.nextSibling }
            return out
        }
        assertEquals(mapOf("InstanceID" to "0", "Speed" to "1"), args(Upnp.play(d)))
        assertEquals(mapOf("InstanceID" to "0"), args(Upnp.pause(d)))
        assertEquals(mapOf("InstanceID" to "0"), args(Upnp.stop(d)))
        assertEquals(mapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "01:02:05"), args(Upnp.seek(d, 3_725_400)))
        assertEquals(mapOf("InstanceID" to "0", "NextURI" to "http://a/b", "NextURIMetaData" to "<x/>"), args(Upnp.setNextUri(d, "http://a/b", "<x/>")))
        assertEquals(mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "100"), args(Upnp.setVolume(d, 140)!!))
        assertEquals(mapOf("InstanceID" to "0", "Channel" to "Master"), args(Upnp.getVolume(d)!!))
        assertEquals(mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "1"), args(Upnp.setMute(d, true)!!))
        assertTrue(Upnp.getPositionInfo(d).soapAction.endsWith("#GetPositionInfo\""))
        assertTrue(Upnp.getTransportInfo(d).soapAction.endsWith("#GetTransportInfo\""))
        assertTrue(Upnp.getVolume(d)!!.soapAction.startsWith("\"urn:schemas-upnp-org:service:RenderingControl:1#"))
    }

    @Test fun respuestasSoap() {
        val pos = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><Track>1</Track><TrackDuration>0:04:12</TrackDuration><TrackMetaData>&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;/DIDL-Lite&gt;</TrackMetaData><TrackURI>http://192.168.1.10:41000/m/abc.flac</TrackURI><RelTime>0:01:05.250</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount></u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        val ok = Upnp.parseSoap(pos) as SoapResult.Ok
        val p = Upnp.position(ok.values)
        assertEquals(65_250L, p.relMs)
        assertEquals(252_000L, p.durationMs)
        assertEquals("http://192.168.1.10:41000/m/abc.flac", p.trackUri)
        assertTrue(ok.values.getValue("TrackMetaData").startsWith("<DIDL-Lite"))
        // Sonos sin nada cargado: duración 0:00:00 y RelTime NOT_IMPLEMENTED.
        val empty = Upnp.position(mapOf("TrackDuration" to "0:00:00", "RelTime" to "NOT_IMPLEMENTED", "TrackURI" to ""))
        assertNull(empty.durationMs); assertNull(empty.relMs)

        val transport = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse></s:Body></s:Envelope>"""
        assertEquals("PAUSED_PLAYBACK", Upnp.transportState((Upnp.parseSoap(transport) as SoapResult.Ok).values))
        val vol = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><CurrentVolume>23</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"""
        assertEquals(23, Upnp.volume((Upnp.parseSoap(vol) as SoapResult.Ok).values))
        val empty2 = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body></s:Envelope>"""
        assertEquals(SoapResult.Ok(emptyMap()), Upnp.parseSoap(empty2))

        val fault = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals(SoapResult.Fault(701, "Transition not available"), Upnp.parseSoap(fault))
        assertEquals(-1, (Upnp.parseSoap("no es xml") as SoapResult.Fault).code)
    }

    @Test fun tiempos() {
        assertEquals(0L, Upnp.hmsToMs("0:00:00"))
        assertEquals(3_725_000L, Upnp.hmsToMs("01:02:05"))
        assertEquals(65_500L, Upnp.hmsToMs("0:01:05.5"))
        assertEquals(65_250L, Upnp.hmsToMs("0:01:05.250"))
        assertEquals(65_500L, Upnp.hmsToMs("0:01:05.1/2"))
        assertEquals(360_000_000L, Upnp.hmsToMs("100:00:00"))
        assertNull(Upnp.hmsToMs("NOT_IMPLEMENTED"))
        assertNull(Upnp.hmsToMs(""))
        assertNull(Upnp.hmsToMs("--:--:--"))
        assertNull(Upnp.hmsToMs("0:61:00"))
        assertEquals("00:00:00", Upnp.msToHms(-5))
        assertEquals("01:02:05", Upnp.msToHms(3_725_999))
        assertEquals("0:03:25.500", Upnp.didlDuration(205_500))
        for (ms in listOf(0L, 999L, 59_000L, 3_600_000L, 7_385_000L)) assertEquals(ms / 1000 * 1000, Upnp.hmsToMs(Upnp.msToHms(ms)))
    }

    @Test fun siguienteYFinal() {
        assertEquals(1, Upnp.nextIndex(0, 3, 0))
        assertNull(Upnp.nextIndex(2, 3, 0))
        assertEquals(0, Upnp.nextIndex(2, 3, 2))
        assertEquals(2, Upnp.nextIndex(2, 3, 1))
        assertNull(Upnp.nextIndex(0, 0, 2))
        assertEquals(RemoteStep.RENDERER_ADVANCED, Upnp.judge("PLAYING", "http://a/n.flac", "http://a/n.flac", true))
        assertEquals(RemoteStep.ENDED, Upnp.judge("STOPPED", "http://a/c.flac", "http://a/n.flac", true))
        assertEquals(RemoteStep.ENDED, Upnp.judge("NO_MEDIA_PRESENT", "", null, true))
        // Parado antes de haber sonado (recién cargada): no ha acabado nada.
        assertEquals(RemoteStep.NOTHING, Upnp.judge("STOPPED", "http://a/c.flac", null, false))
        assertEquals(RemoteStep.NOTHING, Upnp.judge("PLAYING", "http://a/c.flac", "http://a/n.flac", true))
        assertEquals(RemoteStep.NOTHING, Upnp.judge("TRANSITIONING", "", "http://a/n.flac", true))
    }

    @Test fun tiposMime() {
        assertEquals("audio/mpeg", Upnp.mimeForFormat("MP3"))
        assertEquals("audio/flac", Upnp.mimeForFormat("flac"))
        assertEquals("audio/mp4", Upnp.mimeForFormat("ALAC"))
        assertEquals("audio/ogg", Upnp.mimeForFormat("OPUS"))
        assertNull(Upnp.mimeForFormat("XYZ"))
        assertEquals("m4a", Upnp.extForMime("audio/mp4"))
        assertEquals("flac", Upnp.extForMime("audio/x-flac"))
        assertEquals("bin", Upnp.extForMime("application/octet-stream"))
        assertEquals("a &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;", Upnp.esc("a & <b> \"c\" 'd'"))
    }

    // ---------- De extremo a extremo ----------

    /**
     * Un renderizador DLNA falso en 127.0.0.1: contesta a M-SEARCH por UDP, sirve su descripción y
     * atiende los SOAP. Al recibir Play pide el archivo a la dirección que le dieron, como una tele.
     */
    private class FakeRenderer : AutoCloseable {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val actions: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var uri = ""
        @Volatile var nextUri = ""
        @Volatile var state = "NO_MEDIA_PRESENT"
        @Volatile var fetched: ByteArray? = null
        @Volatile var tail: ByteArray? = null
        @Volatile var volume = 20
        @Volatile var positionMs = 0L
        val base get() = "http://127.0.0.1:${server.localPort}"

        private val desc = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Tele de pruebas</friendlyName>
<manufacturer>Prueba</manufacturer><modelName>Smart TV</modelName><UDN>uuid:falsa</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>ctl/avt</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/ctl/rc</controlURL></service>
</serviceList></device></root>"""

        init {
            Thread { while (!server.isClosed) { val c = runCatching { server.accept() }.getOrNull() ?: break; Thread { handle(c) }.start() } }.apply { isDaemon = true }.start()
            Thread {
                val buf = ByteArray(2048)
                while (!udp.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    runCatching { udp.receive(p) }.onFailure { return@Thread }
                    val msg = String(p.data, 0, p.length)
                    if (!msg.startsWith("M-SEARCH") || !msg.contains("MediaRenderer")) continue
                    val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLocation: $base/desc.xml\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:falsa::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                    val b = reply.toByteArray()
                    udp.send(DatagramPacket(b, b.size, p.socketAddress))
                }
            }.apply { isDaemon = true }.start()
        }

        private fun readRequest(input: InputStream): Pair<Map<String, String>, String> {
            val head = ByteArrayOutputStream()
            var last = 0
            while (true) { val b = input.read(); if (b < 0) break; head.write(b); last = (last shl 8) or b; if (last == 0x0d0a0d0a) break }
            val lines = head.toString("UTF-8").split("\r\n")
            val h = HashMap<String, String>()
            h["_line"] = lines.first()
            for (l in lines.drop(1)) { val i = l.indexOf(':'); if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val n = h["content-length"]?.toInt() ?: 0
            val body = ByteArray(n); var off = 0
            while (off < n) { val r = input.read(body, off, n - off); if (r < 0) break; off += r }
            return h to String(body, Charsets.UTF_8)
        }

        private fun handle(c: Socket) = c.use {
            val (h, body) = readRequest(c.getInputStream())
            val line = h.getValue("_line")
            val out = c.getOutputStream()
            fun reply(code: Int, xml: String) {
                val b = xml.toByteArray()
                // La descripción va «chunked», como la mandan algunas teles.
                if (line.contains("desc.xml")) {
                    out.write("HTTP/1.1 $code OK\r\nContent-Type: text/xml\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    b.toList().chunked(100).forEach { ch -> out.write("${Integer.toHexString(ch.size)}\r\n".toByteArray()); out.write(ch.toByteArray()); out.write("\r\n".toByteArray()) }
                    out.write("0\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Internal Server Error"}\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray())
                    out.write(b)
                }
                out.flush()
            }
            if (line.startsWith("GET /desc.xml")) return@use reply(200, desc)
            val action = h["soapaction"].orEmpty().trim('"').substringAfter('#')
            actions += action
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(body)))
            fun arg(n: String) = doc.getElementsByTagName(n).item(0)?.textContent.orEmpty()
            assertEquals("0", arg("InstanceID"))
            fun ok(values: String = "") = reply(200, """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$values</u:${action}Response></s:Body></s:Envelope>""")
            when (action) {
                "SetAVTransportURI" -> {
                    // El DIDL tiene que ser XML válido una vez leído del sobre.
                    val didl = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(arg("CurrentURIMetaData"))))
                    assertEquals("Canción de prueba", didl.getElementsByTagName("dc:title").item(0).textContent)
                    uri = arg("CurrentURI"); state = "STOPPED"; ok()
                }
                "SetNextAVTransportURI" -> {
                    reply(500, """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""")
                }
                "Play" -> {
                    assertEquals("1", arg("Speed"))
                    // Como una tele: mira la cabecera y el final, y luego lo pide entero.
                    tail = HttpCall.request("GET", uri, listOf("Range" to "bytes=-4")).body
                    fetched = HttpCall.request("GET", uri, listOf("Range" to "bytes=0-", "getcontentFeatures.dlna.org" to "1")).body
                    state = "PLAYING"; ok()
                }
                "Pause" -> { state = "PAUSED_PLAYBACK"; ok() }
                "Stop" -> { state = "STOPPED"; ok() }
                "Seek" -> { assertEquals("REL_TIME", arg("Unit")); positionMs = Upnp.hmsToMs(arg("Target")) ?: -1; ok() }
                "GetTransportInfo" -> ok("<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>")
                "GetPositionInfo" -> ok("<Track>1</Track><TrackDuration>0:03:20</TrackDuration><TrackURI>${Upnp.esc(uri)}</TrackURI><RelTime>${Upnp.msToHms(positionMs)}</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime>")
                "SetVolume" -> { volume = arg("DesiredVolume").toInt(); ok() }
                "GetVolume" -> ok("<CurrentVolume>$volume</CurrentVolume>")
                else -> reply(500, "<x/>")
            }
        }

        override fun close() { server.close(); udp.close() }
    }

    @Test fun deExtremoAExtremo() {
        val song = ByteArray(150_001) { (it xor (it shr 8)).toByte() }
        val http = MiniHttp(InetAddress.getByName("127.0.0.1"))
        val port = http.start()
        FakeRenderer().use { tv ->
            // 1. Buscar: M-SEARCH por UDP (aquí directo al falso, en Android al grupo multicast).
            val found = ArrayList<String>()
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { s ->
                Upnp.search(s, InetSocketAddress("127.0.0.1", tv.udp.localPort), Upnp.mSearch(), 600) { found += it.location }
            }
            assertEquals(listOf("${tv.base}/desc.xml"), found)

            // 2. Leer su descripción (llega «chunked»).
            val desc = HttpCall.request("GET", found.single())
            assertEquals(200, desc.code)
            val d = must(Upnp.parseDescription(desc.text(), found.single()))
            assertEquals("Tele de pruebas", d.name)
            assertEquals("${tv.base}/ctl/avt", d.avTransportUrl)
            assertEquals(RendererKind.TV, d.kind)

            // 3. Servir la canción y decirle qué tocar.
            val url = "http://127.0.0.1:$port" + http.register(MiniHttp.Source.ofBytes(song, "audio/flac"), "flac")
            val meta = Upnp.didl(DidlItem("Canción de prueba", "Artista & Cía", "Disco", 200_000, "audio/flac", url, size = song.size.toLong()))
            assertEquals(SoapResult.Ok(emptyMap()), Upnp.send(Upnp.setUri(d, url, meta)))
            assertEquals(url, tv.uri)
            // SetNext no lo tiene: el fallo UPnP llega con su código para dejar de pedirlo.
            assertEquals(Upnp.ERR_INVALID_ACTION, (Upnp.send(Upnp.setNextUri(d, url, meta)) as SoapResult.Fault).code)

            // 4. Play: la tele pide el archivo al servidor del móvil y le llega entero.
            assertTrue(Upnp.send(Upnp.play(d)) is SoapResult.Ok)
            assertArrayEquals(song, tv.fetched)
            assertArrayEquals(song.copyOfRange(song.size - 4, song.size), tv.tail)

            // 5. Sondear, saltar, pausar, volumen.
            assertEquals("PLAYING", Upnp.transportState((Upnp.send(Upnp.getTransportInfo(d)) as SoapResult.Ok).values))
            assertTrue(Upnp.send(Upnp.seek(d, 95_000)) is SoapResult.Ok)
            val pos = Upnp.position((Upnp.send(Upnp.getPositionInfo(d)) as SoapResult.Ok).values)
            assertEquals(95_000L, pos.relMs)
            assertEquals(200_000L, pos.durationMs)
            assertEquals(url, pos.trackUri)
            assertEquals(RemoteStep.NOTHING, Upnp.judge("PLAYING", pos.trackUri, null, true))
            assertTrue(Upnp.send(Upnp.pause(d)) is SoapResult.Ok)
            assertEquals("PAUSED_PLAYBACK", tv.state)
            assertTrue(Upnp.send(Upnp.setVolume(d, 35)!!) is SoapResult.Ok)
            assertEquals(35, Upnp.volume((Upnp.send(Upnp.getVolume(d)!!) as SoapResult.Ok).values))
            assertTrue(Upnp.send(Upnp.stop(d)) is SoapResult.Ok)
            assertEquals(RemoteStep.ENDED, Upnp.judge(tv.state, url, null, true))
            assertEquals(
                listOf("SetAVTransportURI", "SetNextAVTransportURI", "Play", "GetTransportInfo", "Seek", "GetPositionInfo", "Pause", "SetVolume", "GetVolume", "Stop"),
                tv.actions.toList(),
            )
        }
        http.stop()
    }
}
