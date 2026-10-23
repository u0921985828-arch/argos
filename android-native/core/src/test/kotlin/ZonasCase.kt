package eus.argos.paridad

import eus.argos.core.Alertas
import eus.argos.core.Box
import eus.argos.core.Descubre
import eus.argos.core.Zona
import eus.argos.core.Zonas

/** Zonas vigiladas e ignoradas, avisos de entrada y búsqueda de cámaras en la wifi. */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val puerta = Zona("puerta", true, listOf(doubleArrayOf(0.4, 0.5), doubleArrayOf(0.6, 0.5), doubleArrayOf(0.6, 1.0), doubleArrayOf(0.4, 1.0)))
    val arbol = Zona("árbol", false, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.3, 0.0), doubleArrayOf(0.3, 0.4)))
    pide("punto dentro y fuera de un polígono", puerta.contiene(0.5, 0.7) && !puerta.contiene(0.7, 0.7) && arbol.contiene(0.2, 0.05) && !arbol.contiene(0.05, 0.3))
    val w = 1000; val h = 500
    fun caja(id: Int, cx: Double, pie: Double, k: String = "person", src: String = "det") = Box(id, k, 0, cx - 20, pie - 80, cx + 20, pie, src)
    val vistas = Zonas.filtra(listOf(caja(1, 150.0, 50.0, "forma", "forma"), caja(2, 500.0, 400.0)), w, h, listOf(puerta, arbol))
    pide("lo que pisa en la zona ignorada no sale", vistas.map { it.id } == listOf(2))
    val texto = Zonas.serializa(listOf(puerta, arbol))
    val leidas = Zonas.lee(texto)
    pide("las zonas se guardan y se leen", leidas.size == 2 && leidas[0].vigilar && !leidas[1].vigilar && leidas[1].nombre == "árbol" &&
        leidas[0].contiene(0.5, 0.7))
    pide("una zona con menos de 3 puntos no vale", Zonas.lee("vigilar\tx\t0.1,0.1 0.2,0.2").isEmpty())

    val al = Alertas(esperaS = 30.0)
    val zs = listOf(puerta)
    var t = 0.0
    val a1 = al.paso(listOf(caja(7, 200.0, 400.0)), w, h, zs, t); t += 1
    val a2 = al.paso(listOf(caja(7, 500.0, 400.0)), w, h, zs, t); t += 1      // entra
    val a3 = al.paso(listOf(caja(7, 510.0, 410.0)), w, h, zs, t); t += 1      // sigue dentro
    val a4 = al.paso(listOf(caja(7, 510.0, 410.0), caja(8, 520.0, 420.0)), w, h, zs, t); t += 1   // entra otro, demasiado pronto
    pide("avisa al entrar, una vez, y no repite antes de 30 s (${a1.size} ${a2.size} ${a3.size} ${a4.size})",
        a1.isEmpty() && a2.size == 1 && a2[0].zona == "puerta" && a2[0].clase == "person" && a3.isEmpty() && a4.isEmpty())
    val a5 = al.paso(listOf(caja(9, 500.0, 400.0, "vehicle")), w, h, zs, 40.0)
    pide("pasados 30 s, otro que entra sí avisa", a5.size == 1 && a5[0].clase == "vehicle")
    val a6 = Alertas().paso(listOf(caja(3, 500.0, 400.0, "forma", "forma")), w, h, zs, 0.0)
    pide("una mancha de movimiento no avisa", a6.isEmpty())

    val r = """<SOAP-ENV:Envelope><SOAP-ENV:Body><wsdd:ProbeMatches><wsdd:ProbeMatch>
        <wsdd:Types>dn:NetworkVideoTransmitter</wsdd:Types>
        <wsdd:Scopes>onvif://www.onvif.org/name/Tapo%20C245D onvif://www.onvif.org/hardware/C245D onvif://www.onvif.org/Profile/Streaming</wsdd:Scopes>
        <wsdd:XAddrs>http://[fe80::1]:2020/onvif/device_service http://192.168.1.50:2020/onvif/device_service</wsdd:XAddrs>
        </wsdd:ProbeMatch></wsdd:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
    val c = Descubre.lee(r)
    pide("cámara encontrada: IPv4, puerto ONVIF y nombre (${c?.ip} ${c?.nombre})",
        c != null && c.ip == "192.168.1.50" && c.dispositivo == "http://192.168.1.50:2020/onvif/device_service" && c.nombre == "Tapo C245D" && c.modelo == "C245D")
    pide("otra cosa que no es una respuesta de cámara: nada", Descubre.lee("<Hello/>") == null)
    pide("la pregunta lleva su identificador", "uuid:abc" in Descubre.pregunta("abc") && "NetworkVideoTransmitter" in Descubre.pregunta("x"))

    println(if (fallos == 0) "ZONAS: CUMPLE" else "ZONAS: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
