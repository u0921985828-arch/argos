package eus.argos.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lo que es NORMAL en esta escena, aprendido de la propia escena, y cuánto se
 * aparta de ello cada recorrido. Puerto literal de `behaviour.js`, con paridad
 * numérica.
 *
 * No se configura nada: la escena se parte en celdas y cada una acumula de las
 * trayectorias que pasan por ella el flujo (rumbos, con sus modos: una calzada
 * de doble sentido tiene dos), la velocidad (cuantiles, no media) y qué clases
 * transitan. Y una celda con pocas muestras NO OPINA: sin esa reserva el
 * primer día todo es anómalo, el operador apaga las alertas y el sistema deja
 * de existir (invariante 6).
 *
 * No sabe quién es nadie. Puntúa recorridos —por dónde, a qué velocidad, en
 * qué sentido—, con el porqué de cada puntuación: una alerta que no se puede
 * discutir es una acusación.
 */
class BehaviourModel(
    val w: Double, val h: Double,
    val cell: Double = 96.0,
    val dirBins: Int = 12,
    val minSamples: Int = 25,
    val minDirConcentration: Double = 0.35,
    val speedFloor: Double = 0.4,
) {
    private val wDir = 1.0; private val wSpeed = 0.9; private val wKlass = 1.2; private val wNovel = 0.5

    class Cell(bins: Int) {
        val dir = FloatArray(bins)
        val speeds = ArrayList<Double>()
        val klass = LinkedHashMap<String, Int>()
        var n = 0

        fun observe(headingDeg: Double, speedMs: Double, k: String?, bins: Int, floor: Double) {
            n++
            if (speedMs.isFinite()) { speeds.add(speedMs); if (speeds.size > 1000) speeds.removeAt(0) }
            // `speedMs ?? 0` en el JS: NaN no es nulo, así que sin velocidad
            // tampoco hay rumbo. Igual aquí.
            if (headingDeg.isFinite() && speedMs >= floor) {
                val b = (floor(((headingDeg % 360) + 360) % 360 / (360.0 / bins)).toInt()) % bins
                dir[b] = dir[b] + 1f
            }
            if (k != null && k.isNotEmpty()) klass[k] = (klass[k] ?: 0) + 1
        }
    }

    val cols = max(1, ceil(w / cell).toInt())
    val rows = max(1, ceil(h / cell).toInt())
    val cells = LinkedHashMap<Int, Cell>()
    var total = 0; private set

    private fun key(x: Double, y: Double): Int {
        val c = min(cols - 1, max(0, floor(x / cell).toInt()))
        val r = min(rows - 1, max(0, floor(y / cell).toInt()))
        return r * cols + c
    }

    private fun cellAt(k: Int, create: Boolean): Cell? {
        var c = cells[k]
        if (c == null && create) { c = Cell(dirBins); cells[k] = c }
        return c
    }

    /** Un recorrido: puntos en píxeles de escena y velocidad (m/s) en cada uno. */
    class Path(val pts: List<DoubleArray>, val speeds: DoubleArray?, val speedMs: Double?, val cls: String?)

    fun learn(tr: Path) {
        val pts = tr.pts
        if (pts.size < 4) return
        for (i in 1 until pts.size) {
            val x0 = pts[i - 1][0]; val y0 = pts[i - 1][1]; val x1 = pts[i][0]; val y1 = pts[i][1]
            val dx = x1 - x0; val dy = y1 - y0
            if (dx == 0.0 && dy == 0.0) continue
            val heading = (atan2(dy, dx) * 180 / Math.PI + 360) % 360
            val speed = tr.speeds?.let { if (i < it.size) it[i] else null } ?: tr.speedMs ?: Double.NaN
            cellAt(key(x1, y1), true)!!.observe(heading, speed, tr.cls, dirBins, speedFloor)
            total++
        }
    }

    class Mode(val heading: Double, val concentration: Double, val share: Double, val n: Double)
    class Flow(val concentration: Double, val heading: Double, val n: Double)
    class Quant(val p05: Double, val p50: Double, val p95: Double, val width: Double, val n: Int)
    class Profile(val n: Int, val flow: Flow, val modes: List<Mode>, val speed: Quant?, val classes: Map<String, Double>)

    private fun modes(c: Cell, minShare: Double = 0.15): List<Mode> {
        val bins = dirBins; val d = c.dir
        var tot = 0.0
        for (b in 0 until bins) tot += d[b]
        if (tot < 1) return emptyList()
        val sm = FloatArray(bins)
        for (b in 0 until bins)
            sm[b] = (0.25 * d[(b - 1 + bins) % bins] + 0.5 * d[b] + 0.25 * d[(b + 1) % bins]).toFloat()
        val peaks = ArrayList<Int>()
        for (b in 0 until bins) {
            val prev = sm[(b - 1 + bins) % bins]; val next = sm[(b + 1) % bins]
            if (sm[b] >= prev && sm[b] > next && sm[b] / tot >= minShare * 0.5) peaks.add(b)
        }
        if (peaks.isEmpty()) return emptyList()
        val mass = DoubleArray(peaks.size); val msx = DoubleArray(peaks.size); val msy = DoubleArray(peaks.size)
        for (b in 0 until bins) {
            if (d[b] == 0f) continue
            var best = 0; var bestDist = Int.MAX_VALUE
            for (i in peaks.indices) {
                var dist = abs(b - peaks[i])
                if (dist > bins / 2.0) dist = bins - dist
                if (dist < bestDist) { bestDist = dist; best = i }
            }
            val ang = (b + 0.5) * (2 * Math.PI / bins)
            mass[best] += d[b].toDouble(); msx[best] += d[b] * cos(ang); msy[best] += d[b] * sin(ang)
        }
        return peaks.indices.filter { mass[it] / tot >= minShare }
            .map { Mode((atan2(msy[it], msx[it]) * 180 / Math.PI + 360) % 360,
                        hypot(msx[it], msy[it]) / mass[it], mass[it] / tot, mass[it]) }
            .sortedByDescending { it.share }
    }

    private fun flow(c: Cell): Flow {
        var sx = 0.0; var sy = 0.0; var n = 0.0
        for (b in 0 until dirBins) {
            val wgt = c.dir[b].toDouble()
            if (wgt == 0.0) continue
            val ang = (b + 0.5) * (2 * Math.PI / dirBins)
            sx += wgt * cos(ang); sy += wgt * sin(ang); n += wgt
        }
        if (n == 0.0) return Flow(0.0, Double.NaN, 0.0)
        return Flow(hypot(sx, sy) / n, (atan2(sy, sx) * 180 / Math.PI + 360) % 360, n)
    }

    fun profile(x: Double, y: Double): Profile? {
        val c = cellAt(key(x, y), false) ?: return null
        if (c.n < minSamples) return null
        val tot = c.klass.values.sum().let { if (it == 0) 1 else it }
        return Profile(c.n, flow(c), modes(c), if (c.speeds.size >= 12) quantiles(c.speeds) else null,
                       c.klass.mapValues { it.value.toDouble() / tot })
    }

    class Score(val score: Double, val mature: Boolean, val reasons: List<String>, val coverage: Double,
                val direction: Double = 0.0, val speedExcess: Double = 0.0, val classShare: Double = 1.0,
                val unknownFrac: Double = 1.0)

    /** Cuánto se aparta un recorrido de lo normal, con el porqué. */
    fun score(tr: Path): Score {
        val pts = tr.pts; val cls = tr.cls
        val reasons = ArrayList<String>()
        val dirSamples = ArrayList<Double>()
        var spdMax = 0.0; var clsMin = 1.0
        var unknown = 0; var seen = 0
        for (i in 1 until pts.size) {
            val x0 = pts[i - 1][0]; val y0 = pts[i - 1][1]; val x1 = pts[i][0]; val y1 = pts[i][1]
            val p = profile(x1, y1)
            seen++
            if (p == null) { unknown++; continue }
            val dx = x1 - x0; val dy = y1 - y0
            val speed = localMedian(tr.speeds, i, 5)
            if (p.modes.isNotEmpty() && (dx != 0.0 || dy != 0.0) && speed >= speedFloor) {
                val heading = (atan2(dy, dx) * 180 / Math.PI + 360) % 360
                var bestDiff = 0.0; var bestMode: Mode? = null
                for (mo in p.modes) {
                    if (mo.concentration < minDirConcentration) continue
                    var diff = abs(heading - mo.heading) % 360
                    if (diff > 180) diff = 360 - diff
                    if (bestMode == null || diff < bestDiff) { bestDiff = diff; bestMode = mo }
                }
                if (bestMode != null) dirSamples.add((bestDiff / 180) * bestMode.concentration * (0.5 + bestMode.share))
            }
            val q = p.speed
            if (q != null && speed.isFinite()) {
                val over = if (speed > q.p95) speed - q.p95 else if (speed < q.p05) q.p05 - speed else 0.0
                val z = over / q.width
                if (z > spdMax) spdMax = z
            }
            if (cls != null && cls.isNotEmpty()) {
                val share = p.classes[cls] ?: 0.0
                if (share < clsMin) clsMin = share
            }
        }
        if (seen == 0 || unknown == seen) return Score(0.0, false, listOf("escena aún sin aprender"), 0.0)

        var dirScore = 0.0
        if (dirSamples.isNotEmpty()) {
            val s2 = dirSamples.toDoubleArray().also { it.sort() }
            dirScore = quantile(s2, 0.75) * (if (dirSamples.size >= 4) 1.0 else dirSamples.size / 4.0)
        }
        val spdScore = min(1.0, spdMax / 3)
        val clsScore = if (clsMin < 0.02) 1 - clsMin * 50 else 0.0
        val novScore = unknown.toDouble() / seen
        if (dirScore > 0.45) reasons.add("va contra el flujo habitual en un tramo sostenido (${"%.0f".format(dirScore * 100)} %)")
        if (spdMax > 1.5) reasons.add("velocidad fuera de lo normal en la zona (${"%.1f".format(java.util.Locale("es"), spdMax)}× el margen)")
        if (clsScore > 0.5 && cls != null) reasons.add("por ahí casi nunca pasa un ${nombre(cls)} (${"%.1f".format(java.util.Locale("es"), clsMin * 100)} %)")
        if (novScore > 0.6) reasons.add("recorre zonas sin historial")
        val score = min(1.0, (dirScore * wDir + spdScore * wSpeed + clsScore * wKlass + novScore * wNovel) /
                             (wDir + wSpeed + wKlass + wNovel))
        return Score(score, true, reasons, 1 - novScore, dirScore, spdMax, clsMin, novScore)
    }

    class Maturity(val cellsTotal: Int, val cellsTouched: Int, val cellsMature: Int, val directional: Int,
                   val observations: Int, val ready: Boolean)

    fun maturity(): Maturity {
        var mature = 0; var directional = 0
        for (c in cells.values) {
            if (c.n < minSamples) continue
            mature++
            if (modes(c).any { it.concentration >= minDirConcentration }) directional++
        }
        val grid = cols * rows
        return Maturity(grid, cells.size, mature, directional, total, mature >= max(4.0, grid * 0.05))
    }

    /* Persistencia: solo agregados por celda (histograma, velocidades, clases),
       nada de recorridos individuales. Así lo aprendido sobrevive a cerrar la
       app sin guardar por dónde fue nadie. */
    fun serialize(): String = buildString {
        append("v1 ${w} ${h} ${cell} ${dirBins} ${total}\n")
        for ((k, c) in cells) {
            append(k).append('\t').append(c.n).append('\t').append(c.dir.joinToString(","))
            append('\t').append(c.speeds.joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) })
            append('\t').append(c.klass.entries.joinToString(",") { "${it.key}:${it.value}" }).append('\n')
        }
    }

    companion object {
        fun deserialize(s: String): BehaviourModel? {
            val lines = s.lines().filter { it.isNotBlank() }
            val head = lines.firstOrNull()?.split(" ") ?: return null
            if (head.size < 6 || head[0] != "v1") return null
            val m = BehaviourModel(head[1].toDouble(), head[2].toDouble(), head[3].toDouble(), head[4].toInt())
            m.total = head[5].toInt()
            for (l in lines.drop(1)) {
                val c = l.split("\t")
                if (c.size < 5) continue
                val cell = Cell(m.dirBins)
                cell.n = c[1].toInt()
                c[2].split(",").forEachIndexed { i, v -> if (i < m.dirBins) cell.dir[i] = v.toFloat() }
                if (c[3].isNotEmpty()) c[3].split(",").forEach { cell.speeds.add(it.toDouble()) }
                if (c[4].isNotEmpty()) c[4].split(",").forEach { kv ->
                    val (k, v) = kv.split(":"); cell.klass[k] = v.toInt()
                }
                m.cells[c[0].toInt()] = cell
            }
            return m
        }

        fun quantile(sorted: DoubleArray, p: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            val i = (sorted.size - 1) * p
            val lo = floor(i).toInt(); val hi = ceil(i).toInt()
            return if (lo == hi) sorted[lo] else sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo)
        }

        fun quantiles(values: List<Double>): Quant? {
            val s = values.filter { it.isFinite() }.toDoubleArray().also { it.sort() }
            if (s.size < 12) return null
            val p05 = quantile(s, 0.05); val p50 = quantile(s, 0.5); val p95 = quantile(s, 0.95)
            return Quant(p05, p50, p95, max(0.25, max((p95 - p05) / 2, p50 * 0.25)), s.size)
        }

        fun localMedian(arr: DoubleArray?, i: Int, win: Int): Double {
            if (arr == null || arr.isEmpty()) return Double.NaN
            val half = win shr 1
            val wv = ArrayList<Double>()
            for (k in i - half..i + half) if (k in arr.indices && arr[k].isFinite()) wv.add(arr[k])
            return if (wv.isEmpty()) Double.NaN else medianOf(wv.toDoubleArray())
        }

        private fun nombre(k: String) = when (k) {
            "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici"; else -> k
        }
    }
}
