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
    private val varInit: Float = 90f,
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

            // Píxel recién destapado por un desplazamiento de cámara: no hay
            // historia, así que se toma lo que se ve y no opina este frame.
            // Fuera de la cámara en mano nunca ocurre (la media no baja de 0),
            // y la paridad con engine.js no se toca.
            if (mean[i] < 0f) {
                mean[i] = y.toFloat(); vari[i] = varInit; mask[i] = 0
                p += 4; continue
            }
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

    private var tmpMean = FloatArray(0)
    private var tmpVar = FloatArray(0)

    /**
     * Desplaza el modelo para que siga alineado con la escena cuando la cámara
     * se mueve: el contenido que estaba en (x+sx, y+sy) pasa a (x, y). Lo que
     * entra por el borde no tiene historia y se marca para aprenderse de cero.
     */
    fun shift(sx: Int, sy: Int) {
        if (sx == 0 && sy == 0) return
        if (tmpMean.size != mean.size) { tmpMean = FloatArray(mean.size); tmpVar = FloatArray(vari.size) }
        System.arraycopy(mean, 0, tmpMean, 0, mean.size)
        System.arraycopy(vari, 0, tmpVar, 0, vari.size)
        for (y in 0 until h) {
            val oy = y + sy
            for (x in 0 until w) {
                val ox = x + sx
                val i = y * w + x
                if (ox < 0 || oy < 0 || ox >= w || oy >= h) { mean[i] = -1f; vari[i] = varInit }
                else { val j = oy * w + ox; mean[i] = tmpMean[j]; vari[i] = tmpVar[j] }
            }
        }
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

    /** La cámara se movió: todo lo seguido se desplaza con la imagen. */
    fun shift(dx: Double, dy: Double) {
        for (t in tracks) {
            t.x1 += dx; t.x2 += dx; t.y1 += dy; t.y2 += dy
            t.px1 += dx; t.px2 += dx; t.py1 += dy; t.py2 += dy
        }
    }

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
        // La clase del detector no la pisa la proporción de una mancha: entre
        // pasadas la pista vive de la resta de fondo y sin esta guarda la
        // primera mancha la rebautizaría por su forma.
        if (t.klassSrc == "det") return
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
             val klass: String, val score: Double, val fine: String = klass) {
    fun shifted(dx: Double, dy: Double) = DetBox(x1 + dx, y1 + dy, x2 + dx, y2 + dy, klass, score, fine)
}

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

    /**
     * Compensar el movimiento de la cámara. Apagado por defecto: así el núcleo
     * sigue siendo, bit a bit, `engine.js`, y la paridad lo vigila. La app lo
     * enciende porque un móvil casi nunca está quieto.
     */
    var egoMotion = false
    private var motion: CameraMotion? = null
    /** La cámara hace algo que la compensación de traslación no explica (giro,
     *  temblor, escena ambigua): se congela el aprendizaje y NO se emiten
     *  pistas de «movimiento» inventadas. Con histéresis para no parpadear. */
    var cameraUnstable = false; private set
    /** Un solo frame: la cámara acaba de asentarse tras moverse. El cerebro
     *  reinicia la escala para el nuevo encuadre. */
    var justSettled = false; private set
    private var unstableRun = 0
    private var stillRun = 0
    private var wasMoving = false
    /** Desplazamiento acumulado del contenido, en píxeles de análisis. */
    var camX = 0.0; private set
    var camY = 0.0; private set
    private var lastMove = -1000
    /** La cámara se ha movido en el último medio segundo, más o menos. */
    val cameraMoving: Boolean get() = frameIdx - lastMove < 15 || cameraUnstable
    val analysisW: Int get() = w
    /** Luma del modelo de fondo, a la resolución del análisis (solo lectura, para quien mire fuera del núcleo). */
    val fondo: FloatArray? get() = bg?.mean

    private fun ensure(fw: Int, fh: Int) {
        if (w == fw && h == fh && bg != null) return
        w = fw; h = fh
        bg = BackgroundModel(fw, fh)
        motion = CameraMotion(fw, fh)
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
        justSettled = false
        var suppressForma = false
        var justShifted = false
        if (egoMotion && model.frames > 25) {
            val m = motion!!
            when (m.estimate(rgba, model.mean)) {
                CameraMotion.State.MOVED -> {
                    model.shift(m.sx, m.sy)
                    // Lo que estaba en x+sx está ahora en x: las pistas se mueven
                    // con la imagen, o la asociación las perdería en cada paso.
                    tracker.shift(-m.sx.toDouble(), -m.sy.toDouble())
                    camX += m.sx; camY += m.sy
                    lastMove = frameIdx
                    unstableRun = 0; stillRun = 0; wasMoving = true
                    justShifted = true
                }
                CameraMotion.State.UNSTABLE -> {
                    // No se puede compensar un giro o un temblor con una sola
                    // traslación. En vez de mover el fondo a ciegas —y emitir la
                    // habitación entera como «movimiento», que es lo que se veía
                    // con el móvil en la mano— se congela: el detector sigue
                    // diciendo QUÉ hay, pero la resta de fondo no inventa nada.
                    lastMove = frameIdx; stillRun = 0; wasMoving = true
                    if (++unstableRun >= 3) cameraUnstable = true
                }
                CameraMotion.State.STILL -> {
                    unstableRun = 0
                    if (cameraUnstable) { if (++stillRun >= 3) cameraUnstable = false }
                    if (wasMoving && !cameraUnstable && ++stillRun >= 8) {
                        // Encuadre nuevo y estable: la escala del anterior ya no
                        // vale. Se avisa una vez para rehacerla desde cero.
                        wasMoving = false; justSettled = true
                    }
                }
            }
        }
        if (cameraUnstable) suppressForma = true
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

        // Tras compensar un paneo queda un desalineo subpíxel que deja tiras
        // finas de primer plano en los bordes. Subiendo el área mínima esas
        // tiras no llegan a pista, pero un objeto sólido (una persona) sí.
        val minArea = maxOf(12, Math.round(minAreaFrac * w * h).toInt() * (if (justShifted || cameraMoving) 4 else 1))
        val regions = components(mask, w, h, minArea)

        val sx = srcW.toDouble() / w
        val sy = srcH.toDouble() / h
        // Con detector, las cajas son las suyas y la clase también; el fondo
        // solo decide qué se mueve. Es el camino que corre la app en cuanto el
        // detector ha visto algo, y el que en JS estuvo roto sin que ninguna
        // paridad lo pisara: todas corrían sin detecciones.
        val dets = if (detections != null) {
            val fromDet = detections.mapNotNull { d ->
                val x1 = maxOf(0.0, (d.x1 - rx) / sx)
                val y1 = maxOf(0.0, (d.y1 - ry) / sy)
                val x2 = minOf(w.toDouble(), (d.x2 - rx) / sx)
                val y2 = minOf(h.toDouble(), (d.y2 - ry) / sy)
                if (x2 - x1 < 1 || y2 - y1 < 1) null else Det(x1, y1, x2, y2, 1.0, d.klass)
            }
            // Lo que se mueve y el detector no ha visto sigue existiendo: se
            // conservan las regiones que no caen mayormente dentro de una caja
            // del detector, como «movimiento». Salvo con la cámara inestable:
            // ahí la resta de fondo es ruido y solo valen las cajas del detector.
            val out = ArrayList(fromDet)
            for (r in if (suppressForma) emptyList() else regions) {
                val rx1 = r.x1.toDouble(); val ry1 = r.y1.toDouble()
                val rx2 = (r.x2 + 1).toDouble(); val ry2 = (r.y2 + 1).toDouble()
                val ra = (rx2 - rx1) * (ry2 - ry1)
                val dentro = fromDet.any { d ->
                    val iw = minOf(rx2, d.x2) - maxOf(rx1, d.x1)
                    val ih = minOf(ry2, d.y2) - maxOf(ry1, d.y1)
                    iw > 0 && ih > 0 && iw * ih >= 0.3 * ra
                }
                if (!dentro) out.add(Det(rx1, ry1, rx2, ry2, r.fill))
            }
            out
        } else if (suppressForma) emptyList() else regions.map {
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

/* ------------------------------------------------------------------ */
/*  Movimiento de la cámara                                            */
/* ------------------------------------------------------------------ */

/**
 * Cuánto se ha desplazado la imagen respecto al modelo de fondo.
 *
 * Con el móvil en la mano TODO se mueve, y una resta de fondo que no lo sabe
 * marca la habitación entera como «movimiento» (medido: 106 de 150 frames con
 * pistas falsas en un paneo sin un solo objeto). Aquí se busca el
 * desplazamiento entero (sx, sy) que mejor alinea el frame actual con la MEDIA
 * del fondo, no con el frame anterior: registrar contra el frame anterior
 * acumula el error de redondeo de cada paso y el fondo se va despegando; contra
 * el fondo, el error nunca pasa de medio píxel.
 *
 * Búsqueda gruesa a 1/4 de resolución y refinado a resolución completa, con
 * diferencia absoluta truncada: un objeto que se mueve de verdad no debe
 * arrastrar la estimación.
 */
class CameraMotion(private val w: Int, private val h: Int, private val range: Int = 24) {
    /** QUIETA: nada que compensar (o escena demasiado lisa para saberlo).
     *  MOVIDA: traslación limpia, se compensa. INESTABLE: la imagen cambió y
     *  ninguna traslación la explica (giro, temblor, patrón repetido). */
    enum class State { STILL, MOVED, UNSTABLE }

    private val luma = FloatArray(w * h)
    var sx = 0; private set
    var sy = 0; private set

    /**
     * Fracción de píxeles que NO casan con el fondo tras el desplazamiento
     * (dx, dy). No la media de la diferencia: un objeto grande que se mueve
     * contribuye solo su área (una minoría), así que no arrastra la estimación
     * hacia su desplazamiento. Un paneo verdadero casa casi todo y da ~0.
     */
    /**
     * Fracción de píxeles que NO casan con el fondo tras el desplazamiento
     * (dx, dy), contando solo filas en [y0, y1). No la media de la diferencia:
     * un objeto grande que se mueve contribuye solo su área (una minoría) y no
     * arrastra la estimación; una traslación verdadera casa casi todo y da ~0.
     */
    private fun cost(ref: FloatArray, dx: Int, dy: Int, step: Int, y0: Int, y1: Int,
                     x0: Int = 0, x1: Int = w): Double {
        var mal = 0; var n = 0
        val m = range + 2
        var y = maxOf(m, y0)
        val yf = minOf(h - m, y1)
        while (y < yf) {
            val ry = y + dy
            var x = maxOf(m, x0)
            val xf = minOf(w - m, x1)
            while (x < xf) {
                if (Math.abs(luma[y * w + x] - ref[ry * w + x + dx]) > 18f) mal++
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 1.0 else mal.toDouble() / n
    }

    private fun texture(): Double {
        var s = 0.0; var n = 0
        // La diferencia se toma a la misma distancia que el paso de muestreo.
        // Con vecinos inmediatos y paso 3, una textura de periodo múltiplo de 3
        // cae entera entre muestras: bloques de 6 px dieron 0,22 en ciertos
        // desplazamientos, la escena se declaró lisa y el paneo no se compensó.
        var y = 2
        while (y < h - 5) {
            var x = 2
            while (x < w - 5) {
                val i = y * w + x
                s += Math.abs(luma[i + 3] - luma[i]) + Math.abs(luma[i + 3 * w] - luma[i])
                n++; x += 3
            }
            y += 3
        }
        return if (n == 0) 0.0 else s / n
    }

    /** Mejor desplazamiento entero en una banda de filas: grueso y luego fino. */
    private fun mejorEnBanda(ref: FloatArray, y0: Int, y1: Int): IntArray {
        var best = cost(ref, 0, 0, 4, y0, y1); var bx = 0; var by = 0
        var dy = -range
        while (dy <= range) {
            var dx = -range
            while (dx <= range) {
                val c = cost(ref, dx, dy, 4, y0, y1)
                if (c < best) { best = c; bx = dx; by = dy }
                dx += 4
            }
            dy += 4
        }
        val cx = bx; val cy = by
        for (ddy in -3..3) for (ddx in -3..3) {
            val dx2 = cx + ddx; val dy2 = cy + ddy
            if (Math.abs(dx2) > range || Math.abs(dy2) > range) continue
            val c = cost(ref, dx2, dy2, 2, y0, y1)
            if (c < best) { best = c; bx = dx2; by = dy2 }
        }
        return intArrayOf(bx, by)
    }

    fun estimate(rgba: ByteArray, ref: FloatArray): State {
        var p = 0
        for (i in 0 until w * h) {
            val r = rgba[p].toInt() and 0xFF; val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            luma[i] = ((r * 77 + g * 150 + b * 29) shr 8).toFloat()
            p += 4
        }
        sx = 0; sy = 0
        // Escena sin textura (una pared): no se puede saber si se ha movido.
        // Se declara QUIETA en vez de inventar un movimiento. Medido: sin esto,
        // «cámara en mano» en 368 de 400 frames sobre una pared lisa fija.
        if (texture() < 3.0) return State.STILL

        val c0 = cost(ref, 0, 0, 2, 0, h)
        // El desplazamiento se estima por separado en la mitad de arriba y la de
        // abajo. Una TRASLACIÓN mueve las dos igual; un GIRO de muñeca las mueve
        // en sentidos opuestos. Así se distingue un paneo (que se compensa) de
        // un giro o un temblor (que no se puede compensar con una traslación).
        val top = mejorEnBanda(ref, range + 2, h / 2)
        val bot = mejorEnBanda(ref, h / 2, h - range - 2)
        val coinciden = Math.abs(top[0] - bot[0]) <= 2 && Math.abs(top[1] - bot[1]) <= 2
        val bx = Math.round((top[0] + bot[0]) / 2.0).toInt()
        val by = Math.round((top[1] + bot[1]) / 2.0).toInt()
        val best = cost(ref, bx, by, 2, 0, h)

        val movido = bx != 0 || by != 0
        val mejora = best < c0 - 0.08       // una minoría (un objeto) no mueve el total
        // La traslación explica casi todo el cuadro. Un paso entero deja hasta
        // medio píxel de residuo, que en una textura fina pasa del 18 %: se
        // acepta también si el desajuste baja a menos de dos tercios.
        val limpio = best < 0.18 || (best < 0.30 && best < c0 * 0.65)
        if (movido && coinciden && mejora && limpio) { sx = bx; sy = by; return State.MOVED }
        // Mucho del cuadro no casa y ninguna traslación lo explica: giro,
        // temblor o ambigüedad. No se compensa y se avisa de inestabilidad.
        // Pero solo si el desajuste está repartido: un autobús que pasa cerca
        // ocupa mucho cuadro y no casa, pero deja quieta la mayor parte. Se
        // parte en 4×4 y hace falta desajuste en más de la mitad de las celdas.
        if (c0 > 0.22) {
            var revueltos = 0
            for (qy in 0..3) for (qx in 0..3)
                if (cost(ref, 0, 0, 2, qy * h / 4, (qy + 1) * h / 4, qx * w / 4, (qx + 1) * w / 4) > 0.15) revueltos++
            if (revueltos >= 11) return State.UNSTABLE
        }
        return State.STILL
    }
}
