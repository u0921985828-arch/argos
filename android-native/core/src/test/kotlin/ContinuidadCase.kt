package eus.argos.paridad

import eus.argos.core.Box
import eus.argos.core.Continuidad

/**
 * Coches que el núcleo pierde a ratos (solo los ve uno de cada cinco frames,
 * y a veces con otro número): con Continuidad salen en TODOS los frames, con
 * su número de siempre y en su sitio. Lentos, normales y rápidos.
 */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val w = 320; val h = 180
    val rnd = java.util.Random(5)
    val fondo = IntArray(w * h) { 90 + rnd.nextInt(20) }
    fun imagen(cx: Double, cy: Double, visible: Boolean): ByteArray {
        val a = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            var v = fondo[y * w + x] + rnd.nextInt(5) - 2
            if (visible && Math.abs(x - cx) < 7 && Math.abs(y - cy) < 4) v = if ((x + y) % 3 == 0) 30 else 200   // coche con textura
            val k = (y * w + x) * 4; a[k] = v.toByte(); a[k + 1] = v.toByte(); a[k + 2] = v.toByte(); a[k + 3] = -1
        }
        return a
    }
    for ((nombre, vel) in listOf("lento" to 0.6, "normal" to 3.0, "rápido" to 7.0)) {
        val c = Continuidad()
        var vistos = 0; var mismoId = 0; var errMax = 0.0
        val n = 30
        for (f in 0 until n) {
            val cx = 40 + vel * f; val cy = 90.0
            // El núcleo solo lo da uno de cada cinco frames, y a partir del 15 con otro número.
            val nucleo = if (f % 5 == 0) listOf(Box(if (f < 15) 7 else 19, "vehicle", 0, (cx - 7) * 2, (cy - 4) * 2, (cx + 7) * 2, (cy + 4) * 2, "det")) else emptyList()
            val sale = c.paso(nucleo, imagen(cx, cy, true), w, h, 2.0, f / 15.0)
            val b = sale.firstOrNull { it.klass == "vehicle" } ?: continue
            vistos++; if (b.id == 7) mismoId++
            errMax = maxOf(errMax, Math.abs((b.x1 + b.x2) / 4 - cx))
        }
        pide("coche $nombre: sale en $vistos/$n frames, con su número en $mismoId, error máx %.1f px".format(errMax),
            vistos == n && mismoId == n && errMax <= 3.0)
    }
    // Cuando desaparece, se suelta enseguida (no se inventa un coche).
    val c = Continuidad()
    c.paso(listOf(Box(3, "vehicle", 0, 66.0, 172.0, 94.0, 188.0, "det")), imagen(40.0, 90.0, true), w, h, 2.0, 0.0)
    var restos = 0
    for (f in 1..8) restos += c.paso(emptyList(), imagen(40.0, 90.0, false), w, h, 2.0, f / 15.0).size
    pide("coche que desaparece: se suelta en unos frames ($restos cajas de más)", restos <= 4 && c.seguidos == 0)
    // Las manchas de movimiento no se continúan: solo lo que dio el detector.
    val m = Continuidad()
    m.paso(listOf(Box(1, "car", 0, 10.0, 10.0, 30.0, 30.0, "forma")), imagen(10.0, 10.0, true), w, h, 2.0, 0.0)
    pide("una mancha de movimiento no se sigue", m.paso(emptyList(), imagen(10.0, 10.0, true), w, h, 2.0, 0.1).isEmpty())
    println(if (fallos == 0) "CONTINUIDAD: CUMPLE" else "CONTINUIDAD: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
