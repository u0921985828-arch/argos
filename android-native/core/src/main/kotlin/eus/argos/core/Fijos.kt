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
 * las pasadas durante 2 minutos, y QUIETA (el centro no baila más de un 5 % del
 * tamaño de la caja), queda FIJO: no entra en el seguimiento, ni en los
 * recuentos, ni en el zoom. Tampoco a un vehículo grande (≥ 12 % del alto),
 * que no es una letra.
 *
 * En el resto del cuadro lo mismo, durante 3 minutos, pero solo para lo que
 * no puede ser un coche aparcado: una «persona» o una «bici» que no se mueve
 * ni un píxel (en la muestra: semáforos, farolas y señales tomados por
 * personas) y un «vehículo» que es sobre todo luz (≥ 45 % de píxeles casi
 * blancos: farolas, carteles iluminados, destellos). Un coche aparcado de
 * verdad no es casi todo luz, y una persona quieta se balancea.
 *
 * Se guarda por cámara: la vez siguiente se descarta desde el primer momento.
 */
class Fijos(val minS: Double = 120.0, val ratio: Double = 0.6, val iouMismo: Double = 0.6,
            val franja: Double = 0.15) {
    class Plaza(var x1: Double, var y1: Double, var x2: Double, var y2: Double, val klass: String,
                var desde: Double, var visto: Double, var veces: Int, var pasadas: Int, var fija: Boolean,
                val franja: Boolean = true) {
        // Cuánto baila el centro, en fracción del tamaño de la caja (Welford).
        var n = 0; var mx = 0.0; var my = 0.0; var sx = 0.0; var sy = 0.0
        fun baile(): Double = if (n < 2) 0.0 else Math.sqrt(maxOf(sx, sy) / (n - 1))
    }

    val plazas = ArrayList<Plaza>()
    val fijas get() = plazas.count { it.fija }

    private fun iou(p: Plaza, d: DetBox): Double {
        val ix = maxOf(0.0, minOf(p.x2, d.x2) - maxOf(p.x1, d.x1)); val iy = maxOf(0.0, minOf(p.y2, d.y2) - maxOf(p.y1, d.y1))
        val i = ix * iy; val u = (p.x2 - p.x1) * (p.y2 - p.y1) + (d.x2 - d.x1) * (d.y2 - d.y1) - i
        return if (u <= 0) 0.0 else i / u
    }

    fun enFranja(d: DetBox, h: Int) = d.y2 <= franja * h || d.y1 >= (1 - franja) * h

    /**
     * ¿Puede ser algo fijo que no es un objeto? En las franjas, cualquier cosa
     * menos un vehículo grande; en el centro, una persona o bici, o un
     * vehículo que es sobre todo luz. `luz`: fracción de píxeles casi blancos.
     */
    fun candidata(d: DetBox, h: Int, luz: Double = 0.0): Boolean {
        val alto = d.y2 - d.y1
        if (d.klass == "vehicle" && alto >= 0.12 * h) return false
        if (enFranja(d, h)) return true
        return d.klass == "person" || d.klass == "bike" || (d.klass == "vehicle" && luz >= 0.45)
    }

    /**
     * Una pasada del detector, en coordenadas de la imagen que vio. Devuelve lo
     * que sigue siendo objeto; lo fijo se queda fuera.
     */
    fun filtra(dets: List<DetBox>, h: Int, t: Double, luz: (DetBox) -> Double = { 0.0 }): List<DetBox> {
        val tocadas = HashSet<Plaza>()
        val fuera = HashSet<DetBox>()
        for (d in dets) {
            val franjaD = enFranja(d, h)
            // La luz se mira solo cuando decide algo: un vehículo en el centro.
            if (!candidata(d, h, if (!franjaD && d.klass == "vehicle") luz(d) else 0.0)) continue
            val p = plazas.filter { it.klass == d.klass && it !in tocadas }.maxByOrNull { iou(it, d) }
                ?.takeIf { iou(it, d) >= iouMismo }
            if (p == null) {
                plazas.add(Plaza(d.x1, d.y1, d.x2, d.y2, d.klass, t, t, 1, 0, false, franjaD).also { tocadas.add(it); it.anota(d) })
                continue
            }
            tocadas.add(p); p.anota(d)
            p.x1 += (d.x1 - p.x1) * 0.1; p.y1 += (d.y1 - p.y1) * 0.1; p.x2 += (d.x2 - p.x2) * 0.1; p.y2 += (d.y2 - p.y2) * 0.1
            p.visto = t; p.veces++
            if (p.fija) fuera.add(d)
        }
        val it = plazas.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.pasadas++
            val espera = if (p.franja) minS else minS * 1.5
            if (!p.fija && t - p.desde >= espera && p.veces >= ratio * p.pasadas && p.baile() <= 0.05) p.fija = true
            // Lo que no se repite se olvida pronto (un coche que pasa por la
            // franja); lo fijo, si deja de verse un día (cambian el rótulo).
            val olvido = if (p.fija) 86400.0 else 15.0
            if (t - p.visto > olvido || (!p.fija && t - p.desde > minS * 3 && p.veces < ratio * p.pasadas)) it.remove()
        }
        if (plazas.size > 300) plazas.sortByDescending { it.veces }.also { while (plazas.size > 300) plazas.removeAt(plazas.size - 1) }
        return dets.filter { it !in fuera }
    }

    private fun Plaza.anota(d: DetBox) {
        val w = maxOf(1.0, d.x2 - d.x1); val hh = maxOf(1.0, d.y2 - d.y1)
        val cx = (d.x1 + d.x2) / 2 / w; val cy = (d.y1 + d.y2) / 2 / hh
        n++
        val dx = cx - mx; mx += dx / n; sx += dx * (cx - mx)
        val dy = cy - my; my += dy / n; sy += dy * (cy - my)
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

/**
 * Luces y reflejos, que no son objetos.
 *
 * En la muestra nocturna, el detector puso cajas de «vehículo» sobre destellos
 * de faros y farolas, y de «persona» sobre semáforos; y la resta de fondo ve
 * «movimiento» donde un faro barre el asfalto o se refleja en el suelo mojado.
 */
object Luz {
    private fun luma(px: ByteArray, i: Int) =
        0.299 * (px[i].toInt() and 255) + 0.587 * (px[i + 1].toInt() and 255) + 0.114 * (px[i + 2].toInt() and 255)

    /** Fracción de píxeles casi blancos (algún canal ≥ 230) dentro de la caja; RGBA, uno de cada dos. */
    fun fraccion(rgba: ByteArray, rowStride: Int, w: Int, h: Int, d: DetBox): Double {
        val x0 = d.x1.toInt().coerceIn(0, w - 1); val x1 = d.x2.toInt().coerceIn(x0 + 1, w)
        val y0 = d.y1.toInt().coerceIn(0, h - 1); val y1 = d.y2.toInt().coerceIn(y0 + 1, h)
        var n = 0; var luz = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val i = y * rowStride + x * 4
                if (i + 2 < rgba.size) {
                    n++
                    if ((rgba[i].toInt() and 255) >= 230 || (rgba[i + 1].toInt() and 255) >= 230 || (rgba[i + 2].toInt() and 255) >= 230) luz++
                }
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0.0 else luz.toDouble() / n
    }

    /**
     * Una caja de MOVIMIENTO (de la resta de fondo) que es solo luz: lo que ha
     * cambiado se ha ACLARADO y casi nada se ha oscurecido. Un faro que barre
     * el asfalto o su reflejo solo suman luz; un objeto que pasa tapa el fondo
     * y deja partes más oscuras y más claras a la vez (ruedas, lunas, sombra).
     * `cur` es el RGBA del búfer de análisis y `fondo` la luma del modelo de
     * fondo, del mismo tamaño; la caja va en píxeles de ese búfer.
     */
    fun esLuz(cur: ByteArray, fondo: FloatArray, aw: Int, ah: Int,
              x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val a = x1.coerceIn(0, aw - 1); val b = x2.coerceIn(a + 1, aw)
        val c = y1.coerceIn(0, ah - 1); val e = y2.coerceIn(c + 1, ah)
        if (fondo.size < aw * ah || cur.size < aw * ah * 4) return false
        var n = 0; var claro = 0; var oscuro = 0
        for (y in c until e) for (x in a until b) {
            val k = y * aw + x
            val dif = luma(cur, k * 4) - fondo[k]
            n++
            if (dif > 25) claro++ else if (dif < -25) oscuro++
        }
        val cambio = claro + oscuro
        if (n == 0 || cambio < n * 0.1) return false
        return claro >= 0.85 * cambio
    }
}
