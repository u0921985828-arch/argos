package eus.argos.paridad

import eus.argos.core.DetBox
import eus.argos.core.Engine
import eus.argos.core.SceneBrain

/**
 * La escena de extremo a extremo, con el núcleo y el cerebro de verdad.
 *
 * Cámara fija sobre un suelo en perspectiva (fuente 1280×720, análisis
 * 320×180). Pasan 14 peatones, uno tras otro, por filas distintas; su altura en
 * imagen sigue h = 0,3·y + 20, que con 1,70 m de estatura asumida da una escala
 * conocida. El detector los ve cada 6 frames. Al final uno se para 5 s.
 *
 * Se exige: escala válida y cercana a la verdadera, velocidad en m/s de un
 * peatón dentro de ±25 % de la real, evento «detenido» y 15 personas
 * distintas contadas.
 */
fun main() {
    val W = 320; val H = 180; val S = 4.0
    val e = Engine()
    val brain = SceneBrain()
    val fondo = ByteArray(W * H * 4).also {
        for (i in 0 until W * H) { val v = 80 + (i * 7919 % 17); it[i * 4] = v.toByte(); it[i * 4 + 1] = v.toByte(); it[i * 4 + 2] = v.toByte(); it[i * 4 + 3] = -1 }
    }
    val fps = 30.0
    var f = 0
    var velocidad = Double.NaN; var velocidadReal = Double.NaN
    fun paso(px: Double, by: Double) {
        // px, by en fuente: centro y pie de la persona
        val h = 0.3 * by + 20; val w = h * 0.35
        val d = fondo.copyOf()
        val x1 = ((px - w / 2) / S).toInt(); val x2 = ((px + w / 2) / S).toInt()
        val y1 = ((by - h) / S).toInt(); val y2 = (by / S).toInt()
        for (yy in maxOf(0, y1) until minOf(H, y2)) for (xx in maxOf(0, x1) until minOf(W, x2)) {
            val p = (yy * W + xx) * 4; d[p] = 225.toByte(); d[p + 1] = 210.toByte(); d[p + 2] = 195.toByte()
        }
        val det = if (f % 6 == 0) listOf(DetBox(px - w / 2, by - h, px + w / 2, by, "person", 0.9)) else null
        val r = e.analyze(d, W, H, (W * S).toInt(), (H * S).toInt(), det)
        val info = brain.update(e.tracker.tracks, e.tracker.minHits, f / fps, S, S,
                                (W * S).toInt(), (H * S).toInt(), 0.0, 0.0, true)
        info.firstOrNull { it.fromDet && it.speedMs.isFinite() }?.let { velocidad = it.speedMs }
        f++
    }
    // Calentar el fondo.
    repeat(70) { val r = e.analyze(fondo, W, H, (W * S).toInt(), (H * S).toInt()); brain.update(e.tracker.tracks, e.tracker.minHits, f / fps, S, S, 1280, 720, 0.0, 0.0, true); f++ }
    for (k in 0 until 14) {
        val by = 260.0 + (k * 37) % 440              // filas de 260 a 700
        var px = 80.0
        // 1,2 m/s reales a cualquier distancia: los del fondo avanzan menos
        // píxeles por frame, como en una calle de verdad.
        val v = 1.2 / fps * (0.3 * by + 20) / 1.70
        while (px < 1200) { paso(px, by); px += v }
        repeat(25) { val r = e.analyze(fondo, W, H, 1280, 720); brain.update(e.tracker.tracks, e.tracker.minHits, f / fps, S, S, 1280, 720, 0.0, 0.0, true); f++ }
    }
    // Último peatón: anda, y a mitad se para 5 s.
    val by = 520.0; var px = 100.0
    velocidad = Double.NaN
    val v = 1.2 / fps * (0.3 * by + 20) / 1.70
    while (px < 600) { paso(px, by); px += v }
    velocidadReal = 1.2
    val vAndando = velocidad
    repeat(150) { paso(px, by) }
    val sc = brain.scale
    val personas = brain.seen["person"]?.size ?: 0
    // Solo el último se para; nadie corre. Un evento falso cuesta la confianza
    // del que vigila igual que uno perdido.
    val ultimo = brain.seen["person"]?.max() ?: -1
    val detenido = brain.events.any { it.kind == "detenido" && it.id == ultimo }
    val falsos = brain.events.count { !(it.kind == "detenido" && it.id == ultimo) }
    println("eventos falsos: $falsos")
    println("escala ${if (sc.valid) "válida" else "NO válida"}: a ${"%.3f".format(sc.a)} (real 0,300) · b ${"%.1f".format(sc.b)} (real 20)")
    println("velocidad andando ${"%.2f".format(vAndando)} m/s (real ${"%.2f".format(velocidadReal)})")
    println("eventos: " + brain.events.joinToString { "${it.kind} #${it.id} ${it.detail}" }.take(200))
    println("personas distintas: $personas")
    val ok = sc.valid && Math.abs(sc.a - 0.3) < 0.05 &&
        vAndando.isFinite() && Math.abs(vAndando - velocidadReal) / velocidadReal < 0.25 &&
        detenido && falsos == 0 && personas == 15
    println(if (ok) "C4 escena viva: CUMPLE" else "C4 escena viva: NO")
    if (!ok) System.exit(1)
}
