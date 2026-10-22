package eus.argos.paridad.casos
import eus.argos.core.*
import kotlin.math.*

/**
 * Ataques a la compensación del movimiento de cámara, con un mundo bilineal
 * (rotación, ruido, deriva subpíxel) que la prueba de cámara en mano no cubre.
 * Cada caso imprime una línea; con `exigir`, falla si alguno se sale de lo
 * medido al cerrarlo. Lo que NO se arregla está acotado para que no empeore:
 * la deriva de 0,1 px/f (pasos enteros contra una deriva subpíxel) y la
 * escena oscura de contraste 30, que da lo mismo con y sin compensación.
 */

fun main(args: Array<String>) {
    val N = 220; val D = 70
    val tex = Mundo(1100, 800, bloques(1, 50, 150))
    val fallos = ArrayList<String>()
    val exigir = args.contains("exigir")
    fun pr(n: String, r: Res, nm: Int = N - D): Res { println("%-62s frames con falsos %3d/%d · falsos %5d · persona vista %3d · movida %3d · resets %d".format(n, r.framesFalsos, nm, r.falsos, r.vista, r.mov, r.resets)); return r }
    fun pide(n: String, ok: Boolean) { if (!ok) fallos.add(n) }
    val pan = { f: Int -> if (f < 60) Triple(500.0, 400.0, 0.0) else Triple(500.0 + (f - 60) * 1.4, 400.0 + 3 * sin((f - 60) * 0.35), 0.0) }
    // 0. reproducción del caso de la prueba (con bilineal)
    pr("paneo 1,4 px/f + temblor, sin objetos, ego", correr(tex, N, D, true, 0.0, pan)).let { r -> pide("paneo", r.framesFalsos <= 5) }
    pr("paneo 1,4 px/f + temblor, sin objetos, SIN ego", correr(tex, N, D, false, 0.0, pan))
    // 1. caso reservado: persona en sentido contrario al paneo
    val contra = { f: Int -> doubleArrayOf(900.0 - f * 1.6, 380.0, 22.0, 60.0) }
    pr("RESERVADO persona contra el paneo, ego", correr(tex, N, D, true, 0.0, pan, contra)).let { r -> pide("RESERVADO persona contra el paneo", r.framesFalsos <= 5 && r.vista >= 120) }
    val contraR = { f: Int -> doubleArrayOf(900.0 - f * 1.4, 380.0, 22.0, 60.0) }
    pr("RESERVADO persona contra paneo, misma velocidad, ego", correr(tex, N, D, true, 0.0, pan, contraR)).let { r -> pide("RESERVADO persona contra paneo", r.framesFalsos <= 5 && r.vista >= 120) }
    val conPan = { f: Int -> doubleArrayOf(300.0 + f * 1.4, 380.0, 22.0, 60.0) }
    pr("persona A FAVOR del paneo a su velocidad (quieta en pantalla)", correr(tex, N, D, true, 0.0, pan, conPan)).let { r -> pide("persona A FAVOR del paneo a su velocidad (quieta en pantalla)", r.framesFalsos <= 5 && r.vista >= 120) }
    // 2. cámara QUIETA, objeto grande moviéndose
    val quieta = { _: Int -> Triple(500.0, 400.0, 0.0) }
    val big = { f: Int -> if (f < 70) null else doubleArrayOf(150.0 + (f - 70) * 3.0, 230.0, 300.0, 220.0) }
    pr("QUIETA, objeto grande 300x220 a 3 px/f, ego", correr(tex, N, D, true, 0.0, quieta, null, big))
        .let { r -> pide("objeto grande con cámara fija", r.framesFalsos <= 5 && r.mov <= 10) }
    pr("QUIETA, objeto grande 300x220 a 3 px/f, SIN ego", correr(tex, N, D, false, 0.0, quieta, null, big))
    // 3. habitación oscura, poco contraste, ruido de sensor
    for (span in listOf(8, 16, 30)) for (sg in listOf(2.0, 4.0)) {
        val osc = Mundo(1100, 800, bloques(3, 12, span, 24))
        val conEgo = pr("OSCURA contraste $span ruido $sg, temblor de mano, ego", correr(osc, N, D, true, sg, { f -> Triple(500.0 + 2.5 * sin(f * 0.21) + 1.5 * sin(f * 0.53), 400.0 + 2.0 * sin(f * 0.31 + 1), 0.0) }))
        val sinEgo = pr("OSCURA contraste $span ruido $sg, temblor de mano, SIN ego", correr(osc, N, D, false, sg, { f -> Triple(500.0 + 2.5 * sin(f * 0.21) + 1.5 * sin(f * 0.53), 400.0 + 2.0 * sin(f * 0.31 + 1), 0.0) }))
        pide("oscura $span/$sg no peor que sin compensar", conEgo.framesFalsos <= sinEgo.framesFalsos)
    }
    // 4. giro (alabeo) de muñeca ±0,5° / ±1°
    for (deg in listOf(0.3, 0.6, 1.0)) {
        pr("ALABEO ±$deg° + temblor, ego", correr(tex, N, D, true, 0.0, { f -> Triple(500.0 + 2 * sin(f * 0.3), 400.0, Math.toRadians(deg) * sin(f * 0.25)) }))
            .let { r -> pide("alabeo $deg", r.framesFalsos <= 5) }
    }
    // 5. deriva lenta subpíxel (móvil «quieto» en la mano)
    for (v in listOf(0.1, 0.25, 0.5)) {
        pr("DERIVA $v px/f de fuente, ego", correr(tex, N, D, true, 0.0, { f -> Triple(500.0 + f * v, 400.0 + f * v * 0.5, 0.0) }))
            .let { r -> pide("deriva $v", r.framesFalsos <= (if (v < 0.2) 35 else 5)) }
        pr("DERIVA $v px/f de fuente, SIN ego", correr(tex, N, D, false, 0.0, { f -> Triple(500.0 + f * v, 400.0 + f * v * 0.5, 0.0) }))
    }
    // 6. Escena quieta plana (pared lisa) con una persona: ego no debe estorbar
    val lisa = Mundo(1100, 800) { _, _ -> 120 }
    val anda = { f: Int -> doubleArrayOf(300.0 + f * 1.5, 380.0, 22.0, 60.0) }
    pr("PARED LISA quieta, persona, ego", correr(lisa, N, D, true, 1.5, quieta, anda))
        .let { r -> pide("pared lisa", r.framesFalsos <= 5 && r.vista >= 120 && r.mov <= 10) }
    pr("PARED LISA quieta, persona, SIN ego", correr(lisa, N, D, false, 1.5, quieta, anda))
    // 7. textura periódica (persiana/baldosas) quieta, con persona
    val rayas = Mundo(1100, 800) { x, _ -> if ((x / 4) % 2 == 0) 60 else 180 }
    pr("RAYAS 8px quieta, persona, ego", correr(rayas, N, D, true, 2.0, quieta, anda))
        .let { r -> pide("rayas quieta", r.framesFalsos <= 5 && r.vista >= 120 && r.mov <= 10) }
    pr("RAYAS 8px quieta, persona, SIN ego", correr(rayas, N, D, false, 2.0, quieta, anda))
    pr("RAYAS 8px paneo, ego", correr(rayas, N, D, true, 2.0, pan)).let { r -> pide("rayas en paneo", r.framesFalsos <= 5) }
    if (fallos.isEmpty()) println("CASOS DE CÁMARA: todos dentro")
    else {
        println("CASOS DE CÁMARA: FALLAN " + fallos.joinToString(" · "))
        if (exigir) System.exit(1)
    }
}
