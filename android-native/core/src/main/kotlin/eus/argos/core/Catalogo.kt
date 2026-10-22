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
                         val video: String, val proveedor: String, val directo: Boolean)

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
            CamaraPublica("caltrans:${loc["district"] ?: ""}:${c["index"] ?: nombre}", nombre,
                num(loc["latitude"]), num(loc["longitude"]), video, "California", true)
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

    private fun num(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: Double.NaN; else -> Double.NaN }
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
