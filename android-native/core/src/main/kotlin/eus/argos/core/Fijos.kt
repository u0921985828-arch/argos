package eus.argos.core

/**
 * Lo que el detector ve siempre en el mismo sitio y nunca se mueve, y no es un
 * objeto: el nombre de la cámara y la hora sobreimpresos, el logotipo, la rosa
 * de los vientos. Medido sobre 110 cámaras públicas: de las 150 cajas que solo
 * salían con teselas ×2, unas 30 eran letras o cifras de esos rótulos, con
 * puntuaciones de hasta 0,68 («S» de «BOWERS MANSION», «NB», «03:»). Y como el
 * zoom automático contaba cajas, los rótulos le empujaban a elegir teselas.
 *
 * Los rótulos van en las franjas de arriba y de abajo del cuadro. Ahí, un sitio
 * donde el detector repite caja (misma clase, IoU ≥ 0,6) en al menos el 60 % de
 * las pasadas durante 2 minutos queda FIJO: no entra en el seguimiento, ni en
 * los recuentos, ni en el zoom. No se aplica en el centro del cuadro: allí lo
 * que no se mueve suele ser un coche parado, y un coche parado es un coche.
 * Tampoco a un vehículo grande (≥ 12 % del alto), que no es una letra.
 *
 * Se guarda por cámara: la vez siguiente se descarta desde el primer momento.
 */
class Fijos(val minS: Double = 120.0, val ratio: Double = 0.6, val iouMismo: Double = 0.6,
            val franja: Double = 0.15) {
    class Plaza(var x1: Double, var y1: Double, var x2: Double, var y2: Double, val klass: String,
                var desde: Double, var visto: Double, var veces: Int, var pasadas: Int, var fija: Boolean)

    val plazas = ArrayList<Plaza>()
    val fijas get() = plazas.count { it.fija }

    private fun iou(p: Plaza, d: DetBox): Double {
        val ix = maxOf(0.0, minOf(p.x2, d.x2) - maxOf(p.x1, d.x1)); val iy = maxOf(0.0, minOf(p.y2, d.y2) - maxOf(p.y1, d.y1))
        val i = ix * iy; val u = (p.x2 - p.x1) * (p.y2 - p.y1) + (d.x2 - d.x1) * (d.y2 - d.y1) - i
        return if (u <= 0) 0.0 else i / u
    }

    /** ¿Puede ser un rótulo? En la franja de arriba o de abajo, y no un vehículo grande. */
    fun candidata(d: DetBox, h: Int): Boolean {
        val alto = d.y2 - d.y1
        if (d.klass == "vehicle" && alto >= 0.12 * h) return false
        return d.y2 <= franja * h || d.y1 >= (1 - franja) * h
    }

    /**
     * Una pasada del detector, en coordenadas de la imagen que vio. Devuelve lo
     * que sigue siendo objeto; lo fijo se queda fuera.
     */
    fun filtra(dets: List<DetBox>, h: Int, t: Double): List<DetBox> {
        val tocadas = HashSet<Plaza>()
        val fuera = HashSet<DetBox>()
        for (d in dets) {
            if (!candidata(d, h)) continue
            val p = plazas.filter { it.klass == d.klass && it !in tocadas }.maxByOrNull { iou(it, d) }
                ?.takeIf { iou(it, d) >= iouMismo }
            if (p == null) {
                plazas.add(Plaza(d.x1, d.y1, d.x2, d.y2, d.klass, t, t, 1, 0, false).also { tocadas.add(it) })
                continue
            }
            tocadas.add(p)
            p.x1 += (d.x1 - p.x1) * 0.1; p.y1 += (d.y1 - p.y1) * 0.1; p.x2 += (d.x2 - p.x2) * 0.1; p.y2 += (d.y2 - p.y2) * 0.1
            p.visto = t; p.veces++
            if (p.fija) fuera.add(d)
        }
        val it = plazas.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.pasadas++
            if (!p.fija && t - p.desde >= minS && p.veces >= ratio * p.pasadas) p.fija = true
            // Lo que no se repite se olvida pronto (un coche que pasa por la
            // franja); lo fijo, si deja de verse un día (cambian el rótulo).
            val olvido = if (p.fija) 86400.0 else 15.0
            if (t - p.visto > olvido || (!p.fija && t - p.desde > minS * 3 && p.veces < ratio * p.pasadas)) it.remove()
        }
        if (plazas.size > 300) plazas.sortByDescending { it.veces }.also { while (plazas.size > 300) plazas.removeAt(plazas.size - 1) }
        return dets.filter { it !in fuera }
    }

    /** Solo lo fijo, una línea por plaza; el tiempo se guarda como edad, no como hora. */
    fun serializa(t: Double): String = plazas.filter { it.fija }.joinToString("\n") { p ->
        "${p.klass} ${p.x1} ${p.y1} ${p.x2} ${p.y2} ${(t - p.visto).coerceAtLeast(0.0)}"
    }

    fun lee(s: String, t: Double) {
        for (l in s.lines()) {
            val c = l.trim().split(' ')
            if (c.size < 6) continue
            val n = c.drop(1).map { it.toDoubleOrNull() ?: return@lee }
            val edad = n[4]
            if (edad > 30 * 86400.0) continue     // retención: lo de más de 30 días no vuelve
            plazas.add(Plaza(n[0], n[1], n[2], n[3], c[0], t - edad, t - edad, 1, 1, true))
        }
    }
}

/**
 * Una cámara por red que da siempre la MISMA imagen: el servidor sigue
 * sirviendo vídeo pero la cámara se ha quedado colgada. En la muestra de 33
 * ráfagas, 4 eran así (variación cero entre 20 imágenes). El análisis no ve
 * nada moverse y parece que falla; hay que decirlo.
 */
class Congelada(val segundos: Double = 10.0) {
    private var previo: ByteArray? = null
    private var igualDesde = Double.NaN
    var congelada = false; private set

    /** `px`: el búfer pequeño (RGBA). Se compara una muestra de píxeles con la imagen anterior. */
    fun paso(px: ByteArray, t: Double): Boolean {
        val p = previo
        var igual = p != null && p.size == px.size
        if (igual) {
            var i = 0; var dif = 0L; var n = 0
            while (i < px.size) { dif += Math.abs((px[i].toInt() and 255) - (p!![i].toInt() and 255)); n++; i += 4 * 7 }
            igual = dif <= n / 50      // menos de 1 nivel cada 50 muestras: ruido de cero
        }
        if (p == null || p.size != px.size) previo = px.copyOf() else System.arraycopy(px, 0, p, 0, px.size)
        if (!igual) { igualDesde = Double.NaN; congelada = false; return false }
        if (igualDesde.isNaN()) igualDesde = t
        congelada = t - igualDesde >= segundos
        return congelada
    }
}
