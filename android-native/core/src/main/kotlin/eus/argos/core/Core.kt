package eus.argos.core

/**
 * Núcleo de análisis de ARGOS, puerto literal de `engine.js`.
 *
 * "Literal" no es una figura: existe una prueba de paridad que corre la misma
 * secuencia por el JS y por este código y exige **cero** divergencias en las
 * cajas. Por eso hay aquí decisiones que en Kotlin escribirías de otra forma
 * --- cálculo en `Double` y almacenamiento en `Float`, lectura de bytes con
 * `and 0xFF` --- y que están puestas para reproducir la aritmética de
 * JavaScript exactamente, no por descuido.
 *
 * Si alguna vez hay que elegir entre que esto sea idiomático y que sea
 * idéntico, gana idéntico: dos implementaciones del mismo algoritmo que
 * divergen en silencio son peores que una sola más lenta.
 *
 * No depende de Android. Compila y se prueba en la JVM, que es lo que permite
 * verificarlo sin un dispositivo.
 */

/* ------------------------------------------------------------------ */
/*  Modelo de fondo                                                    */
/* ------------------------------------------------------------------ */

class BackgroundModel(
    val w: Int,
    val h: Int,
    private val alpha: Double = 0.008,
    private val k: Double = 2.6,
    varInit: Float = 90f,
    private val varMin: Double = 16.0,
    private val shadowRatio: Double = 0.55,
    private val warmup: Int = 45,
) {
    val mean = FloatArray(w * h)
    val vari = FloatArray(w * h) { varInit }
    val mask = ByteArray(w * h)
    var frames = 0; private set
    var fgRatio = 0.0; private set

    val ready: Boolean get() = frames > warmup

    /**
     * @param rgba Píxeles RGBA a la resolución del modelo. Se leen con
     *   `and 0xFF` porque `ByteArray` de Kotlin es con signo y un valor de 200
     *   llega como -56; sin la máscara la luma sale negativa y el modelo de
     *   fondo no converge nunca.
     */
    fun update(rgba: ByteArray, learn: Boolean = true): ByteArray {
        val n = w * h
        // Durante el arranque se aprende deprisa: si no, los primeros segundos
        // marcan la escena entera como primer plano.
        val a = if (frames < warmup) 0.25 else alpha
        var count = 0
        var p = 0
        for (i in 0 until n) {
            // Luma BT.601 en enteros, igual que el original.
            val r = rgba[p].toInt() and 0xFF
            val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8

            val mu = mean[i].toDouble()
            val d = y - mu
            val vRaw = vari[i].toDouble()
            val v = if (vRaw < varMin) varMin else vRaw

            var fg = if (d * d > k * k * v) 1 else 0

            // Rechazo de sombra: oscurece de forma proporcional sin cambiar el
            // tono. Sin esto cada objeto arrastra su sombra y sale con el doble
            // de ancho, lo que arruina silueta y estatura.
            if (fg == 1 && d < 0 && mu > 12 && (y / mu) > shadowRatio) fg = 0

            mask[i] = fg.toByte()
            count += fg

            if (learn) {
                // Más despacio donde hay objeto: congelarlo del todo deja un
                // objeto parado marcado para siempre; actualizarlo igual lo
                // disuelve en segundos.
                val rate = if (fg == 1) a * 0.05 else a
                mean[i] = (mu + rate * d).toFloat()
                vari[i] = (v + rate * (d * d - v)).toFloat()
            }
            p += 4
        }
        frames++
        fgRatio = count.toDouble() / n
        if (!ready) java.util.Arrays.fill(mask, 0)
        return mask
    }

    fun reset() {
        java.util.Arrays.fill(mean, 0f)
        java.util.Arrays.fill(vari, 90f)
        frames = 0
    }
}

/* ------------------------------------------------------------------ */
/*  Morfología                                                         */
/* ------------------------------------------------------------------ */

/**
 * Erosión/dilatación separables con suma corrida: O(1) por píxel sea cual sea
 * el radio, porque sobre una máscara binaria las dos operaciones se reducen a
 * contar. Era el 39 % del tiempo de frame en la versión original.
 *
 * Los bordes replican el píxel del borde. Con ceros, una erosión se come una
 * franja del ancho del radio en los cuatro lados.
 */
fun morph(mask: ByteArray, w: Int, h: Int, radius: Int, dilate: Boolean, scratch: ByteArray): ByteArray {
    val win = 2 * radius + 1
    fun cw(v: Int) = if (v < 0) 0 else if (v >= w) w - 1 else v
    fun ch(v: Int) = if (v < 0) 0 else if (v >= h) h - 1 else v

    for (y in 0 until h) {
        val row = y * w
        var sum = 0
        for (kk in -radius..radius) sum += mask[row + cw(kk)].toInt()
        for (x in 0 until w) {
            scratch[row + x] = (if (dilate) (if (sum > 0) 1 else 0) else (if (sum == win) 1 else 0)).toByte()
            sum -= mask[row + cw(x - radius)].toInt()
            sum += mask[row + cw(x + radius + 1)].toInt()
        }
    }
    for (x in 0 until w) {
        var sum = 0
        for (kk in -radius..radius) sum += scratch[ch(kk) * w + x].toInt()
        for (y in 0 until h) {
            mask[y * w + x] = (if (dilate) (if (sum > 0) 1 else 0) else (if (sum == win) 1 else 0)).toByte()
            sum -= scratch[ch(y - radius) * w + x].toInt()
            sum += scratch[ch(y + radius + 1) * w + x].toInt()
        }
    }
    return mask
}

fun openMask(m: ByteArray, w: Int, h: Int, r: Int, s: ByteArray): ByteArray =
    morph(morph(m, w, h, r, false, s), w, h, r, true, s)

fun closeMask(m: ByteArray, w: Int, h: Int, r: Int, s: ByteArray): ByteArray =
    morph(morph(m, w, h, r, true, s), w, h, r, false, s)

/* ------------------------------------------------------------------ */
/*  Componentes conexas                                                */
/* ------------------------------------------------------------------ */

class Region(var x1: Int, var y1: Int, var x2: Int, var y2: Int) {
    var area = 0
    var fill = 0.0
    var bw = 0
    var bh = 0
}

/**
 * Etiquetado por union-find sobre runs horizontales: un tramo de 40 píxeles es
 * una sola unión en vez de cuarenta.
 */
fun components(mask: ByteArray, w: Int, h: Int, minArea: Int): List<Region> {
    var parent = IntArray(1024) { it }
    var nLabels = 1

    fun find(a0: Int): Int {
        var a = a0
        while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
        return a
    }
    fun union(a0: Int, b0: Int) {
        val a = find(a0); val b = find(b0)
        if (a != b) parent[b] = a
    }

    val labels = IntArray(w * h)
    var prevRuns: MutableList<IntArray> = mutableListOf()

    for (y in 0 until h) {
        val row = y * w
        val runs = mutableListOf<IntArray>()
        var x = 0
        while (x < w) {
            while (x < w && mask[row + x].toInt() == 0) x++
            if (x >= w) break
            val start = x
            while (x < w && mask[row + x].toInt() != 0) x++
            val end = x - 1

            var label = 0
            for (pr in prevRuns) {
                val ps = pr[0]; val pe = pr[1]; val pl = pr[2]
                if (pe < start - 1 || ps > end + 1) continue
                if (label == 0) label = pl else union(label, pl)
            }
            if (label == 0) {
                label = nLabels++
                // El original reiniciaba al desbordar; aquí basta con crecer,
                // que es lo que aquel comentario llamaba «improbable».
                if (label >= parent.size) {
                    val grown = IntArray(parent.size * 2) { if (it < parent.size) parent[it] else it }
                    parent = grown
                }
                parent[label] = label
            }
            for (i in start..end) labels[row + i] = label
            runs.add(intArrayOf(start, end, label))
        }
        prevRuns = runs
    }

    val stats = LinkedHashMap<Int, Region>()
    for (y in 0 until h) for (x in 0 until w) {
        val l = labels[y * w + x]
        if (l == 0) continue
        val r = find(l)
        val s = stats.getOrPut(r) { Region(x, y, x, y) }
        if (x < s.x1) s.x1 = x
        if (x > s.x2) s.x2 = x
        if (y < s.y1) s.y1 = y
        if (y > s.y2) s.y2 = y
        s.area++
    }

    val out = mutableListOf<Region>()
    for (s in stats.values) {
        if (s.area < minArea) continue
        val bw = s.x2 - s.x1 + 1
        val bh = s.y2 - s.y1 + 1
        // El relleno separa un objeto real de un artefacto disperso de
        // iluminación, que da cajas grandes casi vacías.
        s.fill = s.area.toDouble() / (bw * bh)
        if (s.fill < 0.22) continue
        s.bw = bw; s.bh = bh
        out.add(s)
    }
    return out
}

/* ------------------------------------------------------------------ */
/*  Seguimiento                                                        */
/* ------------------------------------------------------------------ */

class Det(val x1: Double, val y1: Double, val x2: Double, val y2: Double,
          val fill: Double = 1.0, val klass: String? = null)

class Track(val id: Int, var x1: Double, var y1: Double, var x2: Double, var y2: Double) {
    var vx = 0.0; var vy = 0.0
    var hits = 1; var miss = 0; var age = 1
    var klass = "object"
    /** "det" si la clase la dio el detector; "forma" si sale de la proporción. */
    var klassSrc = "forma"
    val votes = HashMap<String, Double>()
    var obs = 0
    var px1 = 0.0; var py1 = 0.0; var px2 = 0.0; var py2 = 0.0
}

/**
 * Clasificación por proporción de caja. Cruda a propósito y etiquetada como tal:
 * una resta de fondo no sabe qué es un objeto, solo que se mueve.
 */
fun classify(x1: Double, y1: Double, x2: Double, y2: Double): String {
    val w = maxOf(1.0, x2 - x1)
    val h = maxOf(1.0, y2 - y1)
    val ar = h / w
    return if (ar > 1.5) "person" else if (ar < 0.65) "car" else "object"
}

private fun iou(ax1: Double, ay1: Double, ax2: Double, ay2: Double,
                bx1: Double, by1: Double, bx2: Double, by2: Double): Double {
    val x1 = maxOf(ax1, bx1); val y1 = maxOf(ay1, by1)
    val x2 = minOf(ax2, bx2); val y2 = minOf(ay2, by2)
    val iw = x2 - x1; val ih = y2 - y1
    if (iw <= 0 || ih <= 0) return 0.0
    val inter = iw * ih
    val areaA = (ax2 - ax1) * (ay2 - ay1)
    val areaB = (bx2 - bx1) * (by2 - by1)
    return inter / (areaA + areaB - inter)
}

/**
 * Asociación voraz por IoU con predicción de velocidad constante.
 *
 * Voraz y no húngaro a propósito: con cámara fija el solape entre frames
 * consecutivos es tan alto que voraz y óptimo coinciden casi siempre, y un
 * húngaro en el bucle de tiempo real cuesta tirones visibles.
 */
class Tracker(
    private val matchIoU: Double = 0.18,
    private val maxAge: Int = 20,
    val minHits: Int = 4,
    private val maxTracks: Int = 60,
) {
    var tracks = mutableListOf<Track>()
    var finished = mutableListOf<Track>()
    private var nextId = 1
    private var frameW = 0
    private var frameH = 0

    fun setFrameSize(w: Int, h: Int) { frameW = w; frameH = h }

    fun step(frameIdx: Int, dets: List<Det>): List<Track> {
        for (t in tracks) {
            t.age++; t.miss++
            t.px1 = t.x1 + t.vx; t.py1 = t.y1 + t.vy
            t.px2 = t.x2 + t.vx; t.py2 = t.y2 + t.vy
        }

        val pairs = mutableListOf<Triple<Double, Int, Int>>()
        for (ti in tracks.indices) for (di in dets.indices) {
            val t = tracks[ti]; val d = dets[di]
            val s = iou(t.px1, t.py1, t.px2, t.py2, d.x1, d.y1, d.x2, d.y2)
            if (s >= matchIoU) pairs.add(Triple(s, ti, di))
        }
        // `sort` de JavaScript es estable desde ES2019, y `sortedByDescending`
        // de Kotlin también lo es: los empates conservan el orden de inserción
        // en los dos. De ahí que las asociaciones coincidan.
        pairs.sortByDescending { it.first }

        val usedT = HashSet<Int>(); val usedD = HashSet<Int>()
        for ((_, ti, di) in pairs) {
            if (ti in usedT || di in usedD) continue
            usedT.add(ti); usedD.add(di)
            hit(tracks[ti], frameIdx, dets[di])
        }

        for (di in dets.indices) {
            if (di in usedD) continue
            if (tracks.size >= maxTracks) break
            spawn(frameIdx, dets[di])
        }

        val alive = mutableListOf<Track>()
        for (t in tracks) {
            if (t.miss > maxAge) retire(t) else alive.add(t)
        }
        tracks = alive
        return tracks.filter { it.hits >= minHits }
    }

    /**
     * Voto de clase ponderado por área, ignorando cajas que tocan el borde.
     *
     * La última caja de cualquier objeto es la rodaja estrecha que queda al
     * salir del cuadro, y una rodaja siempre tiene proporción de peatón: sin
     * esta exclusión un coche cruzando acababa etiquetado como persona
     * prácticamente siempre.
     */
    private fun vote(t: Track, d: Det) {
        val touching = d.x1 <= 1 || d.y1 <= 1 ||
            (frameW != 0 && d.x2 >= frameW - 1) || (frameH != 0 && d.y2 >= frameH - 1)
        if (touching) return
        val area = (d.x2 - d.x1) * (d.y2 - d.y1)
        val kk = classify(d.x1, d.y1, d.x2, d.y2)
        t.votes[kk] = (t.votes[kk] ?: 0.0) + area
        var best: String? = null; var top = -1.0
        for ((key, v) in t.votes) if (v > top) { top = v; best = key }
        if (best != null) t.klass = best
        t.klassSrc = "forma"
    }

    private fun hit(t: Track, frameIdx: Int, d: Det) {
        val cxOld = (t.x1 + t.x2) / 2; val cyOld = (t.y1 + t.y2) / 2
        val cxNew = (d.x1 + d.x2) / 2; val cyNew = (d.y1 + d.y2) / 2
        // Velocidad suavizada: la caja de una resta de fondo tiembla, y derivar
        // el ruido da predicciones peores que no predecir.
        t.vx = 0.65 * t.vx + 0.35 * (cxNew - cxOld)
        t.vy = 0.65 * t.vy + 0.35 * (cyNew - cyOld)
        t.x1 = d.x1; t.y1 = d.y1; t.x2 = d.x2; t.y2 = d.y2
        t.hits++; t.miss = 0
        if (d.klass != null) { t.klass = d.klass; t.klassSrc = "det" } else vote(t, d)
        t.obs++
    }

    private fun spawn(frameIdx: Int, d: Det) {
        val t = Track(nextId++, d.x1, d.y1, d.x2, d.y2)
        t.klass = d.klass ?: classify(d.x1, d.y1, d.x2, d.y2)
        t.klassSrc = if (d.klass != null) "det" else "forma"
        t.px1 = d.x1; t.py1 = d.y1; t.px2 = d.x2; t.py2 = d.y2
        if (d.klass != null) { t.klass = d.klass; t.klassSrc = "det" } else vote(t, d)
        t.obs++
        tracks.add(t)
    }

    private fun retire(t: Track) {
        if (t.hits >= minHits && t.obs >= minHits) finished.add(t)
    }
}

/* ------------------------------------------------------------------ */
/*  Motor                                                              */
/* ------------------------------------------------------------------ */

class Box(val id: Int, val klass: String, val age: Int,
          val x1: Double, val y1: Double, val x2: Double, val y2: Double,
          val klassSrc: String = "forma")

/** Caja del detector neuronal, en píxeles de la FUENTE (no del análisis). */
class DetBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double,
             val klass: String, val score: Double, val fine: String = klass)

class Result(val boxes: List<Box>, val disturbed: Boolean, val ms: Double)

/**
 * Equivalente de `Engine.analyze` del JS: todo el coste, nada de cámara.
 *
 * En Android los píxeles llegan de CameraX ya reducidos; no hay lienzo, no hay
 * `getImageData` y no hay lectura de GPU. Ésa es toda la razón de ser de esta
 * versión nativa: en el navegador esa lectura era el 75 % del tiempo.
 */
class Engine(
    private val analysisWidth: Int = 320,
    private val minAreaFrac: Double = 0.0006,
) {
    private var w = 0
    private var h = 0
    private var bg: BackgroundModel? = null
    private var scratch = ByteArray(0)
    var tracker = Tracker(); private set
    var frameIdx = 0; private set
    var disturbed = false; private set

    private fun ensure(fw: Int, fh: Int) {
        if (w == fw && h == fh && bg != null) return
        w = fw; h = fh
        bg = BackgroundModel(fw, fh)
        scratch = ByteArray(fw * fh)
        tracker = Tracker()
        frameIdx = 0
    }

    /**
     * @param rgba  Píxeles RGBA al tamaño de análisis.
     * @param srcW  Anchura de la fuente, solo para devolver las cajas en
     *   coordenadas de la fuente: quien dibuja no debería conocer la
     *   resolución interna del análisis.
     */
    fun analyze(rgba: ByteArray, fw: Int, fh: Int, srcW: Int, srcH: Int,
                detections: List<DetBox>? = null, rx: Int = 0, ry: Int = 0): Result {
        val t0 = System.nanoTime()
        ensure(fw, fh)
        val model = bg!!
        val mask = model.update(rgba)

        // Más de un tercio del cuadro en primer plano no son objetos: es que la
        // cámara se ha movido o la luz ha cambiado de golpe. Seguir produciría
        // un tubo gigante de basura.
        if (model.fgRatio > 0.34 && model.frames > 60) {
            model.reset()
            tracker = Tracker()
            disturbed = true
            return Result(emptyList(), true, (System.nanoTime() - t0) / 1e6)
        }
        disturbed = false

        openMask(mask, w, h, 1, scratch)
        closeMask(mask, w, h, 2, scratch)

        val minArea = maxOf(12, Math.round(minAreaFrac * w * h).toInt())
        val regions = components(mask, w, h, minArea)

        val sx = srcW.toDouble() / w
        val sy = srcH.toDouble() / h
        // Con detector, las cajas son las suyas y la clase también; el fondo
        // solo decide qué se mueve. Es el camino que corre la app en cuanto el
        // detector ha visto algo, y el que en JS estuvo roto sin que ninguna
        // paridad lo pisara: todas corrían sin detecciones.
        val dets = if (detections != null) {
            detections.mapNotNull { d ->
                val x1 = maxOf(0.0, (d.x1 - rx) / sx)
                val y1 = maxOf(0.0, (d.y1 - ry) / sy)
                val x2 = minOf(w.toDouble(), (d.x2 - rx) / sx)
                val y2 = minOf(h.toDouble(), (d.y2 - ry) / sy)
                if (x2 - x1 < 1 || y2 - y1 < 1) null else Det(x1, y1, x2, y2, 1.0, d.klass)
            }
        } else regions.map {
            Det(it.x1.toDouble(), it.y1.toDouble(),
                (it.x2 + 1).toDouble(), (it.y2 + 1).toDouble(), it.fill)
        }

        tracker.setFrameSize(w, h)
        val live = tracker.step(frameIdx, dets)
        tracker.finished.clear()
        frameIdx++

        return Result(
            live.map { Box(it.id, it.klass, it.miss, rx + it.x1 * sx, ry + it.y1 * sy,
                           rx + it.x2 * sx, ry + it.y2 * sy, it.klassSrc) },
            false,
            (System.nanoTime() - t0) / 1e6,
        )
    }
}

/* ------------------------------------------------------------------ */
/*  Entrada de cámara                                                  */
/* ------------------------------------------------------------------ */

/**
 * Reduce un frame RGBA de cámara al tamaño de análisis, promediando por área.
 *
 * `rowStride` es la distancia en BYTES entre filas, que en un `ImageProxy` de
 * CameraX casi nunca es `anchura × 4`: los móviles rellenan cada fila hasta un
 * múltiplo que conviene al hardware. Leer con `anchura × 4` desplaza cada fila
 * unos píxeles respecto a la anterior y la imagen sale cizallada; el modelo de
 * fondo no lo nota como error, solo ve una escena que no se parece a nada.
 *
 * Promedio por área y no vecino más cercano: el lienzo del navegador también
 * filtra al reducir, y el ruido de un píxel suelto es justo lo que el modelo de
 * fondo convierte en primer plano falso.
 */
fun downscaleRgba(src: ByteArray, rowStride: Int, sw: Int, sh: Int,
                  dst: ByteArray, dw: Int, dh: Int) {
    require(rowStride >= sw * 4) { "rowStride $rowStride < anchura×4 ${sw * 4}" }
    require(dst.size >= dw * dh * 4) { "destino pequeño" }
    for (y in 0 until dh) {
        val ya = y * sh / dh
        val yb = maxOf(ya + 1, (y + 1) * sh / dh)
        for (x in 0 until dw) {
            val xa = x * sw / dw
            val xb = maxOf(xa + 1, (x + 1) * sw / dw)
            var r = 0; var g = 0; var b = 0
            for (yy in ya until yb) {
                var p = yy * rowStride + xa * 4
                for (xx in xa until xb) {
                    r += src[p].toInt() and 0xFF
                    g += src[p + 1].toInt() and 0xFF
                    b += src[p + 2].toInt() and 0xFF
                    p += 4
                }
            }
            val n = (yb - ya) * (xb - xa)
            val o = (y * dw + x) * 4
            dst[o] = (r / n).toByte(); dst[o + 1] = (g / n).toByte()
            dst[o + 2] = (b / n).toByte(); dst[o + 3] = 255.toByte()
        }
    }
}
