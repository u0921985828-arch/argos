package eus.argos.paridad.casos
import eus.argos.core.*
import kotlin.math.*

/** Mundo de luma con muestreo bilineal y rotación. */
class Mundo(val WW: Int, val WH: Int, val gen: (Int, Int) -> Int) {
    val L = FloatArray(WW * WH).also { for (y in 0 until WH) for (x in 0 until WW) it[y * WW + x] = gen(x, y).toFloat() }
    fun at(x: Double, y: Double): Float {
        val xi = floor(x).toInt().coerceIn(0, WW - 2); val yi = floor(y).toInt().coerceIn(0, WH - 2)
        val fx = (x - xi).toFloat().coerceIn(0f, 1f); val fy = (y - yi).toFloat().coerceIn(0f, 1f)
        val a = L[yi * WW + xi]; val b = L[yi * WW + xi + 1]; val c = L[(yi + 1) * WW + xi]; val d = L[(yi + 1) * WW + xi + 1]
        return (a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy)
    }
}

/** Ventana SWxSH centrada en (cx,cy) del mundo, girada `rot` rad, con ruido sigma. */
fun render(m: Mundo, cx: Double, cy: Double, rot: Double, SW: Int, SH: Int, out: ByteArray, sigma: Double, rnd: java.util.Random,
           obj: ((Double, Double) -> Int?)? = null) {
    val c = cos(rot); val s = sin(rot)
    for (y in 0 until SH) for (x in 0 until SW) {
        val u = x - SW / 2.0; val v = y - SH / 2.0
        val wx = cx + c * u - s * v; val wy = cy + s * u + c * v
        var g = obj?.invoke(wx, wy)?.toDouble() ?: m.at(wx, wy).toDouble()
        if (sigma > 0) g += rnd.nextGaussian() * sigma
        val q = g.roundToInt().coerceIn(0, 255)
        val o = (y * SW + x) * 4
        out[o] = q.toByte(); out[o + 1] = q.toByte(); out[o + 2] = q.toByte(); out[o + 3] = -1
    }
}

fun bloques(seed: Int, lo: Int, span: Int, tam: Int = 12): (Int, Int) -> Int {
    val cache = HashMap<Long, Int>()
    return { x, y ->
        val bx = x / tam; val by = y / tam
        cache.getOrPut(bx.toLong() * 100000 + by) { val r = java.util.Random(seed * 7919L + bx * 131L + by * 1_000_003L); lo + r.nextInt(span) }
    }
}

class Res(var falsos: Int = 0, var framesFalsos: Int = 0, var vista: Int = 0, var mov: Int = 0, var resets: Int = 0)

/**
 * Corre un escenario. cam(f) -> (cx, cy, rot); persona(f) -> caja en mundo o null.
 */
fun correr(m: Mundo, n: Int, desde: Int, ego: Boolean, sigma: Double,
           cam: (Int) -> Triple<Double, Double, Double>, persona: ((Int) -> DoubleArray?)? = null,
           grande: ((Int) -> DoubleArray?)? = null): Res {
    val SW = 640; val SH = 480
    val e = Engine(); e.egoMotion = ego
    val src = ByteArray(SW * SH * 4); val small = ByteArray(320 * 240 * 4)
    val rnd = java.util.Random(7)
    val res = Res()
    for (f in 0 until n) {
        val (cx, cy, rot) = cam(f)
        val p = persona?.invoke(f); val g = grande?.invoke(f)
        val obj: ((Double, Double) -> Int?)? = if (p == null && g == null) null else { wx, wy ->
            if (p != null && wx >= p[0] && wx < p[0] + p[2] && wy >= p[1] && wy < p[1] + p[3]) 225
            else if (g != null && wx >= g[0] && wx < g[0] + g[2] && wy >= g[1] && wy < g[1] + g[3])
                // objeto grande texturado: bloques propios que se mueven con él
                (if ((((wx - g[0]) / 16).toInt() + ((wy - g[1]) / 16).toInt()) % 2 == 0) 40 else 200)
            else null
        }
        render(m, cx, cy, rot, SW, SH, src, sigma, rnd, obj)
        downscaleRgba(src, SW * 4, SW, SH, small, 320, 240)
        val r = e.analyze(small, 320, 240, SW, SH)
        if (r.disturbed) res.resets++
        if (e.cameraMoving) res.mov++
        if (f < desde) continue
        var aqui = 0; var vista = false
        // caja de la persona en coordenadas de la ventana (sin rotación: rot pequeña)
        val boxes = r.boxes
        for (b in boxes) {
            var sobre = false
            for (o in listOfNotNull(p, g)) {
                val px = o[0] - (cx - SW / 2.0); val py = o[1] - (cy - SH / 2.0)
                if (b.x1 < px + o[2] && b.x2 > px && b.y1 < py + o[3] && b.y2 > py) { sobre = true; if (o === p) vista = true }
            }
            if (!sobre) aqui++
        }
        if (vista) res.vista++
        res.falsos += aqui; if (aqui > 0) res.framesFalsos++
    }
    return res
}
