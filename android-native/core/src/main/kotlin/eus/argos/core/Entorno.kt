package eus.argos.core

/**
 * Primero se aprende el entorno; después se busca donde toca.
 *
 * Una cámara fija de tráfico no tiene coches en el cielo ni en el césped:
 * pasan por los carriles, y entran por los mismos sitios. Aquí se lleva un
 * mapa de dónde ha visto el detector cosas (rejilla de 16×9, con olvido
 * lento) y, cuando ya hay bastante visto, cada pasada en teselas mira solo:
 *  1. las teselas donde hay algo que se está siguiendo (que no se pierda),
 *  2. las de más actividad aprendida (por donde vienen),
 *  3. y una más del resto, por turno, para no quedarse ciego a lo nuevo.
 * Cada `barridoCada` pasadas se mira el cuadro entero igualmente. Así una
 * pasada son 3-5 inferencias en vez de 12-24, y lo que se sigue se refresca
 * varias veces más por segundo. Hasta que no hay entorno aprendido, todo.
 *
 * La atención SOLO a lo conocido está medida como mala (40-50 % del recall,
 * «atención foveal», resultados negativos): por eso el barrido completo
 * periódico y la tesela de exploración no se quitan.
 */
class Entorno(val gx: Int = 16, val gy: Int = 9) {
    val mapa = DoubleArray(gx * gy)
    var vistos = 0; private set
    private var turno = 0

    val aprendido: Boolean get() = vistos >= 40

    fun anota(dets: List<DetBox>, w: Int, h: Int) {
        if (dets.isEmpty() || w <= 0 || h <= 0) return
        for (i in mapa.indices) mapa[i] *= 0.999
        for (d in dets) {
            val cx = ((d.x1 + d.x2) / 2 / w * gx).toInt().coerceIn(0, gx - 1)
            val cy = ((d.y1 + d.y2) / 2 / h * gy).toInt().coerceIn(0, gy - 1)
            mapa[cy * gx + cx] += 1.0
            vistos++
        }
    }

    private fun actividad(t: IntArray, w: Int, h: Int): Double {
        var s = 0.0
        val x0 = (t[0].toDouble() / w * gx).toInt(); val x1 = Math.ceil(t[2].toDouble() / w * gx).toInt().coerceAtMost(gx)
        val y0 = (t[1].toDouble() / h * gy).toInt(); val y1 = Math.ceil(t[3].toDouble() / h * gy).toInt().coerceAtMost(gy)
        for (y in y0 until y1) for (x in x0 until x1) s += mapa[y * gx + x]
        return s
    }

    /**
     * De todas las teselas, las que tocan en esta pasada. `seguidos` en las
     * mismas coordenadas que las teselas. `pasada` cuenta las pasadas.
     */
    fun plan(todas: List<IntArray>, w: Int, h: Int, seguidos: List<DetBox>, pasada: Int,
             presupuesto: Int = 4, barridoCada: Int = 5): List<IntArray> {
        if (!aprendido || todas.size <= presupuesto || pasada % barridoCada == 0) return todas
        val elegidas = LinkedHashSet<IntArray>()
        for (s in seguidos) {
            val cx = (s.x1 + s.x2) / 2; val cy = (s.y1 + s.y2) / 2
            todas.filter { cx >= it[0] && cx < it[2] && cy >= it[1] && cy < it[3] }
                .maxByOrNull { minOf(cx - it[0], it[2] - cx) + minOf(cy - it[1], it[3] - cy) }?.let { elegidas.add(it) }
        }
        for (t in todas.sortedByDescending { actividad(it, w, h) }) {
            if (elegidas.size >= presupuesto) break
            if (actividad(t, w, h) > 0.5) elegidas.add(t)
        }
        val resto = todas.filter { it !in elegidas }
        if (resto.isNotEmpty()) elegidas.add(resto[turno++ % resto.size])
        return elegidas.toList()
    }

    fun serializa(): String = "$gx $gy $vistos\n" + mapa.joinToString(" ") { "%.3f".format(java.util.Locale.ROOT, it) }

    fun lee(s: String) {
        val l = s.lines(); if (l.size < 2) return
        val c = l[0].trim().split(' '); if (c.size < 3 || c[0].toIntOrNull() != gx || c[1].toIntOrNull() != gy) return
        val v = l[1].trim().split(' ').mapNotNull { it.toDoubleOrNull() }
        if (v.size != mapa.size) return
        v.forEachIndexed { i, x -> mapa[i] = x }
        vistos = c[2].toIntOrNull() ?: 0
    }
}
