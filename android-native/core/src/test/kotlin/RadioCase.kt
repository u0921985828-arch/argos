package eus.argos.paridad

import eus.argos.core.BleAd
import eus.argos.core.CampoRadio
import eus.argos.core.Clase
import eus.argos.core.Clasificador
import eus.argos.core.RemoteId
import eus.argos.core.VigiaGnss
import java.util.Random

/**
 * El módulo de radio, sin aparato: anuncios BLE construidos byte a byte según
 * las especificaciones públicas, un dron con Remote ID y un cielo GNSS
 * sintético con interferencia y suplantación.
 *
 * Caso reservado: un paquete de mensajes de Remote ID (tipo 0xF) con la
 * posición del PILOTO dentro. Se tiene que leer el dron y NO el piloto.
 */
private fun ad(vararg estructuras: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(2, 0x01, 0x06))                  // flags
    for (e in estructuras) out.write(e)
    return out.toByteArray()
}
private fun estructura(tipo: Int, datos: ByteArray) = byteArrayOf((datos.size + 1).toByte(), tipo.toByte()) + datos
private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte())
private fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
private fun fabricante(id: Int, datos: ByteArray) = estructura(0xFF, le16(id) + datos)
private fun servicio(uuid: Int, datos: ByteArray) = estructura(0x16, le16(uuid) + datos)
private fun bytes(n: Int, v: Int = 0xAB) = ByteArray(n) { v.toByte() }

fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    fun clase(b: ByteArray) = Clasificador.clase(BleAd.parse(b))

    // 1. Firmas.
    pide("Find My separado (0x12, 25 bytes)", clase(ad(fabricante(0x004C, byteArrayOf(0x12, 0x19) + bytes(25)))) == Clase.FIND_MY_SEPARADO)
    pide("Find My junto al dueño (0x12, 2 bytes)", clase(ad(fabricante(0x004C, byteArrayOf(0x12, 0x02, 0x00, 0x01)))) == Clase.FIND_MY)
    pide("AirPods (0x07) no es un rastreador", clase(ad(fabricante(0x004C, byteArrayOf(0x07, 0x19) + bytes(25)))) == Clase.OTRO)
    pide("Tile (servicio 0xFEED)", clase(ad(servicio(0xFEED, bytes(10)))) == Clase.TILE)
    pide("Galaxy SmartTag (0xFD5A)", clase(ad(servicio(0xFD5A, bytes(20)))) == Clase.SMARTTAG)
    pide("red Encontrar de Google (0xFEAA, trama 0x41)", clase(ad(servicio(0xFEAA, byteArrayOf(0x41) + bytes(20)))) == Clase.GOOGLE)
    pide("baliza Eddystone UID (0xFEAA, trama 0x00) no es un rastreador", clase(ad(servicio(0xFEAA, byteArrayOf(0x00) + bytes(17)))) == Clase.OTRO)
    pide("estándar DULT (0xFCB2)", clase(ad(servicio(0xFCB2, bytes(8)))) == Clase.DULT)
    pide("anuncio truncado no revienta", clase(byteArrayOf(0x1A, 0xFF.toByte(), 0x4C)) == Clase.OTRO)
    pide("anuncio vacío no revienta", clase(ByteArray(0)) == Clase.OTRO)

    // 2. Remote ID. Basic ID: tipo 0, id_type 1 (serie), ua_type 2 (multirrotor).
    val serie = "1581F5FKD223S00AB123"
    val basic = byteArrayOf(0x02, (1 shl 4 or 2).toByte()) + serie.toByteArray(Charsets.US_ASCII) + ByteArray(3)
    // Location: tipo 1, flags (EW=0, mult=0), dir 90, vel 50·0,25 = 12,5 m/s.
    val lat = 43.2630; val lon = -2.9350
    val location = byteArrayOf(0x12, 0x00, 90, 50, 0) + le32(Math.round(lat * 1e7).toInt()) + le32(Math.round(lon * 1e7).toInt()) +
        le16(0) + le16(((120 + 1000) / 0.5).toInt()) + ByteArray(8)
    // System: tipo 4, con la POSICIÓN DEL PILOTO, que no se debe leer.
    val pilotoLat = 43.3000; val pilotoLon = -2.9000
    val system = byteArrayOf(0x42, 0x00) + le32(Math.round(pilotoLat * 1e7).toInt()) + le32(Math.round(pilotoLon * 1e7).toInt()) + ByteArray(15)
    // Operator ID: tipo 5, tampoco.
    val operador = byteArrayOf(0x52, 0x00) + "ESP-OP-9999999".toByteArray() + ByteArray(9)
    check(basic.size == 25 && location.size == 25 && system.size == 25 && operador.size == 25)

    val suelto = RemoteId.decodifica(byteArrayOf(0x0D, 0x01) + location)
    pide("Location suelto: 43,2630 / -2,9350, 120 m, 12,5 m/s, rumbo 90",
         suelto != null && Math.abs(suelto.lat - lat) < 1e-6 && Math.abs(suelto.lon - lon) < 1e-6 &&
         Math.abs(suelto.alturaM - 120) < 0.6 && Math.abs(suelto.velocidadMs - 12.5) < 0.01 && suelto.rumbo == 90.0)
    val paquete = byteArrayOf(0x0D, 0x07, 0xF2.toByte(), 25, 4) + basic + location + system + operador
    val d = RemoteId.decodifica(paquete)
    pide("RESERVADO paquete 0xF: serie $serie, multirrotor, posición del dron",
         d != null && d.serie == serie && d.tipo == "multirrotor" && Math.abs(d.lat - lat) < 1e-6)
    pide("RESERVADO la posición del piloto no aparece en ningún campo",
         d != null && listOf(d.lat, d.lon).none { Math.abs(it - pilotoLat) < 1e-3 || Math.abs(it - pilotoLon) < 1e-3 } &&
         d.serie?.contains("OP") != true)
    pide("anuncio de dron como DRON", clase(ad(servicio(0xFFFA, paquete))) == Clase.DRON)

    // 3. Campo: un rastreador que se queda, uno de paso, y la dirección que no sale.
    val campo = CampoRadio()
    val sep = BleAd.parse(ad(fabricante(0x004C, byteArrayOf(0x12, 0x19) + bytes(25))))
    val junto = BleAd.parse(ad(fabricante(0x004C, byteArrayOf(0x12, 0x02, 0x00, 0x01))))
    var t = 0.0
    var avisoEn = Double.NaN
    while (t <= 11 * 60) {
        campo.oye("AA:BB:CC:DD:EE:01", -70, sep, t)
        campo.oye("AA:BB:CC:DD:EE:02", -60, junto, t)
        if (avisoEn.isNaN() && campo.avisos.isNotEmpty()) avisoEn = t
        t += 5
    }
    pide("rastreador separado 11 min: un aviso a los 10 min (a los %.0f s)".format(avisoEn),
         campo.avisos.size == 1 && avisoEn in 600.0..610.0)
    pide("accesorio junto a su dueño 11 min: sin aviso", campo.avisos.none { it.texto.contains("junto") })
    val e = campo.estado(t)
    pide("estado: 2 aparatos, 1 rastreador, el más largo ~11 min",
         e.aparatos == 2 && e.porClase[Clase.FIND_MY_SEPARADO] == 1 && e.rastreadorMasLargoS >= 600)
    val otro = CampoRadio()
    pide("la sal es por sesión: la misma dirección da otro resumen", campo.clave("AA:BB:CC:DD:EE:01") != otro.clave("AA:BB:CC:DD:EE:01"))
    pide("ningún texto lleva la dirección",
         campo.avisos.none { it.texto.contains("AA:BB", ignoreCase = true) } && campo.clave("AA:BB:CC:DD:EE:01").length == 16)
    // Uno que viene y va (hueco de 3 min) no acumula.
    val ida = CampoRadio()
    t = 0.0
    while (t <= 30 * 60) { if ((t / 60).toInt() % 8 < 5) ida.oye("11:22:33:44:55:66", -80, sep, t); t += 5 }
    pide("rastreador intermitente (5 min sí, 3 no): sin aviso", ida.avisos.isEmpty())
    pide("al pasar 2 min sin oírse se olvida", ida.estado(t + 200).aparatos == 0)
    val dr = CampoRadio()
    dr.oye("D0:0D:00:00:00:01", -75, BleAd.parse(ad(servicio(0xFFFA, byteArrayOf(0x0D, 0x01) + basic))), 1.0)
    dr.oye("D0:0D:00:00:00:01", -75, BleAd.parse(ad(servicio(0xFFFA, byteArrayOf(0x0D, 0x02) + location))), 1.5)
    val de = dr.estado(2.0)
    pide("dron: Basic ID y Location en anuncios distintos se juntan",
         de.drones.size == 1 && de.drones[0].serie == serie && Math.abs(de.drones[0].alturaM - 120) < 0.6 && dr.avisos.size == 1)

    // 4. GNSS.
    val rnd = Random(3)
    val cielo = DoubleArray(10) { 26.0 + it * 2.0 }          // 26..44 dB-Hz, alturas distintas
    fun epoca(g: VigiaGnss, ts: Double, f: (Int, Double) -> Double) =
        g.epoca(cielo.indices.map { VigiaGnss.Sat(it, f(it, cielo[it]) + rnd.nextGaussian() * 0.7) }, ts)
    var g = VigiaGnss()
    var ts = 0.0
    repeat(300) { epoca(g, ts) { _, c -> c }; ts += 1 }
    pide("cielo normal 5 min: sin avisos, estado NORMAL", g.avisos.isEmpty() && g.estado == VigiaGnss.Estado.NORMAL)
    repeat(120) { epoca(g, ts) { _, c -> c - 3 }; ts += 1 }
    pide("lluvia (-3 dB): sin avisos", g.avisos.isEmpty())
    repeat(60) { epoca(g, ts) { i, c -> if (i == 9) 0.0 else c }; ts += 1 }
    pide("un satélite tapado: sin avisos", g.avisos.isEmpty())
    var cuando = Double.NaN; val tIni = ts
    repeat(30) {
        if (epoca(g, ts) { _, c -> c - 16 } == VigiaGnss.Estado.INTERFERENCIA && cuando.isNaN()) cuando = ts - tIni
        ts += 1
    }
    pide("inhibidor (-16 dB): interferencia a los %.0f s, un aviso".format(cuando),
         cuando in 4.0..7.0 && g.avisos.size == 1 && g.avisos[0].contains("interferencia"))
    repeat(20) { epoca(g, ts) { _, c -> c }; ts += 1 }
    pide("se va el inhibidor: vuelve a NORMAL", g.estado == VigiaGnss.Estado.NORMAL)
    cuando = Double.NaN; val tIni2 = ts
    repeat(30) {
        val st = g.epoca((0 until 8).map { VigiaGnss.Sat(it, 41.0 + rnd.nextGaussian() * 0.3) }, ts)
        if (st == VigiaGnss.Estado.SUPLANTACION && cuando.isNaN()) cuando = ts - tIni2
        ts += 1
    }
    pide("suplantación (8 satélites a 41 ± 0,3 dB): aviso a los %.0f s".format(cuando),
         cuando in 9.0..12.0 && g.avisos.size == 2 && g.avisos[1].contains("suplantación"))
    // Con el inhibidor ya puesto al empezar no hay cambio que avisar.
    g = VigiaGnss(); ts = 0.0
    repeat(300) { epoca(g, ts) { _, c -> c - 16 }; ts += 1 }
    pide("inhibidor desde el principio: no se inventa un cambio", g.avisos.isEmpty())

    println(if (fallos == 0) "RADIO: CUMPLE" else "RADIO: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
