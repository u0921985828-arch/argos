package eus.argos.core

/**
 * Que no se pierda lo que ya se ha detectado.
 *
 * Entre dos pasadas del detector (en teselas, un segundo o más en un móvil)
 * la pista vive de la resta de fondo. En las cámaras de tráfico eso falla:
 * un coche lejano son pocos píxeles y la mancha no llega al tamaño mínimo, o
 * uno rápido se sale de su sitio y el seguimiento lo suelta; luego vuelve a
 * salir con otro número. Se veía «a ratos».
 *
 * Aquí, cada objeto que dio el DETECTOR (persona, vehículo, bici) se sigue en
 * CADA imagen aunque el núcleo lo pierda: se predice dónde estará con su
 * velocidad y se busca su aspecto (una plantilla en grises) alrededor. Si se
 * encuentra, la caja se mueve ahí; si deja de parecerse o pasan 3 s sin que
 * el detector lo confirme, se suelta. Y cuando el núcleo lo vuelve a
 * encontrar con otro número, conserva el suyo.
 *
 * Trabaja sobre el búfer pequeño del análisis (320 px de ancho): cuesta
 * microsegundos por objeto.
 */
class Continuidad(private val maxS: Double = 3.0, private val buscar: Int = 6, private val umbral: Double = 22.0) {
    private class Obj(val id: Int, val klass: String, var x1: Double, var y1: Double, var x2: Double, var y2: Double,
                      var vx: Double = 0.0, var vy: Double = 0.0, var visto: Double, var fallos: Int = 0,
                      var plantilla: FloatArray = FloatArray(0), var pw: Int = 0, var ph: Int = 0, var paso: Int = 1)

    private val objs = HashMap<Int, Obj>()
    /** Número nuevo del núcleo → número que ya tenía el objeto. */
    private val alias = HashMap<Int, Int>()
    private var gris = FloatArray(0)
    private var aw = 0; private var ah = 0

    val seguidos: Int get() = objs.size

    private fun luma(rgba: ByteArray, w: Int, h: Int) {
        if (gris.size != w * h) gris = FloatArray(w * h)
        for (i in 0 until w * h) {
            val k = i * 4
            gris[i] = (0.299f * (rgba[k].toInt() and 255) + 0.587f * (rgba[k + 1].toInt() and 255) + 0.114f * (rgba[k + 2].toInt() and 255))
        }
        aw = w; ah = h
    }

    private fun recorta(o: Obj) {
        val w = (o.x2 - o.x1).toInt().coerceAtLeast(2); val h = (o.y2 - o.y1).toInt().coerceAtLeast(2)
        o.paso = maxOf(1, maxOf(w, h) / 24)
        o.pw = w / o.paso; o.ph = h / o.paso
        if (o.pw < 2 || o.ph < 2) { o.plantilla = FloatArray(0); return }
        val p = FloatArray(o.pw * o.ph)
        val x0 = o.x1.toInt(); val y0 = o.y1.toInt()
        for (j in 0 until o.ph) for (i in 0 until o.pw) {
            val x = (x0 + i * o.paso).coerceIn(0, aw - 1); val y = (y0 + j * o.paso).coerceIn(0, ah - 1)
            p[j * o.pw + i] = gris[y * aw + x]
        }
        o.plantilla = p
    }

    /** Diferencia media por píxel de la plantilla con la imagen, con la esquina en (x0, y0). */
    private fun dif(o: Obj, x0: Int, y0: Int, cota: Double): Double {
        var s = 0.0; val n = o.pw * o.ph; val lim = cota * n
        for (j in 0 until o.ph) {
            val y = y0 + j * o.paso
            if (y < 0 || y >= ah) return Double.MAX_VALUE
            for (i in 0 until o.pw) {
                val x = x0 + i * o.paso
                if (x < 0 || x >= aw) return Double.MAX_VALUE
                s += Math.abs(gris[y * aw + x] - o.plantilla[j * o.pw + i])
            }
            if (s > lim) return Double.MAX_VALUE
        }
        return s / n
    }

    private fun iou(a: Obj, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val ix = maxOf(0.0, minOf(a.x2, x2) - maxOf(a.x1, x1)); val iy = maxOf(0.0, minOf(a.y2, y2) - maxOf(a.y1, y1))
        val i = ix * iy; val u = (a.x2 - a.x1) * (a.y2 - a.y1) + (x2 - x1) * (y2 - y1) - i
        return if (u <= 0) 0.0 else i / u
    }

    /**
     * Un frame. `cajas` en píxeles de la fuente; `rgba` el búfer del análisis
     * (`fw`×`fh`); `sc` = ancho de la fuente / ancho del análisis. Devuelve las
     * cajas del núcleo (con el número de siempre) más las que el núcleo perdió
     * y aquí se siguen.
     */
    fun paso(cajas: List<Box>, rgba: ByteArray, fw: Int, fh: Int, sc: Double, t: Double): List<Box> {
        luma(rgba, fw, fh)
        val presentes = HashSet<Int>()
        val salida = ArrayList<Box>(cajas.size + objs.size)
        for (b in cajas) {
            if (b.klassSrc != "det" || b.klass !in CLASES) { salida.add(b); continue }
            val x1 = b.x1 / sc; val y1 = b.y1 / sc; val x2 = b.x2 / sc; val y2 = b.y2 / sc
            var id = alias[b.id] ?: b.id
            if (!objs.containsKey(id)) {
                // ¿Es uno que ya se seguía y el núcleo ha renumerado?
                objs.values.filter { it.klass == b.klass && it.id !in presentes && it.id != b.id }
                    .maxByOrNull { iou(it, x1, y1, x2, y2) }?.takeIf { iou(it, x1, y1, x2, y2) > 0.25 }
                    ?.let { alias[b.id] = it.id; id = it.id }
            }
            val o = objs[id]
            if (o == null) objs[id] = Obj(id, b.klass, x1, y1, x2, y2, visto = t).also { recorta(it) }
            else {
                val cx = (x1 + x2) / 2 - (o.x1 + o.x2) / 2; val cy = (y1 + y2) / 2 - (o.y1 + o.y2) / 2
                o.vx = 0.6 * o.vx + 0.4 * cx; o.vy = 0.6 * o.vy + 0.4 * cy
                o.x1 = x1; o.y1 = y1; o.x2 = x2; o.y2 = y2; o.visto = t; o.fallos = 0
                recorta(o)
            }
            presentes.add(id)
            salida.add(if (id == b.id) b else Box(id, b.klass, b.age, b.x1, b.y1, b.x2, b.y2, b.klassSrc))
        }
        val it = objs.values.iterator()
        val fuera = ArrayList<Int>()
        while (it.hasNext()) {
            val o = it.next()
            if (o.id in presentes) continue
            // Lo perdió el núcleo: se predice y se busca su aspecto alrededor.
            if (t - o.visto > maxS || o.plantilla.isEmpty()) { fuera.add(o.id); continue }
            val px = o.x1 + o.vx; val py = o.y1 + o.vy
            val r = buscar + Math.ceil(Math.hypot(o.vx, o.vy) * 0.5).toInt()
            var mejor = Double.MAX_VALUE; var bx = 0; var by = 0
            for (dy in -r..r) for (dx in -r..r) {
                val d = dif(o, px.toInt() + dx, py.toInt() + dy, minOf(mejor, umbral * 2))
                if (d < mejor) { mejor = d; bx = dx; by = dy }
            }
            if (mejor > umbral) {
                // No se parece: un par de imágenes con la predicción, luego se suelta.
                o.fallos++
                if (o.fallos > 3) { fuera.add(o.id); continue }
                o.x1 = px; o.x2 += o.vx; o.y1 = py; o.y2 += o.vy
            } else {
                val nx = px.toInt() + bx - o.x1; val ny = py.toInt() + by - o.y1
                o.vx = 0.7 * o.vx + 0.3 * nx; o.vy = 0.7 * o.vy + 0.3 * ny
                o.x1 += nx; o.x2 += nx; o.y1 += ny; o.y2 += ny
                o.fallos = 0
            }
            if (o.x2 < 0 || o.y2 < 0 || o.x1 > fw || o.y1 > fh) { fuera.add(o.id); continue }
            salida.add(Box(o.id, o.klass, 0, o.x1 * sc, o.y1 * sc, o.x2 * sc, o.y2 * sc, "det"))
        }
        fuera.forEach { objs.remove(it) }
        alias.values.retainAll(objs.keys)
        return salida
    }

    fun reinicia() { objs.clear(); alias.clear() }

    companion object { val CLASES = setOf("person", "vehicle", "bike") }
}
