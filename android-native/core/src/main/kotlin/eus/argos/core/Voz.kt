package eus.argos.core

/**
 * La voz de ARGOS: de texto en español a la secuencia de fonemas que espera
 * una voz Piper (VITS entrenada sobre la salida IPA de espeak-ng «es»).
 *
 * Piper necesita espeak-ng para pasar de letras a fonemas, y espeak-ng es una
 * biblioteca nativa que habría que compilar para Android. El español se
 * escribe casi como se pronuncia, así que aquí van sus reglas en Kotlin puro,
 * contrastadas palabra a palabra con espeak-ng (`:core:voz`): letras a
 * fonemas, sílabas, acento, diptongos, alófonos (β ð ɣ, ŋ, ɾ/r) y la «e»
 * abierta de espeak. Lo que no se dice como se escribe —cifras, horas,
 * unidades— se pasa antes a palabras.
 */
object Fonetica {
    private const val VOCALES = "aeiouáéíóúü"
    private val ATONAS = setOf("el", "la", "los", "las", "lo", "le", "les", "de", "del", "a", "al", "y", "e", "o", "u",
        "en", "con", "por", "que", "se", "me", "te", "nos", "os", "mi", "mis", "tu", "tus", "su", "sus", "sin", "si", "ni",
        "mas", "tan", "hacia", "según")
    /** Palabras de enlace que espeak dice con acento secundario, no primario. */
    private val SECUNDARIAS = setOf("como", "para", "pero", "cuando", "donde", "porque", "sobre", "entre", "hasta",
        "desde", "aunque", "mientras")

    /** Un segmento: fonema y si es núcleo de sílaba. */
    private class Seg(var f: String, val vocal: Boolean, var acento: Boolean = false, var glide: Boolean = false,
                      var plena: Boolean = false, var hiato: Boolean = false)

    /** Letras de una palabra a segmentos, sin alófonos de contexto todavía. */
    private fun segmentos(w: String): ArrayList<Seg> {
        val s = ArrayList<Seg>()
        var i = 0
        fun c(k: Int) = if (k in w.indices) w[k] else ' '
        fun anteEI(k: Int) = c(k) in "eiéí"
        while (i < w.length) {
            val ch = w[i]
            // Consonante doble que no es dígrafo (ck, tt, ss): una sola.
            if (i > 0 && ch == w[i - 1] && ch in "bdfgkmpstvz") { i++; continue }
            if (ch == 'c' && c(i + 1) == 'k') { i++; continue }
            when (ch) {
                // Siempre núcleo: la «ü» y la vocal tras una «h» entre vocales (ahí).
                // La «i» o «u» tras «l» o «r» de principio de palabra o de grupo,
                // ante vocal (cliente, amplia, riesgo, grueso, corrió), se dice
                // entera, pero para el acento cuenta como diptongo: «amplia» es
                // llana sobre «am». Tras «r» o «l» entre vocales, no (contrario).
                'a', 'e', 'i', 'o', 'u', 'ü' -> s.add(Seg(if (ch == 'ü') "u" else ch.toString(), true,
                    plena = ch == 'ü' || (c(i - 1) == 'h' && c(i - 2) in VOCALES),
                    hiato = (ch == 'i' || ch == 'u') && c(i - 1) in "lr" && c(i + 1) in VOCALES &&
                        (i == 1 || c(i - 2) !in VOCALES || (ch == 'u' && c(i - 1) == 'l'))))
                'á', 'é', 'í', 'ó', 'ú' -> s.add(Seg(when (ch) { 'á' -> "a"; 'é' -> "e"; 'í' -> "i"; 'ó' -> "o"; else -> "u" }, true, acento = true))
                'b', 'v' -> s.add(Seg("b", false))
                'c' -> when {
                    c(i + 1) == 'h' -> { s.add(Seg("tʃ", false)); i++ }
                    anteEI(i + 1) -> s.add(Seg("θ", false))
                    else -> s.add(Seg("k", false))
                }
                'd' -> s.add(Seg("d", false))
                'f' -> s.add(Seg("f", false))
                'g' -> when {
                    c(i + 1) == 'u' && anteEI(i + 2) -> { s.add(Seg("g", false)); i++ }
                    anteEI(i + 1) -> s.add(Seg("x", false))
                    else -> s.add(Seg("g", false))
                }
                'h' -> {}
                'j' -> s.add(Seg("x", false))
                'k' -> s.add(Seg("k", false))
                'l' -> if (c(i + 1) == 'l') { s.add(Seg("ʎ", false)); i++ } else s.add(Seg("l", false))
                'm' -> s.add(Seg("m", false))
                'n' -> s.add(Seg("n", false))
                'ñ' -> s.add(Seg("ɲ", false))
                'p' -> if (c(i + 1) == 'h') { s.add(Seg("f", false)); i++ } else s.add(Seg("p", false))
                'q' -> { s.add(Seg("k", false)); if (c(i + 1) == 'u') i++ }
                'r' -> if (c(i + 1) == 'r') { s.add(Seg("r", false)); i++ }
                       else s.add(Seg(if (i == 0 || c(i - 1) in "nls") "r" else "ɾ", false))
                's' -> s.add(Seg("s", false))
                't' -> s.add(Seg("t", false))
                'w' -> s.add(Seg("w", false))
                'x' -> { s.add(Seg("k", false)); s.add(Seg("s", false)) }
                // «y» ante vocal es consonante; al final o ante consonante, la
                // cola de un diptongo (hay, muy); sola, la conjunción «i».
                'y' -> when {
                    c(i + 1) in VOCALES -> s.add(Seg("ʝ", false))
                    w.length == 1 -> s.add(Seg("i", true))
                    else -> s.add(Seg("i", true, glide = true))
                }
                'z' -> s.add(Seg("θ", false))
                else -> {}
            }
            i++
        }
        return s
    }

    private fun fuerte(f: String) = f == "a" || f == "e" || f == "o"

    /**
     * Fonemas de una palabra. `previo` es el último fonema dicho antes (null =
     * principio de frase), que decide los alófonos del principio.
     */
    fun palabra(w0: String, previo: String? = null, enFrase: Boolean = false): String {
        val w = w0.lowercase()
        // Los adverbios en -mente llevan dos acentos: el del adjetivo y «mente».
        if (w.length > 7 && w.endsWith("mente")) {
            val base = palabra(w.dropLast(5), previo, enFrase)
            return base + "mˈente"
        }
        val s = segmentos(w)
        if (s.none { it.vocal }) return s.joinToString("") { it.f }

        // Diptongos: i/u sin tilde junto a otra vocal pierden el núcleo.
        for (k in s.indices) {
            val a = s[k]; if (!a.vocal || a.acento || a.glide || a.plena || a.hiato || (a.f != "i" && a.f != "u")) continue
            val sig = s.getOrNull(k + 1); val ant = s.getOrNull(k - 1)
            if (sig != null && sig.vocal && !sig.glide && !sig.plena) {
                // «iu» y «ui»: la segunda es el núcleo.
                a.glide = true
            } else if (ant != null && ant.vocal && !ant.glide && !ant.hiato && !a.plena) {
                a.glide = true
            }
        }
        // Sin ningún núcleo («dy»): la primera vocal lo es.
        if (s.none { it.vocal && !it.glide }) s.first { it.vocal }.glide = false
        val nucleos = s.indices.filter { s[it].vocal && !s[it].glide }
        val paraAcento = nucleos.filter { !s[it].hiato }.ifEmpty { nucleos }

        // Acento: tilde; si no, llana si acaba en vocal, n o s; si no, aguda.
        val n = paraAcento.size
        var tonicaA = paraAcento.indexOfFirst { s[it].acento }
        if (tonicaA < 0) {
            val ult = w.last()
            tonicaA = if (n == 1) 0 else if (ult in "aeiouns") n - 2 else n - 1
        }
        // Índice de la tónica entre los núcleos que se dicen.
        val tonica = nucleos.indexOf(paraAcento[tonicaA])
        // Las átonas solo lo son dentro de una frase: dichas solas, se acentúan.
        val atona = enFrase && w in ATONAS
        val secundaria = enFrase && w in SECUNDARIAS

        // Alófonos.
        val out = ArrayList<String>()
        for (k in s.indices) {
            val g = s[k]
            val ant: String? = if (k == 0) previo else out.lastOrNull()
            val sig = s.getOrNull(k + 1)
            var f = g.f
            if (g.vocal && g.glide) {
                val tras = k > 0 && s[k - 1].vocal
                f = if (g.f == "i") (if (tras && fuerte(s[k - 1].f)) "ɪ" else "j") else (if (tras && fuerte(s[k - 1].f)) "ʊ" else "w")
            }
            if (!g.vocal) {
                val tras = ant != null && ant != "m" && ant != "n" && ant != "ŋ" && ant != " "
                val coda = sig != null && !sig.vocal && sig.f != "ɾ" && sig.f != "l" && sig.f != "ʝ"
                f = when (g.f) {
                    // Tras vocal o consonante (no nasal), b/d/g se relajan. La «d» y
                    // la «g» de final de sílaba cerrada no; la «b» sí.
                    "b" -> if (tras && k != s.lastIndex && sig?.f !in setOf("t", "b", "d", "k", "p", "g")) "β" else "b"
                    "d" -> if (tras && !coda && k != s.lastIndex) "ð" else "d"
                    "g" -> if (tras && !coda && k != s.lastIndex) "ɣ" else "ɡ"
                    "n" -> when (sig?.f) { "b", "p", "f" -> "m"; "g", "x" -> "ŋ"; else -> "n" }
                    // espeak alarga la «p» ante «t» (apto, adaptar).
                    "p" -> if (sig?.f == "t") "pː" else "p"
                    else -> f
                }
            }
            if (g.vocal && !g.glide && !g.acento && f == "e" && nucleos.indexOf(k) == tonica && !atona && !secundaria) {
                val a = s.getOrNull(k + 1); val b = s.getOrNull(k + 2)
                if (a != null && a.f == "n" && b != null && !b.vocal) f = "ɛ"
            }
            out.add(f)
        }

        // Marcas de acento: justo antes de la vocal núcleo.
        val sb = StringBuilder()
        for (k in s.indices) {
            val ni = nucleos.indexOf(k)
            if (ni >= 0 && !atona) {
                if (ni == tonica) sb.append(if (secundaria) 'ˌ' else 'ˈ')
                else if (ni % 2 == 0 && ni <= tonica - 2) sb.append('ˌ')
            }
            sb.append(out[k])
        }
        return sb.toString()
    }
}

/**
 * Lo que no se lee como se escribe, pasado a palabras: cifras, horas,
 * decimales, unidades, siglas y las marcas en inglés que salen en las
 * respuestas. Es lo que espeak haría por su cuenta, y mejor dicho: «km/h» es
 * «kilómetros por hora», no «ka eme barra hache».
 */
object Normaliza {
    private val UNIDADES = arrayOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
        "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve")
    private val DECENAS = arrayOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val CENTENAS = arrayOf("", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos",
        "setecientos", "ochocientos", "novecientos")
    private val FEMENINOS = setOf("persona", "personas", "cosa", "cosas", "hora", "horas", "bici", "bicis", "moto", "motos",
        "pista", "pistas", "zona", "zonas", "tesela", "teselas", "vez", "veces", "inferencia", "inferencias", "trayectoria",
        "trayectorias", "semana", "semanas", "alerta", "alertas")
    private val LETRAS = mapOf('a' to "a", 'b' to "be", 'c' to "ce", 'd' to "de", 'e' to "e", 'f' to "efe", 'g' to "ge",
        'h' to "hache", 'i' to "i", 'j' to "jota", 'k' to "ka", 'l' to "ele", 'm' to "eme", 'n' to "ene", 'ñ' to "eñe",
        'o' to "o", 'p' to "pe", 'q' to "cu", 'r' to "erre", 's' to "ese", 't' to "te", 'u' to "u", 'v' to "uve",
        'w' to "uve doble", 'x' to "equis", 'y' to "i griega", 'z' to "zeta")
    /** Marcas y palabras inglesas que salen en las respuestas, escritas como suenan. */
    private val LEXICO = linkedMapOf(
        "bluetooth" to "blutuz", "find my" to "faind mai", "smarttag" to "esmart tag", "airtag" to "er tag",
        "airtags" to "er tags", "tile" to "tail", "remote id" to "rimout ai di", "google" to "gugel", "argos" to "argos",
        "wifi" to "uaifai", "wi-fi" to "uaifai")

    /** Entero a palabras. `fem` = concuerda con un nombre femenino (una, veintiuna, doscientas). */
    fun entero(n0: Long, fem: Boolean = false, apocope: Boolean = false): String {
        if (n0 < 0) return "menos " + entero(-n0, fem, apocope)
        val n = n0
        fun uno(s: String) = when {
            !s.endsWith("uno") -> s
            fem -> s.dropLast(3) + if (s == "veintiuno") "una" else "una"
            apocope -> s.dropLast(1).let { if (it == "veintiun") "veintiún" else it }
            else -> s
        }
        return when {
            n < 30 -> uno(UNIDADES[n.toInt()])
            n < 100 -> DECENAS[(n / 10).toInt()] + (if (n % 10 != 0L) " y " + uno(UNIDADES[(n % 10).toInt()]) else "")
            n == 100L -> "cien"
            n < 1000 -> CENTENAS[(n / 100).toInt()].let { if (fem && n >= 200) it.replace("ientos", "ientas") else it } +
                (if (n % 100 != 0L) " " + entero(n % 100, fem, apocope) else "")
            n < 1_000_000 -> (if (n / 1000 == 1L) "mil" else entero(n / 1000, fem, true) + " mil") +
                (if (n % 1000 != 0L) " " + entero(n % 1000, fem, apocope) else "")
            else -> (if (n / 1_000_000 == 1L) "un millón" else entero(n / 1_000_000, false, true) + " millones") +
                (if (n % 1_000_000 != 0L) " " + entero(n % 1_000_000, fem, apocope) else "")
        }
    }

    fun texto(t0: String): String {
        var t = " " + t0 + " "
        t = t.replace('«', ' ').replace('»', ' ').replace('"', ' ').replace('“', ' ').replace('”', ' ')
            .replace("·", ",").replace(";", ",").replace("—", ",").replace("–", ",").replace("(", ",").replace(")", ",")
            .replace("…", ".").replace("⚠", " ").replace("#", " número ").replace("×", " por ")
        // Horas: 18:07 → dieciocho y siete; 9:00 → nueve en punto.
        t = Regex("(\\d{1,2}):(\\d{2})").replace(t) { m ->
            val h = m.groupValues[1].toLong(); val mi = m.groupValues[2].toLong()
            entero(h, fem = true) + if (mi == 0L) " en punto" else " y " + entero(mi)
        }
        // Unidades tras un número.
        t = Regex("(\\d)\\s*km/h").replace(t) { "${it.groupValues[1]} kilómetros por hora" }
        t = Regex("(\\d)\\s*m/s").replace(t) { "${it.groupValues[1]} metros por segundo" }
        t = Regex("(\\d)\\s*ms\\b").replace(t) { "${it.groupValues[1]} milisegundos" }
        t = Regex("(\\d)\\s*min\\b").replace(t) { "${it.groupValues[1]} minutos" }
        t = Regex("(\\d)\\s*dB(-Hz)?\\b").replace(t) { "${it.groupValues[1]} decibelios" }
        t = Regex("(\\d)\\s*m\\b").replace(t) { "${it.groupValues[1]} metros" }
        t = Regex("(\\d)\\s*s\\b").replace(t) { "${it.groupValues[1]} segundos" }
        t = Regex("(\\d)\\s*%").replace(t) { "${it.groupValues[1]} por ciento" }
        t = Regex("(\\d)\\s*°").replace(t) { "${it.groupValues[1]} grados" }
        // Marcas en inglés.
        for ((k, v) in LEXICO) t = Regex("(?i)\\b" + Regex.escape(k) + "\\b").replace(t, v)
        // Siglas: mayúsculas de 2 a 5 letras se deletrean (GPS, GNSS, ID).
        t = Regex("\\b([A-ZÑ]{2,5})\\b").replace(t) { m -> m.value.lowercase().map { LETRAS[it] ?: "" }.joinToString(" ") }
        // Códigos con letras y cifras (números de serie): letra a letra.
        t = Regex("\\b(?=[A-Za-z]*\\d)(?=\\d*[A-Za-z])[A-Za-z0-9]{4,}\\b").replace(t) { m ->
            m.value.lowercase().map { if (it.isDigit()) entero((it - '0').toLong()) else LETRAS[it] ?: "" }.joinToString(" ")
        }
        // Miles con punto (1.250) antes que decimales.
        t = Regex("\\b\\d{1,3}(?:\\.\\d{3})+\\b").replace(t) { it.value.replace(".", "") }
        // Decimales: 28,8 → veintiocho coma ocho.
        t = Regex("(\\d+)[,.](\\d+)").replace(t) { m ->
            entero(m.groupValues[1].toLong()) + " coma " +
                m.groupValues[2].let { d -> if (d.startsWith("0")) d.map { entero((it - '0').toLong()) }.joinToString(" ") else entero(d.toLong()) }
        }
        // Enteros, concordando con el nombre que sigue (una persona, un vehículo).
        t = Regex("(\\d+)(\\s+)([\\p{L}]+)?").replace(t) { m ->
            val sig = m.groupValues[3].lowercase()
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val fem = sig in FEMENINOS
            entero(n, fem, apocope = sig.isNotEmpty() && sig.first().isLetter()) + m.groupValues[2] + m.groupValues[3]
        }
        t = Regex("\\d+").replace(t) { entero(it.value.toLong()) }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}

/** Texto a fonemas de frase, con pausas y enlaces entre palabras como espeak. */
fun Fonetica.frase(texto: String): String {
    val t = Normaliza.texto(texto).lowercase()
    val sb = StringBuilder()
    var previo: String? = null
    val partes = Regex("[\\p{L}]+|[,.:!?]").findAll(t).map { it.value }.toList()
    for ((i, p) in partes.withIndex()) {
        if (p.length == 1 && p in ",.:!?") {
            // Pausa: la siguiente palabra empieza como a principio de frase.
            if (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
            sb.append(if (p == ":") "," else p).append(' ')
            previo = null
            continue
        }
        // Una átona al final de un tramo («dame la», «es de») se acentúa.
        val finTramo = partes.getOrNull(i + 1).let { it == null || (it.length == 1 && it in ",.:!?") }
        var f = palabra(p, previo, enFrase = !finTramo)
        // «un vehículo» → «um»; «con gente» → «coŋ» (solo si es átona).
        val sig = partes.getOrNull(i + 1)?.takeIf { it.length > 1 || it !in ",.:!?" }
        if (f.endsWith("n") && sig != null) {
            val s0 = palabra(sig).replace("ˈ", "").replace("ˌ", "").firstOrNull()
            if (s0 == 'b' || s0 == 'p') f = f.dropLast(1) + "m"
            else if ((s0 == 'x' || s0 == 'ɡ') && f.none { it == 'ˈ' }) f = f.dropLast(1) + "ŋ"
        }
        sb.append(f).append(' ')
        previo = f.lastOrNull { it != 'ˈ' && it != 'ˌ' }?.toString()
    }
    return sb.toString().trim()
}

/**
 * Una voz Piper: el `.onnx.json` de la voz y una función que ejecuta el
 * modelo. La inferencia la pone quien llama (ONNX Runtime de Android en la
 * app, el de la JVM en las pruebas), como con el detector.
 *
 * Piper espera: «^», y luego cada fonema seguido del relleno «_», y «$» al
 * final; entradas `input` (ids), `input_lengths` y `scales` (ruido, duración,
 * ruido de duración). Se sintetiza frase a frase con una pausa corta entre
 * medias: una frase larga de una vez sale atropellada.
 */
class VozPiper(config: String, private val infer: (LongArray, FloatArray) -> FloatArray) {
    val muestreo: Int = Regex("\"sample_rate\"\\s*:\\s*(\\d+)").find(config)?.groupValues?.get(1)?.toInt() ?: 22050
    private val mapa: Map<String, LongArray>
    private val escalas: FloatArray
    /** Velocidad: >1 más despacio. */
    var duracion = 1.0f
    /** El acabado que se le da a la salida. */
    var efecto = EfectoVoz.Tipo.ESTUDIO

    init {
        val i = config.indexOf("\"phoneme_id_map\"")
        require(i >= 0) { "el .onnx.json no tiene phoneme_id_map" }
        val a = config.indexOf('{', i); var d = 0; var j = a
        while (true) { if (config[j] == '{') d++; if (config[j] == '}') { d--; if (d == 0) break }; j++ }
        val m = HashMap<String, LongArray>()
        Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\\[([^\\]]*)\\]").findAll(config.substring(a + 1, j)).forEach { r ->
            var k = r.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            k = Regex("\\\\u([0-9a-fA-F]{4})").replace(k) { it.groupValues[1].toInt(16).toChar().toString() }
            m[k] = r.groupValues[2].split(",").map { it.trim().toLong() }.toLongArray()
        }
        mapa = m
        fun num(n: String, def: Float) = Regex("\"$n\"\\s*:\\s*([0-9.]+)").find(config)?.groupValues?.get(1)?.toFloat() ?: def
        // El ruido de duración se baja: con el de fábrica (0,8) la MISMA frase
        // duraba de 2,2 a 10,9 s según la tirada (medido); con 0,3, de 2,0 a 2,4.
        escalas = floatArrayOf(num("noise_scale", 0.667f), num("length_scale", 1f), minOf(0.3f, num("noise_w", 0.8f)))
    }

    /** Fonemas → ids de Piper. Lo que el mapa no conoce se salta. */
    fun ids(fonemas: String): LongArray {
        val out = ArrayList<Long>()
        val pad = mapa["_"]!!
        mapa["^"]!!.forEach { out.add(it) }; pad.forEach { out.add(it) }
        var k = 0
        while (k < fonemas.length) {
            val cp = fonemas.codePointAt(k); val s = String(Character.toChars(cp)); k += Character.charCount(cp)
            val v = mapa[s] ?: continue
            v.forEach { out.add(it) }; pad.forEach { out.add(it) }
        }
        mapa["$"]!!.forEach { out.add(it) }
        return out.toLongArray()
    }

    /** Texto a audio en coma flotante, frase a frase. */
    fun habla(texto: String): FloatArray {
        val frases = texto.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val pausa = FloatArray((muestreo * 0.18).toInt())
        val trozos = ArrayList<FloatArray>()
        for (f in frases) {
            val fon = Fonetica.frase(f)
            if (fon.isBlank()) continue
            trozos.add(recorta(infer(ids(fon), floatArrayOf(escalas[0], escalas[1] * duracion, escalas[2]))))
            trozos.add(pausa)
        }
        val out = FloatArray(trozos.sumOf { it.size })
        var p = 0
        for (t in trozos) { System.arraycopy(t, 0, out, p, t.size); p += t.size }
        return EfectoVoz.aplica(out, muestreo, efecto)
    }

    /** Fuera el silencio de los bordes y los huecos de más de 0,3 s dentro. */
    private fun recorta(a: FloatArray): FloatArray {
        if (a.isEmpty()) return a
        val umbral = 0.02f * (a.maxOfOrNull { Math.abs(it) } ?: 0f)
        var i0 = 0; while (i0 < a.size && Math.abs(a[i0]) < umbral) i0++
        var i1 = a.size - 1; while (i1 > i0 && Math.abs(a[i1]) < umbral) i1--
        val maxHueco = (muestreo * 0.3).toInt()
        val out = ArrayList<Float>(i1 - i0 + 1)
        var hueco = 0
        for (k in i0..i1) {
            hueco = if (Math.abs(a[k]) < umbral) hueco + 1 else 0
            if (hueco <= maxHueco) out.add(a[k])
        }
        return out.toFloatArray()
    }

    companion object {
        /** A PCM de 16 bits, normalizado a un pico de 0,9. */
        fun pcm16(a: FloatArray): ShortArray {
            val pico = maxOf(0.01f, a.maxOfOrNull { Math.abs(it) } ?: 0f)
            return ShortArray(a.size) { (a[it] / pico * 0.9f * 32767).toInt().toShort() }
        }
    }
}

/**
 * El acabado de la voz: limpiarla y pulirla, como se trata una voz grabada
 * para un anuncio o un pódcast, sin que suene a radio ni a robot.
 *  1. Limpieza: un expansor hacia abajo baja suavemente lo que queda entre
 *     palabras por debajo del umbral (el siseo y el soplido de la síntesis),
 *     sin cortes bruscos. Un paso alto a 80 Hz quita el retumbe.
 *  2. Ecualización:
 *     - −3 dB en 280 Hz, la «caja» que embota la voz;
 *     - +2,5 dB en 3,2 kHz, la presencia que la pone delante;
 *     - −2 dB en 6,5 kHz, el brillo metálico típico de la síntesis;
 *     - +1,5 dB de estante por encima de 9 kHz, aire si la voz lo tiene.
 *  3. Compresión suave 3:1 a −20 dB, con ataque de 10 ms y relajación de
 *     60 ms: igual de cerca en cada sílaba, sin bombeo.
 *  4. Limitador final: nada pasa de −1 dB, sin saturar.
 * `NATURAL` no toca nada.
 */
object EfectoVoz {
    enum class Tipo(val texto: String) { NATURAL("natural"), ESTUDIO("estudio") }

    private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val c0 = b0 / a0; private val c1 = b1 / a0; private val c2 = b2 / a0
        private val d1 = a1 / a0; private val d2 = a2 / a0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun paso(x: Double): Double {
            val y = c0 * x + c1 * x1 + c2 * x2 - d1 * y1 - d2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        companion object {
            // Fórmulas de R. Bristow-Johnson (Audio EQ Cookbook).
            fun pasoAlto(f: Double, sr: Int): Biquad {
                val w = 2 * Math.PI * f / sr; val al = Math.sin(w) / (2 * 0.707); val cw = Math.cos(w)
                return Biquad((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
            }
            fun campana(f: Double, q: Double, db: Double, sr: Int): Biquad {
                val a = Math.pow(10.0, db / 40); val w = 2 * Math.PI * f / sr; val al = Math.sin(w) / (2 * q); val cw = Math.cos(w)
                return Biquad(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
            }
            fun estanteAlto(f: Double, db: Double, sr: Int): Biquad {
                val a = Math.pow(10.0, db / 40); val w = 2 * Math.PI * f / sr; val cw = Math.cos(w)
                val al = Math.sin(w) / 2 * Math.sqrt(2.0); val r = 2 * Math.sqrt(a) * al
                return Biquad(a * ((a + 1) + (a - 1) * cw + r), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - r),
                              (a + 1) - (a - 1) * cw + r, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - r)
            }
        }
    }

    fun aplica(a: FloatArray, sr: Int, tipo: Tipo): FloatArray {
        if (tipo == Tipo.NATURAL || a.isEmpty()) return a
        val pico = (a.maxOfOrNull { Math.abs(it) } ?: 0f).toDouble().coerceAtLeast(1e-4)
        val nyq = sr / 2.0
        val filtros = listOfNotNull(
            Biquad.pasoAlto(80.0, sr),
            Biquad.campana(280.0, 1.0, -3.0, sr),
            Biquad.campana(3200.0, 0.9, 2.5, sr),
            if (6500.0 < nyq * 0.9) Biquad.campana(6500.0, 2.0, -2.0, sr) else null,
            if (9000.0 < nyq * 0.9) Biquad.estanteAlto(9000.0, 1.5, sr) else null)
        val out = FloatArray(a.size)
        // Expansor: envolvente de 1 ms / 30 ms; por debajo del 6 % del pico la
        // ganancia baja con pendiente 1:4 (lo de −40 dB acaba a −76): el siseo
        // desaparece y una sílaba floja, muy por encima, ni se entera.
        val eAt = Math.exp(-1.0 / (0.001 * sr)); val eRe = Math.exp(-1.0 / (0.030 * sr))
        var env = 0.0
        val umbralE = 0.06
        // Compresor 3:1 a −20 dB, sobre la señal ya ecualizada.
        val cAt = Math.exp(-1.0 / (0.010 * sr)); val cRe = Math.exp(-1.0 / (0.060 * sr))
        var cEnv = 0.0; val umbralC = 0.1; val razon = 3.0
        val recupera = Math.pow(1 / umbralC, 1 - 1 / razon) * 0.5   // nivel de vuelta a ~−6 dB
        for (i in a.indices) {
            var x = a[i] / pico
            val ax = Math.abs(x)
            env = if (ax > env) eAt * env + (1 - eAt) * ax else eRe * env + (1 - eRe) * ax
            if (env < umbralE) x *= Math.pow(env / umbralE, 3.0)
            for (f in filtros) x = f.paso(x)
            val ay = Math.abs(x)
            cEnv = if (ay > cEnv) cAt * cEnv + (1 - cAt) * ay else cRe * cEnv + (1 - cRe) * ay
            if (cEnv > umbralC) x *= Math.pow(cEnv / umbralC, 1 / razon - 1)
            x *= recupera
            // Limitador: por encima de −1 dB se dobla con suavidad, sin recortar.
            val lim = 0.89
            if (Math.abs(x) > lim) x = Math.signum(x) * (lim + (1 - lim) * Math.tanh((Math.abs(x) - lim) / (1 - lim)))
            out[i] = x.toFloat()
        }
        return out
    }
}
