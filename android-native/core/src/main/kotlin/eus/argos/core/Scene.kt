package eus.argos.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Lo que la app sabe de la ESCENA, no solo de las cajas: escala métrica
 * aprendida de los propios peatones, velocidad en m/s, estatura, eventos y
 * recuentos. Puerto literal de `SceneScale`, `Physics` y la parte de `Brain`
 * que los alimenta (brain.js), con prueba de paridad numérica como el resto
 * del núcleo. Donde el JS tiene una peculiaridad, aquí está la misma: dos
 * implementaciones que difieren «un poco» acaban sin saber cuál tenía razón.
 */

/** Mediana numérica, como `median` de brain.js (orden de Float64Array). */
fun medianOf(arr: DoubleArray): Double {
    if (arr.isEmpty()) return Double.NaN
    val a = arr.copyOf(); a.sort()
    val m = a.size shr 1
    return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2
}

/** Sistema pequeño por eliminación gaussiana con pivoteo; null si es singular. */
fun solveSmall(M: Array<DoubleArray>, v: DoubleArray, n: Int): DoubleArray? {
    val A = Array(n) { i -> DoubleArray(n + 1).also { r -> for (j in 0 until n) r[j] = M[i][j]; r[n] = v[i] } }
    for (c in 0 until n) {
        var piv = c
        for (r in c + 1 until n) if (abs(A[r][c]) > abs(A[piv][c])) piv = r
        if (abs(A[piv][c]) < 1e-12) return null
        val tmp = A[c]; A[c] = A[piv]; A[piv] = tmp
        for (r in 0 until n) {
            if (r == c) continue
            val f = A[r][c] / A[c][c]
            for (k in c..n) A[r][k] -= f * A[c][k]
        }
    }
    return DoubleArray(n) { i -> A[i][n] / A[i][i] }
}

/** Una muestra por TRAYECTORIA: dónde pisa, a qué altura y cuánto mide en imagen. */
class ScaleSample(val x: Double, val y: Double, val h: Double, val cls: String)

/**
 * Relación entre la fila de contacto con el suelo y el tamaño en imagen.
 *
 * Si un peatón a la fila `y` mide `h(y)` píxeles y un peatón mide ~1,70 m, en
 * esa zona un píxel son 1,70/h(y) metros: velocidad, separación y estatura sin
 * calibrar nada. Solo vale con la cámara QUIETA, y solo se da por buena con
 * objetos a distintas distancias (recorrido vertical) y dispersión razonable.
 */
class SceneScale(
    val assumedStature: Double = 1.70,
    val minSamples: Int = 10,
    val minYSpan: Double = 120.0,
    val maxColinearity: Double = 0.75,
) {
    var a = 0.0; var b = 0.0; var sigma = 0.0; var n = 0
    var valid = false; private set
    var cx = 0.0; private set
    var usesX = false; private set
    var colinearity = 0.0; private set
    var ySpan = 0.0; private set
    var rejected = 0; private set
    val classScale = HashMap<String, Double>()

    private class Fit(val coef: DoubleArray, val sigma: Double, val n: Int)

    private fun fitPlane(pts: List<ScaleSample>, useX: Boolean): Fit? {
        val n = pts.size
        if (n < (if (useX) 12 else 6)) return null
        val cols = if (useX) 3 else 2
        var keep = BooleanArray(n) { true }
        var coef: DoubleArray? = null
        fun pred(c: DoubleArray, p: ScaleSample) =
            if (useX) c[0] * p.y + c[1] * p.x + c[2] else c[0] * p.y + c[1]
        for (iter in 0 until 6) {
            val M = Array(cols) { DoubleArray(cols) }
            val v = DoubleArray(cols)
            var m = 0
            for (i in 0 until n) {
                if (!keep[i]) continue
                m++
                val p = pts[i]
                val row = if (useX) doubleArrayOf(p.y, p.x, 1.0) else doubleArrayOf(p.y, 1.0)
                for (aa in 0 until cols) {
                    v[aa] += row[aa] * p.h
                    for (bb in 0 until cols) M[aa][bb] += row[aa] * row[bb]
                }
            }
            if (m < cols + 2) return null
            coef = solveSmall(M, v, cols) ?: return null
            val res = DoubleArray(n) { pts[it].h - pred(coef, pts[it]) }
            val inl = res.filterIndexed { i, _ -> keep[i] }.toDoubleArray()
            val med = medianOf(inl)
            val sd = 1.4826 * medianOf(DoubleArray(inl.size) { abs(inl[it] - med) })
            if (sd <= 1e-6) break
            val next = BooleanArray(n) { abs(res[it]) < 2.5 * sd }
            if (next.contentEquals(keep)) break
            keep = next
        }
        val c = coef!!
        val res = ArrayList<Double>()
        for (i in 0 until n) if (keep[i]) res.add(pts[i].h - pred(c, pts[i]))
        val kept = res.size
        val mu = res.sum() / max(1, kept)
        val s = sqrt(res.sumOf { (it - mu) * (it - mu) } / max(1, kept))
        return Fit(c, max(1e-3, s), kept)
    }

    fun fit(samples: List<ScaleSample>): SceneScale {
        val pts = samples.filter { it.h > 4 && it.y.isFinite() && it.x.isFinite() }
        if (pts.size < minSamples) return this

        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (p in pts) {
            sxy += (p.x - mx) * (p.y - my); sxx += (p.x - mx) * (p.x - mx); syy += (p.y - my) * (p.y - my)
        }
        val corr = abs(sxy) / max(1e-9, sqrt(sxx * syy))

        val flat = fitPlane(pts, false)
        var plane: Fit? = null
        if (corr < maxColinearity && pts.size >= 40) {
            plane = fitPlane(pts, true)
            if (plane != null && flat != null && plane.sigma > flat.sigma * 0.97) plane = null
        }
        colinearity = corr
        usesX = plane != null
        val best = plane ?: flat
        if (best != null) {
            a = best.coef[0]
            cx = if (plane != null) best.coef[1] else 0.0
            b = if (plane != null) best.coef[2] else best.coef[1]
        }

        val persons = pts.filter { it.cls == "person" }
        val base = if (persons.size >= minSamples) persons else pts

        var keep = BooleanArray(base.size) { true }
        var aa = 0.0; var bb = 0.0
        for (iter in 0 until 5) {
            var nn = 0; var sx = 0.0; var sy = 0.0; var sxx2 = 0.0; var sxy2 = 0.0
            for (i in base.indices) {
                if (!keep[i]) continue
                val y = base[i].y; val h = base[i].h
                nn++; sx += y; sy += h; sxx2 += y * y; sxy2 += y * h
            }
            if (nn < 4) break
            val den = nn * sxx2 - sx * sx
            if (abs(den) < 1e-9) break
            aa = (nn * sxy2 - sx * sy) / den
            bb = (sy - aa * sx) / nn
            val res = DoubleArray(base.size) { base[it].h - (aa * base[it].y + bb) }
            val inl = res.filterIndexed { i, _ -> keep[i] }.toDoubleArray()
            val med = medianOf(inl)
            val s = 1.4826 * medianOf(DoubleArray(inl.size) { abs(inl[it] - med) })
            if (s <= 1e-6) break
            val next = BooleanArray(base.size) { abs(res[it]) < 2.5 * s }
            if (next.contentEquals(keep)) break
            keep = next
        }
        val res = ArrayList<Double>()
        for (i in base.indices) if (keep[i]) res.add(base[i].h - (aa * base[i].y + bb))
        val mu = res.sum() / max(1, res.size)
        val ys = base.filterIndexed { i, _ -> keep[i] }.map { it.y }
        val span = if (ys.isNotEmpty()) ys.max() - ys.min() else 0.0
        val meanH = base.sumOf { it.h } / max(1, base.size)
        val sig = max(1e-3, sqrt(res.sumOf { (it - mu) * (it - mu) } / max(1, res.size)))

        val ok = res.size >= minSamples && sig > 0 && span >= minYSpan && aa > 0 &&
            sig < 0.45 * max(1.0, meanH)
        if (!ok && valid) { rejected++; return this }
        ySpan = span
        valid = ok
        a = aa; b = bb; sigma = sig; n = res.size

        val byClass = LinkedHashMap<String, MutableList<Double>>()
        for (s in pts) {
            val exp = aa * s.y + bb
            if (exp > 1) byClass.getOrPut(s.cls) { mutableListOf() }.add(s.h / exp)
        }
        classScale.clear()
        for ((c, ratios) in byClass) if (ratios.size >= 5) classScale[c] = medianOf(ratios.toDoubleArray())
        return this
    }

    fun expected(y: Double, cls: String = "person", x: Double = 0.0) =
        (a * y + cx * x + b) * (classScale[cls] ?: 1.0)

    fun z(y: Double, h: Double, cls: String = "person"): Double {
        if (!valid) return 0.0
        val k = classScale[cls] ?: 1.0
        return (h - expected(y, cls)) / (sigma * k)
    }

    fun metersPerPixel(y: Double, x: Double = 0.0): Double {
        if (!valid) return Double.NaN
        val hp = a * y + cx * x + b
        return if (hp > 2) assumedStature / hp else Double.NaN
    }
}

/**
 * Estado cinemático de un objeto, suavizado antes de derivar: la caja de una
 * resta de fondo tiembla y derivar ruido dos veces no significa nada.
 */
class Physics {
    var vx = 0.0; var vy = 0.0; var ax = 0.0; var ay = 0.0
    var speedPx = 0.0; var speedMs = Double.NaN; var heading = Double.NaN
    var pathPx = 0.0; var dispPx = 0.0; var straightness = 1.0
    var dwellS = 0.0; var stopped = false
    private var x0 = 0.0; private var y0 = 0.0
    private var px = 0.0; private var py = 0.0
    private var t: Double? = null
    private var tStart: Double? = null
    private var stopThresh = Double.NaN

    fun update(cx: Double, by: Double, tSec: Double, scale: SceneScale?) {
        val tp = t
        if (tp == null) { x0 = cx; y0 = by; px = cx; py = by; t = tSec; return }
        val dt = max(1e-3, tSec - tp)
        val dx = cx - px; val dy = by - py
        pathPx += hypot(dx, dy)
        val k = min(1.0, dt * 6)
        val nvx = dx / dt; val nvy = dy / dt
        val pvx = vx; val pvy = vy
        vx += (nvx - vx) * k
        vy += (nvy - vy) * k
        val ka = min(1.0, dt * 1.5)
        ax += (((vx - pvx) / dt) - ax) * ka
        ay += (((vy - pvy) / dt) - ay) * ka
        speedPx = hypot(vx, vy)
        heading = (atan2(vy, vx) * 180 / Math.PI + 360) % 360
        val mpp = scale?.metersPerPixel(by, cx) ?: Double.NaN
        speedMs = if (mpp.isFinite()) speedPx * mpp else Double.NaN
        dispPx = hypot(cx - x0, by - y0)
        straightness = if (pathPx > 1) dispPx / pathPx else 1.0
        if (tStart == null) tStart = tSec
        dwellS = tSec - tStart!!
        stopped = speedPx < stopThresh
        px = cx; py = by; t = tSec
    }

    fun setStopThreshold(objHeightPx: Double) { stopThresh = max(1.5, objHeightPx * 0.35) }
}

/**
 * Una trayectoria terminada, lista para guardarse. Solo geometría y tiempo:
 * ni imagen, ni recorte, ni nada que identifique a nadie.
 */
class Trajectory(val id: Int, val cls: String, val fromDet: Boolean, val start: Double, val end: Double,
                 val maxSpeedMs: Double, val points: List<DoubleArray>, val events: List<String>)

/** Algo que ha pasado en la escena, en lenguaje de quien la vigila. */
class SceneEvent(val kind: String, val id: Int, val cls: String, val t: Double, val detail: String)

/** Estatura estimada: NO probatoria (la escala se ancla en una mediana asumida). */
class Stature(val m: Double, val lo: Double, val hi: Double, val n: Int)

/**
 * La parte de `Brain` que convierte pistas en conocimiento de la escena.
 *
 * Trabaja en coordenadas de ESCENA (imagen + desplazamiento de la cámara), así
 * que la velocidad sigue siendo correcta con el móvil girando; pero la escala
 * métrica solo se aprende con la cámara quieta, porque la relación fila-tamaño
 * es de una cámara fija y un paneo la falsea.
 */
class SceneBrain(
    private val minStatureSamples: Int = 12,
    private val staturePercentile: Double = 88.0,
    private val eventStopS: Double = 2.5,
    private val eventRunMs: Double = 2.2,
    private val loiterS: Double = 45.0,
    private val loiterStraightness: Double = 0.25,
    private val refitEveryS: Double = 3.0,
) {
    val scale = SceneScale()
    val physics = HashMap<Int, Physics>()
    private val stature = HashMap<Int, ArrayList<Double>>()
    private val obs = HashMap<Int, ArrayList<DoubleArray>>()      // x, y, h por observación
    private val trajectories = ArrayDeque<ScaleSample>()         // una por trayectoria terminada
    private val lastEvent = HashMap<Int, HashMap<String, Double>>()
    val events = ArrayDeque<SceneEvent>()
    private var lastFit = -1e9

    private class Path(val start: Double) {
        var end = start; var cls = "object"; var det = false; var vmax = Double.NaN
        val pts = ArrayList<DoubleArray>(); val evs = ArrayList<String>(); var n = 0
        /** La cámara se movió en algún momento del recorrido. */
        var moved = false
        var anomaly: BehaviourModel.Score? = null
    }

    /**
     * Lo normal de esta escena (behaviour.js). Solo aprende de recorridos
     * hechos con la cámara quieta y con escala métrica: sin velocidad en m/s
     * una celda no aprende rumbos, igual que en la web.
     */
    var behaviour: BehaviourModel? = null
    private var frameN = 0

    private fun pathOf(pa: Path) = BehaviourModel.Path(
        pa.pts.map { doubleArrayOf(it[1], it[2]) }, DoubleArray(pa.pts.size) { pa.pts[it][3] }, null,
        if (pa.det) pa.cls else null)
    private val paths = HashMap<Int, Path>()
    /** Trayectorias que han terminado desde la última vez que se recogieron. */
    val finished = ArrayList<Trajectory>()

    /** Pistas con clase del detector en pantalla ahora. */
    var people = 0; private set
    var vehicles = 0; private set
    var unclassified = 0; private set
    /** Distintas pistas confirmadas por el detector en la sesión. */
    val seen = HashMap<String, HashSet<Int>>()

    class TrackInfo(val id: Int, val cls: String, val fromDet: Boolean, val speedMs: Double,
                    val speedPx: Double, val dwellS: Double, val stopped: Boolean, val stature: Stature?,
                    val anomaly: BehaviourModel.Score? = null)

    /**
     * @param tracks pistas confirmadas del núcleo, en píxeles de análisis
     * @param sx, sy  escala análisis → fuente
     * @param camX, camY desplazamiento acumulado de la cámara, en píxeles de FUENTE
     * @param still la cámara está quieta: solo entonces se aprende la escala
     */
    fun update(tracks: List<Track>, minHits: Int, tSec: Double, sx: Double, sy: Double,
               srcW: Int, srcH: Int, camX: Double, camY: Double, still: Boolean): List<TrackInfo> {
        val live = HashSet<Int>()
        var p0 = 0; var v0 = 0; var u0 = 0
        // Lo aprendido vale para un encuadre: si cambia la resolución de la
        // cámara, las celdas ya no son el mismo trozo de suelo.
        val bw = behaviour
        if (bw == null || bw.w != srcW.toDouble() || bw.h != srcH.toDouble())
            behaviour = BehaviourModel(srcW.toDouble(), srcH.toDouble())
        val beh = behaviour!!
        frameN++
        val out = ArrayList<TrackInfo>()
        for (t in tracks) {
            if (t.hits < minHits) continue
            live.add(t.id)
            // Una pista que este frame no ha visto nada se queda donde estaba
            // mientras agota su margen. Medir su física ahí es medir un objeto
            // inmóvil que ya no existe: cada peatón que salía del cuadro dejaba
            // un «detenido» falso de nueve segundos y algún «corriendo» al
            // saltar la caja. Se cuenta, pero no se mide.
            if (t.miss > 0) continue
            val p = physics.getOrPut(t.id) { Physics() }
            val path = paths.getOrPut(t.id) { Path(tSec) }
            val cxImg = (t.x1 + t.x2) / 2 * sx
            val byImg = t.y2 * sy
            val hpx = (t.y2 - t.y1) * sy
            p.setStopThreshold(hpx)
            p.update(cxImg + camX, byImg + camY, tSec, if (still) scale else null)
            if (still) {
                obs.getOrPut(t.id) { ArrayList() }.let {
                    it.add(doubleArrayOf(cxImg, byImg, hpx)); if (it.size > 240) it.removeAt(0)
                }
                if (t.klass == "person" && t.klassSrc == "det")
                    accumulateStature(t.id, t.x1 * sx, t.x2 * sx, t.y1 * sy, t.y2 * sy, byImg, cxImg, hpx, srcW, srcH)
            }
            if (t.klassSrc == "det") {
                seen.getOrPut(t.klass) { HashSet() }.add(t.id)
                when (t.klass) { "person" -> p0++; "vehicle", "bike" -> v0++ }
            } else u0++
            checkEvents(t, p, tSec, sy)
            path.end = tSec; path.cls = t.klass; path.det = path.det || t.klassSrc == "det"
            if (!still) path.moved = true
            if (p.speedMs.isFinite() && !(p.speedMs <= path.vmax)) path.vmax = p.speedMs
            // Un punto cada 5 observaciones, en coordenadas de escena: basta
            // para dibujar el recorrido y ocupa unos cientos de bytes.
            if (path.n++ % 5 == 0)
                path.pts.add(doubleArrayOf(tSec, (t.x1 + t.x2) / 2 * sx + camX, t.y2 * sy + camY, p.speedMs))
            // Puntuación en vivo, una vez por segundo y pista: cuánto se aparta
            // de lo que suele pasar ahí. Con la cámara quieta; moviéndose, las
            // celdas no corresponden al mismo trozo de suelo.
            if (still && !path.moved && path.pts.size >= 6 && (frameN + t.id) % 30 == 0)
                path.anomaly = beh.score(pathOf(path)).takeIf { it.mature }
            out.add(TrackInfo(t.id, t.klass, t.klassSrc == "det", p.speedMs, p.speedPx, p.dwellS,
                              p.stopped, statureOf(t.id), path.anomaly))
        }
        people = p0; vehicles = v0; unclassified = u0
        // Lo que ya no está se suelta; de cada trayectoria queda UNA muestra
        // para la escala, que ocupa unos bytes en vez de cientos.
        for (id in paths.keys.toList()) if (id !in live) {
            val pa = paths.remove(id)!!
            if (!pa.moved && pa.pts.size >= 4) {
                // Se puntúa ANTES de aprender: un recorrido no puede servir de
                // norma para sí mismo.
                val s = beh.score(pathOf(pa))
                if (s.mature && s.score >= 0.2) {
                    val why = s.reasons.firstOrNull() ?: "fuera de lo habitual"
                    events.addFirst(SceneEvent(if (s.score >= 0.5) "anómalo" else "inusual", id, pa.cls, tSec,
                                               "%.2f · %s".format(java.util.Locale("es"), s.score, why)))
                    if (events.size > 60) events.removeLast()
                    pa.evs.add(if (s.score >= 0.5) "anómalo" else "inusual")
                }
                beh.learn(pathOf(pa))
            }
            if (pa.n >= 3) finished.add(Trajectory(id, pa.cls, pa.det, pa.start, pa.end, pa.vmax, pa.pts, pa.evs))
        }
        for (id in physics.keys.toList()) if (id !in live) {
            physics.remove(id); stature.remove(id); lastEvent.remove(id)
            obs.remove(id)?.let { o -> if (o.size >= 4) addTrajectory(o, tracks, id) }
        }
        if (still && tSec - lastFit >= refitEveryS) {
            lastFit = tSec
            val samples = ArrayList<ScaleSample>(trajectories)
            for ((id, o) in obs) if (o.size >= 4) samples.add(sampleOf(o, clsOf(tracks, id)))
            if (samples.size >= scale.minSamples) scale.fit(samples)
        }
        return out
    }

    private var lastCls = HashMap<Int, String>()
    private fun clsOf(tracks: List<Track>, id: Int): String =
        tracks.firstOrNull { it.id == id }?.klass?.also { lastCls[id] = it } ?: lastCls[id] ?: "object"

    private fun sampleOf(o: List<DoubleArray>, cls: String) = ScaleSample(
        medianOf(DoubleArray(o.size) { o[it][0] }), medianOf(DoubleArray(o.size) { o[it][1] }),
        medianOf(DoubleArray(o.size) { o[it][2] }), cls)

    private fun addTrajectory(o: List<DoubleArray>, tracks: List<Track>, id: Int) {
        trajectories.addLast(sampleOf(o, clsOf(tracks, id)))
        if (trajectories.size > 500) trajectories.removeFirst()
        lastCls.remove(id)
    }

    private fun accumulateStature(id: Int, x1: Double, x2: Double, y1: Double, y2: Double,
                                  baseY: Double, cx: Double, hpx: Double, W: Int, H: Int) {
        if (!scale.valid) return
        // Una caja que toca el borde está truncada: miente (invariante 1).
        if (x1 <= 4 || y1 <= 4 || x2 >= W - 4 || y2 >= H - 4) return
        if (hpx < 30) return
        val mpp = scale.metersPerPixel(baseY, cx)
        if (!mpp.isFinite()) return
        val h = hpx * mpp
        if (h < 1.0 || h > 2.4) return
        val arr = stature.getOrPut(id) { ArrayList() }
        arr.add(h); if (arr.size > 240) arr.removeAt(0)
    }

    fun statureOf(id: Int): Stature? {
        val arr = stature[id] ?: return null
        if (arr.size < minStatureSamples) return null
        val sorted = arr.toDoubleArray().also { it.sort() }
        val idx = min(sorted.size - 1, Math.round((sorted.size - 1) * staturePercentile / 100).toInt())
        val point = sorted[idx]
        val med = medianOf(arr.toDoubleArray())
        val spread = 1.4826 * medianOf(DoubleArray(arr.size) { abs(arr[it] - med) })
        val half = max(0.06, 2 * spread) + 0.055 * point
        return Stature(point, point - half, point + half, arr.size)
    }

    private fun checkEvents(t: Track, p: Physics, tSec: Double, sy: Double) {
        fun push(kind: String, detail: String) {
            val m = lastEvent.getOrPut(t.id) { HashMap() }
            if (tSec - (m[kind] ?: -1e9) < 5) return
            m[kind] = tSec
            events.addFirst(SceneEvent(kind, t.id, t.klass, tSec, detail))
            paths[t.id]?.evs?.add(kind)
            if (events.size > 60) events.removeLast()
        }
        if (p.stopped && p.dwellS > eventStopS) push("detenido", "%.1f s".format(java.util.Locale.ROOT, p.dwellS))
        if (p.speedMs.isFinite() && p.speedMs > eventRunMs && t.klass == "person" && t.klassSrc == "det")
            push("corriendo", "%.1f m/s".format(java.util.Locale.ROOT, p.speedMs))
        if (p.dwellS > loiterS && p.straightness < loiterStraightness)
            push("merodeo", "${p.dwellS.toInt()} s")
        if (scale.valid) {
            val z = scale.z(t.y2 * sy, (t.y2 - t.y1) * sy, t.klass)
            if (abs(z) > 6 && p.stopped) push("decorado", "z %.1f".format(java.util.Locale.ROOT, z))
        }
    }
}
