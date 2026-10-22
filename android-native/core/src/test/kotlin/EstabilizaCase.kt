package eus.argos.paridad

import eus.argos.core.Estabilizador
import java.util.Random

/**
 * Estabilizado en pantalla. Se simula el camino de la cámara a 25 fps: un
 * balanceo de mano de 2 Hz y ±6 px, pasos de píxel entero de 2 px (como da el
 * estimador), y 50 ms de retraso del análisis. Lo que se mide es dónde cae en
 * pantalla un punto quieto de la escena, con y sin estabilizar:
 *  - el balanceo (1-3 Hz) baja al menos un 50 %;
 *  - un paneo de 3 px por frame durante 4 s se sigue: el desplazamiento nunca
 *    pasa del límite y no se queda pegado;
 *  - con la cámara quieta, la vista no se mueve.
 */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val fps = 25.0; val w = 640
    val rnd = Random(3)
    fun camino(t: Double) = 6.0 * Math.sin(2 * Math.PI * 2.0 * t) + 2.5 * Math.sin(2 * Math.PI * 1.1 * t + 1)
    // Balanceo
    val e = Estabilizador()
    val sin = ArrayList<Double>(); val con = ArrayList<Double>()
    for (i in 0 until 250) {
        val t = i / fps
        val real = camino(t)
        val medido = Math.round(camino(t - 0.05) / 2.0) * 2.0      // retraso y pasos de 2 px
        val (dx, _) = e.paso(medido, 0.0, w)
        if (i > 50) { sin.add(-real); con.add(-real + dx) }
    }
    fun rms(x: List<Double>): Double { val m = x.average(); return Math.sqrt(x.sumOf { (it - m) * (it - m) } / x.size) }
    pide("balanceo de 2 Hz: %.2f px → %.2f px".format(rms(sin), rms(con)), rms(con) < rms(sin) * 0.5)
    // Paneo
    val p = Estabilizador()
    var maxD = 0.0; var finalD = 0.0
    for (i in 0 until 100) { val (dx, _) = p.paso(i * 3.0, 0.0, w); maxD = maxOf(maxD, Math.abs(dx)); finalD = dx }
    for (i in 0 until 50) { val (dx, _) = p.paso(300.0, 0.0, w); finalD = dx }
    pide("paneo: desplazamiento máximo %.1f px (límite %.0f)".format(maxD, 0.05 * w), maxD <= 0.05 * w + 1e-9)
    pide("tras el paneo vuelve al centro: %.2f px".format(finalD), Math.abs(finalD) < 1.0)
    // Quieta
    val q = Estabilizador()
    var mq = 0.0
    for (i in 0 until 100) { val (dx, dy) = q.paso(12.0, -4.0, w); mq = maxOf(mq, Math.abs(dx), Math.abs(dy)) }
    pide("cámara quieta: la vista no se mueve (%.3f px)".format(mq), mq < 1e-9)
    // Giro de muñeca con el giroscopio a 200 Hz: temblor de ±1,2° a 3 Hz sobre
    // una inclinación lenta de 10° en 5 s. El temblor visto en pantalla (giro
    // real menos corrección, sin la tendencia lenta) baja más de un 70 % y la
    // inclinación a propósito no se corrige.
    run {
        val g = eus.argos.core.EstabilizaGiro()
        val dt = 1 / 200.0
        fun real(t: Double) = 1.2 * Math.sin(2 * Math.PI * 3 * t) + 2.0 * t
        fun dreal(t: Double) = Math.toRadians(1.2 * 2 * Math.PI * 3 * Math.cos(2 * Math.PI * 3 * t) + 2.0)
        val sin = ArrayList<Double>(); val con = ArrayList<Double>()
        var corrFinal = 0.0
        for (i in 0 until 1000) {
            val t = i * dt
            val c = g.paso(dreal(t), dt)
            if (i > 300) { sin.add(real(t) - 2.0 * t); con.add(real(t) - c - 2.0 * t) }
            corrFinal = c
        }
        pide("giro de muñeca de 3 Hz: %.2f° → %.2f°".format(rms(sin), rms(con)), rms(con) < rms(sin) * 0.3)
        pide("inclinación a propósito no se corrige (al final %.2f°)".format(corrFinal - 1.2 * Math.sin(2 * Math.PI * 3 * 999 * dt)),
             Math.abs(corrFinal - 1.2 * Math.sin(2 * Math.PI * 3 * 999 * dt)) < 1.5)
        var maxC = 0.0
        val h = eus.argos.core.EstabilizaGiro()
        for (i in 0 until 400) maxC = maxOf(maxC, Math.abs(h.paso(Math.toRadians(90.0), dt)))
        pide("giro brusco de 90°/s: corrección limitada a %.1f°".format(maxC), maxC <= 2.0 + 1e-9)
        pide("ampliación para ±2° en 9:16: %.3f".format(g.zoom(16 / 9.0)), g.zoom(16 / 9.0) in 1.05..1.07)
    }
    println(if (fallos == 0) "ESTABILIZADO: CUMPLE" else "ESTABILIZADO: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
