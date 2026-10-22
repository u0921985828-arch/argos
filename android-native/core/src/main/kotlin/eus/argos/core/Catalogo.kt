package eus.argos.core

/**
 * Cámaras públicas que la app trae de serie: la lista se baja de los propios
 * organismos (Caltrans en California, Transport for London) y el usuario solo
 * elige una. Es el mismo catálogo que `cameras.js` de la versión web, con la
 * misma lectura de cada formato.
 *
 * Son cámaras de tráfico de organismos públicos, hechas para consulta
 * ciudadana. Se leen como vídeo y se analizan en el teléfono; nada se reenvía.
 */
data class CamaraPublica(val id: String, val nombre: String, val lat: Double, val lon: Double,
                         val video: String, val proveedor: String, val directo: Boolean,
                         /** Ciudad y condado, cuando el organismo los da: el nombre suele ser solo la vía. */
                         val zona: String = "")

object Catalogo {
    /** Distritos de Caltrans con vídeo en directo (HLS). */
    val DISTRITOS = listOf("3", "4", "5", "6", "7", "8", "10", "11", "12")
    fun urlCaltrans(d: String) = "https://cwwp2.dot.ca.gov/data/d$d/cctv/cctvStatusD${d.padStart(2, '0')}.json"
    const val URL_TFL = "https://api.tfl.gov.uk/Place/Type/JamCam"

    /** Caltrans: `{data: [{cctv: {location: {...}, imageData: {streamingVideoURL}}}]}`. Solo las de vídeo. */
    fun caltrans(json: String): List<CamaraPublica> {
        val raiz = Json.lee(json)
        val filas = (raiz as? Map<*, *>)?.get("data") as? List<*> ?: raiz as? List<*> ?: return emptyList()
        return filas.mapNotNull { fila ->
            val c = ((fila as? Map<*, *>)?.get("cctv") ?: fila) as? Map<*, *> ?: return@mapNotNull null
            val loc = c["location"] as? Map<*, *> ?: emptyMap<String, Any>()
            val img = c["imageData"] as? Map<*, *> ?: emptyMap<String, Any>()
            val video = (img["streamingVideoURL"] as? String)?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            if ((c["inService"] as? String)?.lowercase() == "false") return@mapNotNull null
            val nombre = (loc["locationName"] as? String)?.trim().orEmpty().ifEmpty { "sin nombre" }
            // «Not Reported» es como Caltrans dice que no lo sabe.
            val zona = listOf(loc["nearbyPlace"], loc["county"]).mapNotNull { (it as? String)?.trim() }
                .filter { it.isNotEmpty() && !it.equals("Not Reported", true) }.distinct().joinToString(" · ")
            CamaraPublica("caltrans:${loc["district"] ?: ""}:${c["index"] ?: nombre}", nombre,
                num(loc["latitude"]), num(loc["longitude"]), video, "California", true, zona)
        }
    }

    /** TfL: `[{id, commonName, lat, lon, additionalProperties: [{key, value}]}]`. Clips que se renuevan. */
    fun tfl(json: String): List<CamaraPublica> {
        val filas = Json.lee(json) as? List<*> ?: return emptyList()
        return filas.mapNotNull { f ->
            val p = f as? Map<*, *> ?: return@mapNotNull null
            val props = (p["additionalProperties"] as? List<*>).orEmpty()
                .mapNotNull { it as? Map<*, *> }.associate { (it["key"] as? String) to (it["value"] as? String) }
            if (props["available"] == "false") return@mapNotNull null
            val video = props["videoUrl"]?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            CamaraPublica("tfl:${p["id"]}", (p["commonName"] as? String).orEmpty().ifEmpty { "sin nombre" },
                num(p["lat"]), num(p["lon"]), video, "Londres", false)
        }
    }

    /** Otros nombres con los que se busca cada sitio: en español y en inglés. */
    private val ALIAS = mapOf(
        "California" to "california ee uu eeuu estados unidos usa us america",
        "Londres" to "londres london reino unido uk inglaterra england gran bretana")

    /** Lo que se busca de una cámara: nombre, ciudad, condado y sitio, sin tildes ni mayúsculas. */
    fun texto(c: CamaraPublica) = Ask.norm(c.nombre + " " + c.zona + " " + c.proveedor + " " + (ALIAS[c.proveedor] ?: ""))

    /**
     * Las cámaras que casan con lo escrito: cada palabra tiene que estar, en
     * cualquier orden («angeles 405» encuentra «I-405 : Venice · Los Angeles»).
     */
    fun busca(todas: List<CamaraPublica>, consulta: String): List<CamaraPublica> {
        val palabras = Ask.norm(consulta).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return todas
        return todas.filter { c -> val t = texto(c); palabras.all { t.contains(it) } }
    }

    private fun num(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: Double.NaN; else -> Double.NaN }
}

/**
 * Cuánto va por detrás de la realidad lo que se ve de una cámara pública, y
 * si está viva. Se mide desde el propio teléfono: la red cuenta.
 *
 *  · Directo (HLS): lo que tarda la red, más lo que el reproductor se queda
 *    detrás del borde en vivo (unos tres segmentos) y lo que tarda el servidor
 *    en publicar uno. Si la lista trae la hora de cada segmento, se usa.
 *  · Clips (TfL): la edad del clip, por su `Last-Modified`. Uno de más de una
 *    hora no es una cámara viva.
 */
object Retraso {
    /** Una lista HLS leída: o apunta a una variante, o trae segmentos. */
    data class Hls(val variante: String?, val objetivoS: Double, val segmentos: Int,
                   val finUltimoMs: Long?)

    const val CLIP_MUERTO_S = 3600.0

    fun leeHls(texto: String): Hls? {
        val l = texto.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (l.firstOrNull()?.startsWith("#EXTM3U") != true) return null
        // Lista maestra: la primera variante (Caltrans da una sola).
        val k = l.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (k >= 0) return Hls(l.drop(k + 1).firstOrNull { !it.startsWith("#") }, 0.0, 0, null)
        var objetivo = 0.0; var segs = 0; var pdt: Long? = null; var dur = 0.0; var fin: Long? = null
        for (x in l) when {
            x.startsWith("#EXT-X-TARGETDURATION:") -> objetivo = x.substringAfter(':').toDoubleOrNull() ?: 0.0
            x.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pdt = fecha(x.substringAfter(':'))
            x.startsWith("#EXTINF:") -> dur = x.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
            !x.startsWith("#") -> { segs++; fin = pdt?.let { it + (dur * 1000).toLong() }; pdt = null }
        }
        return Hls(null, objetivo, segs, fin)
    }

    private fun fecha(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(s.trim()).toEpochMilli() }.getOrNull()

    /** Segundos de retraso de un directo; null si la lista no tiene segmentos (cámara caída). */
    fun directo(h: Hls, ahoraMs: Long, redS: Double): Double? {
        if (h.segmentos == 0 || h.objetivoS <= 0) return null
        val borde = h.finUltimoMs?.let { maxOf(0.0, (ahoraMs - it) / 1000.0) } ?: h.objetivoS
        return redS + borde + 3 * h.objetivoS
    }

    /** Segundos de retraso de un clip; null si es de hace más de una hora. NaN si el servidor no dice la hora. */
    fun clip(ultimaMs: Long, ahoraMs: Long, redS: Double): Double? {
        if (ultimaMs <= 0) return Double.NaN
        val edad = maxOf(0.0, (ahoraMs - ultimaMs) / 1000.0)
        return if (edad > CLIP_MUERTO_S) null else redS + edad
    }

    fun texto(s: Double, directo: Boolean): String = when {
        s.isNaN() -> if (directo) "en directo" else "clip"
        s < 90 -> "%.0f s de retraso".format(s)
        else -> "%.0f min de retraso".format(s / 60)
    }

    /**
     * Sin búsqueda: las medidas y vivas primero, de menos a más retraso; luego
     * las que aún no se han medido. Las caídas no salen.
     */
    fun ordena(lista: List<CamaraPublica>, retraso: Map<String, Double>, caidas: Set<String>): List<CamaraPublica> {
        val vivas = lista.filter { it.id !in caidas }
        val medidas = vivas.filter { retraso[it.id]?.isNaN() == false }.sortedBy { retraso[it.id] }
        val sinDato = vivas.filter { retraso[it.id]?.isNaN() == true }
        val resto = vivas.filter { it.id !in retraso }
        return medidas + sinDato + resto
    }

    /**
     * Qué medir primero: un poco de cada sitio y de cada distrito, para que
     * lo primero que salga no sea todo de la misma carretera.
     */
    fun muestra(lista: List<CamaraPublica>, n: Int, semilla: Long): List<CamaraPublica> {
        val grupos = lista.groupBy { it.id.substringBeforeLast(':') }.values
            .map { it.shuffled(java.util.Random(semilla)) }
        val out = ArrayList<CamaraPublica>(n)
        var r = 0
        while (out.size < n && grupos.any { r < it.size }) {
            for (g in grupos) if (r < g.size && out.size < n) out += g[r]
            r++
        }
        return out
    }
}

/**
 * Lector de JSON mínimo (objetos, listas, cadenas, números, true/false/null),
 * para no arrastrar una biblioteca a un núcleo que se prueba en la JVM.
 */
object Json {
    fun lee(s: String): Any? { val p = Lector(s); val v = p.valor(); return v }

    private class Lector(val s: String) {
        var i = 0
        fun blanco() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valor(): Any? {
            blanco()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> objeto(); '[' -> lista(); '"' -> cadena()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> numero()
            }
        }
        fun objeto(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++
            while (true) {
                blanco(); if (i >= s.length) return m
                if (s[i] == '}') { i++; return m }
                if (s[i] == ',') { i++; continue }
                val k = cadena(); blanco(); if (i < s.length && s[i] == ':') i++
                m[k] = valor()
            }
        }
        fun lista(): List<Any?> {
            val l = ArrayList<Any?>(); i++
            while (true) {
                blanco(); if (i >= s.length) return l
                if (s[i] == ']') { i++; return l }
                if (s[i] == ',') { i++; continue }
                l.add(valor())
            }
        }
        fun cadena(): String {
            val sb = StringBuilder(); i++
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}; 'b' -> {}; 'f' -> {}
                        'u' -> { if (i + 4 < s.length) sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun numero(): Any? {
            val a = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (i == a) { i++; return null }      // basura: se salta
            return s.substring(a, i).toDoubleOrNull()
        }
    }
}
