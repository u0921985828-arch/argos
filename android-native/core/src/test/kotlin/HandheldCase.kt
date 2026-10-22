package eus.argos.paridad

import eus.argos.core.Engine
import eus.argos.core.downscaleRgba

/**
 * Cámara en mano: la escena no se mueve, la cámara sí.
 *
 * Un mundo texturado de 900×640 visto por una ventana de 640×480 que, tras 60
 * frames quieta (el fondo se asienta), se desplaza en horizontal con un temblor
 * vertical, como un móvil sujeto con la mano. No hay NINGÚN objeto: cada pista
 * de «movimiento» que salga es falsa. Es lo que se vio en el teléfono sobre una
 * habitación a oscuras.
 *
 * Con `persona = true` hay además un peatón andando por la escena, que tiene
 * que seguir saliendo.
 */
object Mano {
    const val WW = 900; const val WH = 640; const val SW = 640; const val SH = 480
    val mundo: ByteArray by lazy {
        var s = 4242
        val m = ByteArray(WW * WH * 4)
        // Bloques de 12 px con gris aleatorio y un degradado suave: textura de
        // sobra para que un desplazamiento de un píxel se note en la resta.
        for (by in 0 until WH step 12) for (bx in 0 until WW step 12) {
            s = s * 1103515245 + 12345
            val v = 50 + ((s ushr 24) % 150)
            for (y in by until minOf(WH, by + 12)) for (x in bx until minOf(WW, bx + 12)) {
                val p = (y * WW + x) * 4
                val g = (v + (x + y) / 40).coerceAtMost(255)
                m[p] = g.toByte(); m[p + 1] = g.toByte(); m[p + 2] = (g * 9 / 10).toByte(); m[p + 3] = -1
            }
        }
        m
    }

    /** Desplazamiento de la cámara en el frame f, en píxeles del mundo. */
    fun camara(f: Int): Pair<Int, Int> {
        if (f < 60) return 100 to 60
        val t = f - 60
        val dx = 100 + Math.round(t * 1.4).toInt()                // paneo
        val dy = 60 + Math.round(3.0 * Math.sin(t * 0.35)).toInt() // temblor
        return dx.coerceAtMost(WW - SW) to dy
    }

    /** Persona en coordenadas del MUNDO (se mueve por la escena, no con la cámara). */
    fun persona(f: Int): IntArray = intArrayOf(260 + f, 250, 22, 60)

    fun frame(f: Int, conPersona: Boolean, out: ByteArray) {
        val (cx, cy) = camara(f)
        for (y in 0 until SH) System.arraycopy(mundo, ((cy + y) * WW + cx) * 4, out, y * SW * 4, SW * 4)
        if (conPersona) {
            val p = persona(f)
            for (y in p[1] until p[1] + p[3]) for (x in p[0] until p[0] + p[2]) {
                val sx = x - cx; val sy = y - cy
                if (sx < 0 || sy < 0 || sx >= SW || sy >= SH) continue
                val o = (sy * SW + sx) * 4
                out[o] = 230.toByte(); out[o + 1] = 215.toByte(); out[o + 2] = 200.toByte()
            }
        }
    }
}

fun main(args: Array<String>) {
    val conPersona = args.contains("persona")
    val e = Engine()
    e.egoMotion = !args.contains("sin")
    val src = ByteArray(Mano.SW * Mano.SH * 4)
    val small = ByteArray(320 * 240 * 4)
    var falsos = 0; var framesConFalsos = 0; var personaVista = 0
    for (f in 0 until 220) {
        Mano.frame(f, conPersona, src)
        downscaleRgba(src, Mano.SW * 4, Mano.SW, Mano.SH, small, 320, 240)
        val r = e.analyze(small, 320, 240, Mano.SW, Mano.SH)
        if (f < 70) continue
        val (cx, cy) = Mano.camara(f)
        val p = Mano.persona(f)
        val px = (p[0] - cx).toDouble(); val py = (p[1] - cy).toDouble()
        var aqui = 0; var vista = false
        for (b in r.boxes) {
            val sobrePersona = conPersona && b.x1 < px + p[2] && b.x2 > px && b.y1 < py + p[3] && b.y2 > py
            if (sobrePersona) vista = true else aqui++
        }
        if (vista) personaVista++
        falsos += aqui
        if (aqui > 0) framesConFalsos++
    }
    println("Cámara en mano${if (conPersona) " con persona" else ""}: 150 frames en movimiento · " +
            "$framesConFalsos con pistas falsas · $falsos pistas falsas en total" +
            if (conPersona) " · persona vista en $personaVista frames" else "")
    // Criterio C1 de la fase 4: ≤ 5 frames con pistas falsas y, con persona,
    // vista en ≥ 80 % de los 150 frames en movimiento.
    if (args.contains("exigir") && (framesConFalsos > 5 || (conPersona && personaVista < 120))) {
        println("CÁMARA EN MANO: FALLA"); System.exit(1)
    }
}
