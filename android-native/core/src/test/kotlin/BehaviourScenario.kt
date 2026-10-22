package eus.argos.paridad

import eus.argos.core.DetBox
import eus.argos.core.Engine
import eus.argos.core.SceneBrain

/**
 * El comportamiento aprendido, de extremo a extremo y con el núcleo de verdad.
 *
 * Cámara fija. Doce peatones a distintas distancias para que se aprenda la
 * escala; luego treinta por la misma acera de izquierda a derecha, que es lo
 * normal ahí; y al final uno en sentido contrario. Se exige que el último
 * salga como «inusual» o «anómalo», con el motivo de la dirección, y que
 * ninguno de los normales lo haga.
 */
fun main() {
    val W = 320; val H = 180; val S = 4.0; val fps = 30.0
    val e = Engine(); val brain = SceneBrain()
    val fondo = ByteArray(W * H * 4).also {
        for (i in 0 until W * H) { val v = 80 + (i * 7919 % 17); it[i * 4] = v.toByte(); it[i * 4 + 1] = v.toByte(); it[i * 4 + 2] = v.toByte(); it[i * 4 + 3] = -1 }
    }
    var f = 0
    fun tick(d: ByteArray, det: List<DetBox>?) {
        e.analyze(d, W, H, 1280, 720, det)
        brain.update(e.tracker.tracks, e.tracker.minHits, f / fps, S, S, 1280, 720, 0.0, 0.0, true)
        f++
    }
    fun anda(by: Double, desde: Double, hasta: Double) {
        val h = 0.3 * by + 20; val w = h * 0.35
        val v = 1.2 / fps * h / 1.70 * (if (hasta > desde) 1 else -1)
        var px = desde
        while (if (v > 0) px < hasta else px > hasta) {
            val d = fondo.copyOf()
            val x1 = ((px - w / 2) / S).toInt(); val x2 = ((px + w / 2) / S).toInt()
            val y1 = ((by - h) / S).toInt(); val y2 = (by / S).toInt()
            for (yy in maxOf(0, y1) until minOf(H, y2)) for (xx in maxOf(0, x1) until minOf(W, x2)) {
                val p = (yy * W + xx) * 4; d[p] = 225.toByte(); d[p + 1] = 210.toByte(); d[p + 2] = 195.toByte()
            }
            tick(d, if (f % 6 == 0) listOf(DetBox(px - w / 2, by - h, px + w / 2, by, "person", 0.9)) else null)
            px += v
        }
        repeat(25) { tick(fondo, null) }
    }
    repeat(70) { tick(fondo, null) }
    for (k in 0 until 12) anda(260.0 + (k * 37) % 440, 80.0, 1200.0)
    val antes = brain.events.size
    for (k in 0 until 30) anda(500.0, 80.0, 1200.0)
    val falsos = brain.events.take(brain.events.size - antes).count { it.kind == "inusual" || it.kind == "anómalo" }
    val m = brain.behaviour!!.maturity()
    anda(500.0, 1200.0, 80.0)
    val ultimo = brain.events.firstOrNull { it.kind == "inusual" || it.kind == "anómalo" }
    println("escala ${if (brain.scale.valid) "válida" else "NO válida"} · celdas maduras ${m.cellsMature} (con flujo ${m.directional})")
    println("normales marcados como raros: $falsos de 30")
    println("contramano: " + (ultimo?.let { "${it.kind} #${it.id} · ${it.detail}" } ?: "SIN EVENTO"))
    val ok = brain.scale.valid && falsos == 0 && ultimo != null && ultimo.detail.contains("contra el flujo")
    println(if (ok) "comportamiento vivo: CUMPLE" else "comportamiento vivo: NO")
    if (!ok) System.exit(1)
}
