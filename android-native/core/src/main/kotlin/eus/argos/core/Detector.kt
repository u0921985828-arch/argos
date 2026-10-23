package eus.argos.core

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Detector YOLOX, puerto de `detector.js` en su modo de cuadro entero.
 *
 * Igual que `Core.kt`, es un puerto LITERAL: hay una prueba que pasa las
 * mismas fotos por el JS y por esto y compara caja a caja. Se portan los dos
 * modos que importan: el cuadro entero, que es el de por defecto (invariante
 * 3), y las teselas con zoom, para escenas amplias con objetos pequeños
 * (invariante 2: no hay zoom universal, depende de los píxeles del objeto).
 * La pirámide y los modos foveales quedan en el JS.
 *
 * No sabe nada de ONNX Runtime: la inferencia entra como función. Así el
 * preproceso y la decodificación, que es donde vive todo lo que puede salir
 * mal sin dar un error, se prueban en la JVM sin dispositivo.
 *
 * Dos trampas que este código reproduce a propósito:
 *  - la entrada es BGR CRUDO 0-255, sin normalizar, relleno 114 abajo-derecha;
 *    normalizar deja el modelo mudo;
 *  - la salida NO trae la rejilla decodificada; sin reconstruir las anclas de
 *    los strides 8/16/32 salen cero detecciones, no un error.
 */
class YoloxDetector(
    val size: Int = 416,
    private val infer: (FloatArray) -> FloatArray,
) {
    companion object {
        val COCO_NAMES = mapOf(0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle",
                               5 to "bus", 7 to "truck", 15 to "cat", 16 to "dog")

        /**
         * Categoría gruesa. `truck`→`car` se confundió 40 veces en un frame: la
         * subclase de vehículo no es fiable a estos tamaños, y el NMS opera
         * DENTRO del grupo o la misma furgoneta sale como dos objetos.
         */
        val COARSE = mapOf("person" to "person", "bicycle" to "bike", "motorcycle" to "bike",
                           "car" to "vehicle", "bus" to "vehicle", "truck" to "vehicle",
                           "cat" to "animal", "dog" to "animal")
    }

    // Los mismos valores que DET_DEFAULTS en detector.js. Cada uno está medido
    // allí; aquí solo se copian.
    var scoreThresh = 0.15
    var classThresh = mapOf("person" to 0.08, "bicycle" to 0.10, "motorcycle" to 0.12)
    var softSigma = 0.5
    /** Corte del soft-NMS y, a la vez, suelo de puntuación final (invariante 4). */
    var softCut = 0.20
    var allow = setOf("person", "vehicle", "bike")

    private val floorThr get() = minOf(scoreThresh, classThresh.values.minOrNull() ?: scoreThresh)

    private val gx: FloatArray
    private val gy: FloatArray
    private val st: FloatArray

    init {
        val n = intArrayOf(8, 16, 32).sumOf { (size / it) * (size / it) }
        gx = FloatArray(n); gy = FloatArray(n); st = FloatArray(n)
        var i = 0
        for (stride in intArrayOf(8, 16, 32)) {
            val m = size / stride
            for (y in 0 until m) for (x in 0 until m) {
                gx[i] = x.toFloat(); gy[i] = y.toFloat(); st[i] = stride.toFloat(); i++
            }
        }
    }

    class Prep(val tensor: FloatArray, val ratio: Double)

    /**
     * Letterbox al estilo YOLOX con vecino más cercano, como `_preprocess`.
     *
     * @param rowStride bytes por fila del búfer RGBA. En CameraX no es
     *   `anchura × 4`; leer con la anchura cizalla la imagen y el detector, en
     *   vez de fallar, simplemente deja de ver.
     */
    fun preprocess(rgba: ByteArray, rowStride: Int, tw: Int, th: Int,
                   ox: Int = 0, oy: Int = 0): Prep {
        val r = min(size.toDouble() / th, size.toDouble() / tw)
        val nw = Math.round(tw * r).toInt()
        val nh = Math.round(th * r).toInt()
        val plane = size * size
        val out = FloatArray(3 * plane) { 114f }
        for (y in 0 until nh) {
            val sy = min(th - 1, floor(y / r).toInt())
            for (x in 0 until nw) {
                val sx = min(tw - 1, floor(x / r).toInt())
                val s = (oy + sy) * rowStride + (ox + sx) * 4
                val d = y * size + x
                out[d] = (rgba[s + 2].toInt() and 0xFF).toFloat()
                out[plane + d] = (rgba[s + 1].toInt() and 0xFF).toFloat()
                out[2 * plane + d] = (rgba[s].toInt() and 0xFF).toFloat()
            }
        }
        return Prep(out, r)
    }

    class Raw(val boxes: List<DoubleArray>, val scores: List<Double>, val labels: List<Int>)

    /** `_decode`: rejilla, umbral por clase y lista blanca por categoría gruesa. */
    fun decode(raw: FloatArray, ratio: Double, tw: Int, th: Int): Raw {
        val n = gx.size
        val stride = raw.size / n
        val nCls = stride - 5
        val boxes = ArrayList<DoubleArray>(); val scores = ArrayList<Double>()
        val labels = ArrayList<Int>()
        val cheap = floorThr * 0.4
        for (i in 0 until n) {
            val o = i * stride
            val obj = raw[o + 4].toDouble()
            // El corte barato usa el umbral MÁS BAJO: con el general se
            // descartaría a las personas antes de mirar de qué clase son.
            if (obj < cheap) continue
            var best = 0; var bestV = raw[o + 5].toDouble()
            for (c in 1 until nCls) {
                val v = raw[o + 5 + c].toDouble()
                if (v > bestV) { bestV = v; best = c }
            }
            val score = obj * bestV
            val name = COCO_NAMES[best] ?: continue
            if (score < (classThresh[name] ?: scoreThresh)) continue
            if (allow.isNotEmpty() && (COARSE[name] ?: "other") !in allow) continue

            val s = st[i].toDouble()
            val cx = (raw[o].toDouble() + gx[i]) * s
            val cy = (raw[o + 1].toDouble() + gy[i]) * s
            val bw = exp(min(10.0, raw[o + 2].toDouble())) * s
            val bh = exp(min(10.0, raw[o + 3].toDouble())) * s
            val x1 = max(0.0, (cx - bw / 2) / ratio)
            val y1 = max(0.0, (cy - bh / 2) / ratio)
            val x2 = min(tw.toDouble(), (cx + bw / 2) / ratio)
            val y2 = min(th.toDouble(), (cy + bh / 2) / ratio)
            if (x2 - x1 < 2 || y2 - y1 < 2) continue
            boxes.add(doubleArrayOf(x1, y1, x2, y2)); scores.add(score); labels.add(best)
        }
        return Raw(boxes, scores, labels)
    }

    /**
     * Soft-NMS gaussiano agrupado, como `softNMS` del JS, orden incluido: los
     * grupos en orden de primera aparición y el mejor por `>` estricto, de modo
     * que los empates resuelven igual. Devuelve las puntuaciones ORIGINALES de
     * las supervivientes, que es lo que hace el JS.
     */
    fun softNms(raw: Raw, group: (Int) -> String): List<Int> {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for (i in raw.labels.indices) groups.getOrPut(group(raw.labels[i])) { mutableListOf() }.add(i)
        val keep = ArrayList<Int>()
        for (idx in groups.values) {
            val s = DoubleArray(idx.size) { raw.scores[idx[it]] }
            val alive = ArrayList(idx)
            while (alive.isNotEmpty()) {
                var best = 0
                for (k in 1 until alive.size) {
                    if (s[idx.indexOf(alive[k])] > s[idx.indexOf(alive[best])]) best = k
                }
                val i = alive.removeAt(best)
                if (s[idx.indexOf(i)] < softCut) continue
                keep.add(i)
                val bi = raw.boxes[i]
                for (k in alive.size - 1 downTo 0) {
                    val j = alive[k]; val bj = raw.boxes[j]
                    val x1 = max(bi[0], bj[0]); val y1 = max(bi[1], bj[1])
                    val x2 = min(bi[2], bj[2]); val y2 = min(bi[3], bj[3])
                    if (x2 <= x1 || y2 <= y1) continue
                    val inter = (x2 - x1) * (y2 - y1)
                    val ai = (bi[2] - bi[0]) * (bi[3] - bi[1])
                    val aj = (bj[2] - bj[0]) * (bj[3] - bj[1])
                    val ov = inter / (ai + aj - inter)
                    val pos = idx.indexOf(j)
                    s[pos] *= exp(-(ov * ov) / softSigma)
                    if (s[pos] < softCut) alive.removeAt(k)
                }
            }
        }
        return keep
    }

    /** Milisegundos de la última inferencia, sin preproceso ni decodificación. */
    var lastInferMs = 0.0; private set

    // Teselas: los mismos parámetros que DET_DEFAULTS.
    var tileOverlap = 0.45
    var edgeMargin = 4
    var minTileFrac = 1.35
    var maxTiles = 24

    /**
     * Plan de teselas, como `_tiles` del JS. `zoom <= 0` es el cuadro entero.
     * Por encima de ×3 el recuento BAJA (medido): ampliar no crea detalle y la
     * tesela pierde el contexto que el modelo necesita.
     */
    fun tiles(w: Int, h: Int, zoom: Double): List<IntArray> {
        if (zoom <= 0) return listOf(intArrayOf(0, 0, w, h))
        val t = maxOf(40, Math.round(size / maxOf(0.5, zoom)).toInt())
        if (w <= t && h <= t) return listOf(intArrayOf(0, 0, w, h))
        if (w < t * minTileFrac && h < t * minTileFrac) return listOf(intArrayOf(0, 0, w, h))
        val step = maxOf(1, Math.round(t * (1 - tileOverlap)).toInt())
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        run { var x = 0; while (x + t <= w) { xs.add(x); x += step } }
        if (xs.isEmpty() || xs.last() + t < w) xs.add(maxOf(0, w - t))
        run { var y = 0; while (y + t <= h) { ys.add(y); y += step } }
        if (ys.isEmpty() || ys.last() + t < h) ys.add(maxOf(0, h - t))
        var out = ArrayList<IntArray>()
        for (y in ys.distinct().sorted()) for (x in xs.distinct().sorted())
            out.add(intArrayOf(x, y, minOf(w, x + t), minOf(h, y + t)))
        if (out.size > maxTiles) {
            // Antes que exceder el presupuesto se reduce el solape.
            val every = (out.size + maxTiles - 1) / maxTiles
            out = ArrayList(out.filterIndexed { i, _ -> i % every == 0 })
        }
        return out
    }

    /** Inferencias de la última llamada a `detect` (1 en cuadro entero). */
    var lastTiles = 1; private set

    /**
     * Una pasada completa: cuadro entero (`zoom <= 0`) o todas las teselas.
     * Cajas en píxeles del búfer de entrada.
     */
    fun detect(rgba: ByteArray, rowStride: Int, w: Int, h: Int, zoom: Double = 0.0): List<DetBox> {
        val plan = tiles(w, h, zoom)
        lastTiles = plan.size
        val boxes = ArrayList<DoubleArray>(); val scores = ArrayList<Double>()
        val labels = ArrayList<Int>()
        var inferNs = 0L
        for (tl in plan) {
            val tx1 = tl[0]; val ty1 = tl[1]; val tw = tl[2] - tx1; val th = tl[3] - ty1
            val prep = preprocess(rgba, rowStride, tw, th, tx1, ty1)
            val t0 = System.nanoTime()
            val out = infer(prep.tensor)
            inferNs += System.nanoTime() - t0
            val d = decode(out, prep.ratio, tw, th)
            val m = edgeMargin
            for (i in d.boxes.indices) {
                val b = d.boxes[i]
                // Invariante 1: una caja que toca el borde de una tesela
                // INTERIOR miente; la vecina tiene el objeto entero.
                if ((b[0] <= m && tx1 > 0) || (b[1] <= m && ty1 > 0) ||
                    (b[2] >= tw - m && tl[2] < w) || (b[3] >= th - m && tl[3] < h)) continue
                boxes.add(doubleArrayOf(tx1 + b[0], ty1 + b[1], tx1 + b[2], ty1 + b[3]))
                scores.add(d.scores[i]); labels.add(d.labels[i])
            }
        }
        lastInferMs = inferNs / 1e6
        val raw = Raw(boxes, scores, labels)
        if (raw.boxes.isEmpty()) return emptyList()
        val keep = softNms(raw) { COARSE[COCO_NAMES[it]] ?: "other" }
        return keep.map { i ->
            val fine = COCO_NAMES[raw.labels[i]] ?: "object"
            val b = raw.boxes[i]
            DetBox(b[0], b[1], b[2], b[3], COARSE[fine] ?: "other", raw.scores[i], fine)
        }
    }
}

/**
 * Memoria de detecciones entre pasadas, como `Brain.mergeDetections` en modo
 * cuadro entero (cada pasada barre el cuadro completo).
 *
 * Una caja nueva se empareja con una viva de la misma clase si solapan más de
 * `confirmIou`; reencontrada `confirmPasses` veces queda confirmada. Lo que no
 * se reencuentra se SUELTA: mantenerla congelada deja recuadros pegados al
 * suelo mientras la persona sigue andando --- es pedirle al detector que haga
 * de tracker, resultado negativo documentado. Y lo inventado por un modelo
 * pequeño parpadea de sitio entre pasadas, así que no llega a confirmarse.
 */
class DetectionMemory(
    private val ttlMs: Double = 4000.0,
    private val confirmPasses: Int = 2,
    private val confirmIou: Double = 0.35,
) {
    class Mem(val d: DetBox, val t: Double, val seen: Int, val confirmed: Boolean)

    @Volatile var boxes: List<Mem> = emptyList(); private set

    fun update(dets: List<DetBox>, nowMs: Double) {
        val vivas = boxes.filter { nowMs - it.t < ttlMs }
        val usadas = HashSet<Mem>()
        val fresh = ArrayList<Mem>()
        for (d in dets) {
            var mejor: Mem? = null; var mejorIoU = confirmIou
            for (o in vivas) {
                if (o.d.klass != d.klass || o in usadas) continue
                val s = iouBox(o.d, d)
                if (s > mejorIoU) { mejorIoU = s; mejor = o }
            }
            if (mejor != null) usadas.add(mejor)
            val seen = if (mejor != null) mejor.seen + 1 else 1
            fresh.add(Mem(d, nowMs, seen, mejor != null && seen >= confirmPasses))
        }
        boxes = fresh
    }

    /** `null` sin ninguna caja: entonces el seguimiento usa la resta de fondo. */
    fun forTracking(): List<DetBox>? {
        val b = boxes
        if (b.isEmpty()) return null
        return if (confirmPasses > 1) b.filter { it.confirmed || it.seen >= confirmPasses }.map { it.d }
               else b.map { it.d }
    }

    private fun iouBox(a: DetBox, b: DetBox): Double {
        val x1 = max(a.x1, b.x1); val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2); val y2 = min(a.y2, b.y2)
        if (x2 <= x1 || y2 <= y1) return 0.0
        val i = (x2 - x1) * (y2 - y1)
        return i / ((a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - i)
    }
}

/**
 * Elige solo entre cuadro entero y teselas ×2, mirando lo que encuentra cada uno.
 *
 * Es la regla que domina el proyecto: el presupuesto de píxeles por objeto
 * manda, y no hay zoom universal. Medido con el detector nativo:
 *
 *   bus, zidane, dog (objetos grandes)   cuadro entero 4 / 2 / 3   teselas ×2 1 / 1 / 1
 *   plaza (personas de ~50 px)           cuadro entero 0           teselas ×2 31
 *
 * Ninguno de los dos modos sirve para las dos escenas. Así que se trabaja en
 * uno y de vez en cuando se SONDEA el otro: se cambia a teselas solo si
 * encuentran claramente más (al menos 2 más y un 50 % más), y se vuelve al
 * cuadro entero en cuanto este iguala a las teselas, porque es veinte veces
 * más barato y no parte los objetos grandes en trozos. Fundir los dos en una
 * pasada está probado y es peor (el trozo puntúa más que el objeto).
 */
class AutoZoom(private val probeEvery: Int = 8, private val minScore: Double = 0.3) {
    /** 0 = cuadro entero, 2 = teselas ×2. */
    var mode = 0.0; private set
    private var pass = probeEvery - 2      // el primer sondeo, en la segunda pasada
    private var lastMain = -1
    /** Cuántas veces ha cambiado de modo, para el panel. */
    var switches = 0; private set
    // Medias de lo que encuentra cada modo. Con pocos objetos (una carretera
    // con 1-3 coches) la regla instantánea «2 más y un 50 % más» no salta
    // nunca: en 110 cámaras públicas las teselas ×2 vieron 1,4 veces más que
    // el cuadro entero y el modo se quedaba en cuadro entero. Con la media de
    // varios sondeos, una ventaja pequeña pero constante también cuenta.
    private var mediaPrincipal = -1.0
    private var mediaSondeo = -1.0
    private var sondeos = 0

    fun next(): Double {
        val z = if (pass % probeEvery == probeEvery - 1 && lastMain >= 0) other() else mode
        pass++
        return z
    }

    /** Lo aprendido de esta cámara en otra sesión: se empieza en ese modo. */
    fun empiezaEn(modo: Double) {
        if (modo == 0.0 || modo == 2.0) mode = modo
    }

    fun report(zoomUsed: Double, dets: List<DetBox>) {
        val n = dets.count { it.score >= minScore }
        if (zoomUsed == mode) {
            lastMain = n
            mediaPrincipal = if (mediaPrincipal < 0) n.toDouble() else mediaPrincipal * 0.8 + n * 0.2
            return
        }
        mediaSondeo = if (sondeos == 0) n.toDouble() else mediaSondeo * 0.5 + n * 0.5
        sondeos++
        val sube = mode == 0.0 && (n >= maxOf(lastMain + 2, Math.ceil(lastMain * 1.5).toInt()) ||
            (sondeos >= 3 && mediaSondeo >= mediaPrincipal + 0.8 && mediaSondeo >= mediaPrincipal * 1.3))
        val baja = mode == 2.0 && n >= lastMain && (sondeos >= 2 && mediaSondeo >= mediaPrincipal || n > lastMain + 1)
        if (sube || baja) {
            mode = zoomUsed; lastMain = n; switches++
            mediaPrincipal = mediaSondeo; mediaSondeo = -1.0; sondeos = 0
        }
    }

    private fun other() = if (mode == 0.0) 2.0 else 0.0
}
