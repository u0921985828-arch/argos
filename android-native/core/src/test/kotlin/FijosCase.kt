package eus.argos.paridad

import eus.argos.core.AutoZoom
import eus.argos.core.Congelada
import eus.argos.core.DetBox
import eus.argos.core.Fijos

/**
 * Lo que se aprendió mirando 110 cámaras públicas: los rótulos sobreimpresos
 * no son objetos, una cámara colgada no es una escena quieta, y con pocos
 * objetos el zoom automático no cambiaba nunca.
 */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val h = 240
    fun caja(x: Double, y: Double, w: Double, hh: Double, k: String = "vehicle", s: Double = 0.45) = DetBox(x, y, x + w, y + hh, k, s)

    // Una letra del rótulo de arriba, vista en 4 de cada 5 pasadas (una cada 0,5 s).
    val f = Fijos()
    val letra = caja(100.0, 6.0, 12.0, 16.0)
    var antes = 0; var despues = 0
    for (i in 0 until 400) {
        val t = i * 0.5
        val dets = if (i % 5 != 0) listOf(letra) else emptyList()
        val sale = f.filtra(dets, h, t)
        if (dets.isNotEmpty()) { if (t < 110) antes += sale.size else if (t > 125) despues += sale.size }
    }
    pide("rótulo: sale mientras aprende ($antes) y deja de salir a los 2 min ($despues)", antes > 150 && despues == 0)

    // Un coche que cruza la franja de arriba: nunca se queda fijo.
    val g = Fijos()
    var vistos = 0
    for (i in 0 until 600) {
        val t = i * 0.5
        val x = (i * 7) % 300.0
        vistos += g.filtra(listOf(caja(x, 8.0, 20.0, 14.0)), h, t).size
    }
    pide("coche que pasa por la franja: siempre sale ($vistos/600), ninguna plaza fija", vistos == 600 && g.fijas == 0)

    // Un coche aparcado en el centro: es un coche.
    val c = Fijos()
    var aparcado = 0
    for (i in 0 until 600) aparcado += c.filtra(listOf(caja(150.0, 120.0, 30.0, 18.0)), h, i * 0.5).size
    pide("coche aparcado en el centro: sale siempre ($aparcado/600)", aparcado == 600)

    // Un coche grande abajo, cerca de la cámara, parado mucho rato: tampoco es una letra.
    val b = Fijos()
    var grande = 0
    for (i in 0 until 600) grande += b.filtra(listOf(caja(40.0, 205.0, 90.0, 34.0)), h, i * 0.5).size
    pide("vehículo grande en la franja de abajo: sale siempre ($grande/600)", grande == 600)

    // Lo aprendido se guarda por cámara: la vez siguiente, fuera desde el principio.
    val texto = f.serializa(200.0)
    val otra = Fijos().apply { lee(texto, 1000.0) }
    pide("rótulo aprendido: en la sesión siguiente no sale nunca", otra.filtra(listOf(letra), h, 1000.5).isEmpty())
    pide("lo de hace más de 30 días no vuelve", Fijos().apply { lee(f.serializa(200.0 + 31 * 86400.0), 0.0) }.fijas == 0)

    // Cámara colgada: la misma imagen una y otra vez.
    val img = ByteArray(320 * 240 * 4) { (it * 31 % 251).toByte() }
    val cg = Congelada()
    var t = 0.0
    repeat(60) { cg.paso(img, t); t += 0.2 }
    val tras12 = cg.congelada
    val otra2 = img.copyOf().also { for (i in 0 until it.size step 9) it[i] = (it[i] + 40).toByte() }
    cg.paso(otra2, t)
    pide("imagen congelada: se nota a los 10 s ($tras12) y se olvida al cambiar (${!cg.congelada})", tras12 && !cg.congelada)
    val viva = Congelada(); var tv = 0.0; var alguna = false
    repeat(80) { k -> val im = img.copyOf().also { a -> a[(k * 997) % a.size] = (k * 13).toByte(); for (i in 0 until a.size step (40 + k)) a[i] = (a[i] + k).toByte() }
        alguna = alguna || viva.paso(im, tv); tv += 0.2 }
    pide("una escena que cambia no es una cámara colgada", !alguna)

    // Zoom automático con pocos objetos: 1 coche en cuadro entero, 2 con teselas.
    val az = AutoZoom()
    repeat(60) { val z = az.next(); az.report(z, List(if (z == 0.0) 1 else 2) { caja(it * 50.0, 100.0, 10.0, 8.0, s = 0.5) }) }
    pide("pocos objetos: una ventaja pequeña y constante lleva a teselas (modo ${az.mode})", az.mode == 2.0)
    val az2 = AutoZoom()
    repeat(60) { val z = az2.next(); az2.report(z, List(if (z == 0.0) 4 else 1) { caja(it * 50.0, 100.0, 60.0, 50.0, s = 0.8) }) }
    pide("objetos grandes: sigue en cuadro entero (modo ${az2.mode})", az2.mode == 0.0)
    val az3 = AutoZoom().apply { empiezaEn(2.0) }
    pide("lo aprendido de la cámara: empieza en teselas", az3.mode == 2.0)

    println(if (fallos == 0) "FIJOS: CUMPLE" else "FIJOS: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
