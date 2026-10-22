package eus.argos.paridad

import eus.argos.core.SceneBrain
import eus.argos.core.SceneScale
import eus.argos.core.ScaleSample
import eus.argos.core.Track

/**
 * Física plausible o ninguna.
 *
 * En el teléfono salió «corriendo #30 · 41,5 m/s» con el móvil apuntando a los
 * pies: una escala aprendida de una perspectiva imposible y una caja que
 * saltaba. Se exige:
 *  1. una perspectiva de cámara a 1,4 m (h = 1,2·y) NO da escala válida, y una
 *     de cámara a 5,7 m (h = 0,3·y + 20) sí, con esa altura deducida;
 *  2. una caja que salta 400 px en un frame no produce velocidad ni «corriendo»;
 *  3. alguien que corre de verdad a 4 m/s sí da «corriendo», y solo tras 1 s.
 */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }

    // 1. Altura de cámara deducida como control de calidad.
    val pies = SceneScale().fit((0 until 40).map { ScaleSample(300.0, 100.0 + it * 12, 1.2 * (100.0 + it * 12) + 5, "person") })
    pide("cámara a 1,4 m: escala no válida", !pies.valid)
    val calle = SceneScale().fit((0 until 40).map { ScaleSample(300.0, 260.0 + it * 11, 0.3 * (260.0 + it * 11) + 20, "person") })
    pide("cámara a 5,7 m: escala válida, altura %.2f m".format(calle.cameraHeightM),
         calle.valid && Math.abs(calle.cameraHeightM - 1.70 / 0.3) < 0.3)

    // 2 y 3. Un cerebro con escala aprendida de peatones en una calle.
    val brain = SceneBrain()
    val fps = 30.0
    var f = 0
    var nextId = 1
    fun frame(tracks: List<Track>) = brain.update(tracks, 3, f++ / fps, 1.0, 1.0, 1280, 720, 0.0, 0.0, true)
    fun persona(id: Int, cx: Double, by: Double): Track {
        val h = 0.3 * by + 20; val w = h * 0.35
        return Track(id, cx - w / 2, by - h, cx + w / 2, by).apply { hits = 10; klass = "person"; klassSrc = "det" }
    }
    for (k in 0 until 16) {
        val by = 260.0 + (k * 37) % 440
        val id = nextId++
        val v = 1.2 / fps * (0.3 * by + 20) / 1.70
        var x = 80.0
        while (x < 1200) { frame(listOf(persona(id, x, by))); x += v }
        repeat(5) { frame(emptyList()) }
    }
    pide("escala aprendida en la calle", brain.scale.valid)

    // 2. Un salto de caja.
    val idS = nextId++
    var x = 200.0; val by = 520.0
    val vAndar = 1.2 / fps * (0.3 * by + 20) / 1.70
    repeat(40) { frame(listOf(persona(idS, x, by))); x += vAndar }
    val antes = brain.events.count { it.kind == "corriendo" }
    var maxV = 0.0
    for (j in 0 until 20) {
        if (j == 0) x += 400.0 else x += vAndar
        frame(listOf(persona(idS, x, by))).firstOrNull()?.speedMs?.let { if (it.isFinite() && it > maxV) maxV = it }
    }
    pide("salto de 400 px: sin «corriendo» (velocidad máxima medida %.1f m/s)".format(maxV),
         brain.events.count { it.kind == "corriendo" } == antes && maxV < 2.2)
    repeat(5) { frame(emptyList()) }

    // 3. Alguien que corre de verdad.
    val idC = nextId++
    x = 100.0
    val vCorrer = 4.0 / fps * (0.3 * by + 20) / 1.70
    var tPrimero = Double.NaN
    val t0 = f / fps
    while (x < 1200) {
        frame(listOf(persona(idC, x, by))); x += vCorrer
        if (tPrimero.isNaN() && brain.events.any { it.kind == "corriendo" && it.id == idC }) tPrimero = f / fps - t0
    }
    pide("corredor a 4 m/s: «corriendo» tras %.2f s".format(tPrimero), tPrimero.isFinite() && tPrimero >= 1.0)

    println(if (fallos == 0) "FÍSICA PLAUSIBLE: CUMPLE" else "FÍSICA PLAUSIBLE: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
