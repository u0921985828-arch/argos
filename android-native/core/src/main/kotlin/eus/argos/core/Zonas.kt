package eus.argos.core

/**
 * Zonas dibujadas sobre la imagen de una cámara, como en los programas de
 * vigilancia de siempre (iSpy y compañía): una zona VIGILADA avisa cuando una
 * persona, un vehículo o una bici ENTRA en ella; una zona IGNORADA (un árbol
 * que se mueve, la carretera de fondo) deja de enseñar lo que pasa dentro.
 *
 * Los puntos van normalizados (0-1) sobre la imagen: valen aunque cambie la
 * resolución del vídeo. Lo que cuenta es dónde PISA el objeto (el centro de
 * la base de su caja), no la caja entera: un coche que pasa por delante de la
 * puerta no ha entrado por ella.
 */
class Zona(val nombre: String, val vigilar: Boolean, val puntos: List<DoubleArray>) {
    /** Punto en polígono (cruce de rayos). */
    fun contiene(x: Double, y: Double): Boolean {
        var dentro = false
        var j = puntos.size - 1
        for (i in puntos.indices) {
            val a = puntos[i]; val b = puntos[j]
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) dentro = !dentro
            j = i
        }
        return dentro
    }
}

object Zonas {
    fun pie(b: Box, w: Int, h: Int) = doubleArrayOf((b.x1 + b.x2) / 2 / w, b.y2 / h)

    /** Fuera lo que pisa dentro de una zona ignorada. */
    fun filtra(cajas: List<Box>, w: Int, h: Int, zonas: List<Zona>): List<Box> {
        val ign = zonas.filter { !it.vigilar }
        if (ign.isEmpty() || w <= 0 || h <= 0) return cajas
        return cajas.filter { b -> val p = pie(b, w, h); ign.none { it.contiene(p[0], p[1]) } }
    }

    /** Una zona por línea: «vigilar|ignorar<TAB>nombre<TAB>x,y x,y …». */
    fun serializa(zonas: List<Zona>): String = zonas.joinToString("\n") { z ->
        (if (z.vigilar) "vigilar" else "ignorar") + "\t" + z.nombre.replace('\t', ' ').replace('\n', ' ') + "\t" +
            z.puntos.joinToString(" ") { "%.4f,%.4f".format(java.util.Locale.ROOT, it[0], it[1]) }
    }

    fun lee(s: String): List<Zona> = s.lines().mapNotNull { l ->
        val c = l.split('\t')
        if (c.size < 3) return@mapNotNull null
        val pts = c[2].trim().split(' ').mapNotNull { p ->
            val xy = p.split(','); if (xy.size != 2) null
            else { val x = xy[0].toDoubleOrNull(); val y = xy[1].toDoubleOrNull(); if (x == null || y == null) null else doubleArrayOf(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)) }
        }
        if (pts.size < 3) null else Zona(c[1], c[0] == "vigilar", pts)
    }
}

/**
 * Avisos de entrada en una zona vigilada. Solo lo que dio el DETECTOR (una
 * mancha de «movimiento» no es una persona) y solo al ENTRAR: quien se queda
 * dentro no vuelve a avisar. Y una misma zona no avisa más de una vez cada
 * `esperaS` segundos, o una cola de coches llenaría el teléfono.
 */
class Alertas(private val esperaS: Double = 30.0) {
    class Aviso(val zona: String, val clase: String, val id: Int)

    private val dentroDe = HashMap<Int, MutableSet<String>>()
    private val ultimo = HashMap<String, Double>()

    fun paso(cajas: List<Box>, w: Int, h: Int, zonas: List<Zona>, t: Double): List<Aviso> {
        val vig = zonas.filter { it.vigilar }
        if (vig.isEmpty() || w <= 0 || h <= 0) { dentroDe.clear(); return emptyList() }
        val out = ArrayList<Aviso>()
        val vivos = HashSet<Int>()
        for (b in cajas) {
            if (b.klassSrc != "det" || b.klass !in setOf("person", "vehicle", "bike")) continue
            vivos.add(b.id)
            val p = Zonas.pie(b, w, h)
            val ahora = vig.filter { it.contiene(p[0], p[1]) }.map { it.nombre }.toSet()
            val antes = dentroDe[b.id] ?: emptySet<String>()
            for (z in ahora - antes) {
                if (t - (ultimo[z] ?: -1e9) >= esperaS) { out.add(Aviso(z, b.klass, b.id)); ultimo[z] = t }
            }
            dentroDe[b.id] = ahora.toMutableSet()
        }
        dentroDe.keys.retainAll(vivos)
        return out
    }
}
