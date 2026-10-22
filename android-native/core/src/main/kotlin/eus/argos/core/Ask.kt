package eus.argos.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

/**
 * El chat con ARGOS. Puerto de `ask.js`: se pregunta en español corriente y
 * responde en una línea.
 *
 * Parece un chat y no lo es, a propósito: no hay modelo de lenguaje detrás,
 * hay una gramática. Un modelo generativo sobre datos de vigilancia RELLENA
 * los huecos, y aquí un hueco relleno es una afirmación inventada sobre dónde
 * estuvo alguien. Un intérprete determinista tiene el defecto contrario, que
 * es el bueno: cuando no entiende, lo dice. Y no necesita red: la pregunta, la
 * voz y los datos no salen del teléfono.
 *
 * Reglas de la respuesta:
 *   - números de objeto, nunca nombres ni descripciones de personas
 *   - cada cifra sale de una consulta real a la memoria o a la escena viva
 *   - lo que no se sabe se declara; no se estima para rellenar
 */
class Ask(private val src: Source, private val zone: ZoneId = ZoneId.systemDefault(),
          private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** Lo que está pasando ahora, desde la escena viva. */
    class Live(val people: Int, val vehicles: Int, val tracks: Int, val unusual: List<String>,
               val moving: Boolean, val scaleValid: Boolean, val lastEvents: List<String>)

    /** La radio pasiva, si está encendida. Nada de esto identifica a nadie. */
    class RadioLive(val aparatos: Int, val rastreadores: Map<String, Int>, val drones: List<String>,
                    val gnss: String, val avisos: List<String>)

    interface Source {
        fun rows(desdeS: Double?, hastaS: Double?, nowS: Double): List<TrackStore.Row>
        fun live(): Live?
        /** null = el módulo de radio está apagado. */
        fun radio(): RadioLive? = null
    }

    enum class Intent { OBJETO, AHORA, ANOMALIAS, RAPIDO, CONTAR, RESUMEN, AYUDA }
    class Parsed(val intent: Intent, val window: Window?, val cls: String?, val clsNombre: String, val obj: Int?)
    /** [desde, hasta) en milisegundos de reloj; null = sin límite. */
    class Window(val desde: Long?, val hasta: Long?, val etiqueta: String)

    companion object {
        /** Un saludo al principio de la frase, para separarlo de la pregunta. */
        private val SALUDO = Regex("^\\W*(hola|buenas( tardes| noches)?|buenos dias|hey|ey|saludos)\\b")
        const val MIN = 60_000L; const val HORA = 3_600_000L; const val DIA = 24 * HORA
        val AYUDA = listOf("cuánta gente pasó en la última hora", "qué pasó entre las 3 y las 4", "algo raro hoy",
                           "vehículos esta semana", "objeto 47", "lo más rápido de hoy")
        private val CLASES = listOf(
            Triple(Regex("\\b(personas?|gente|peatones?|viandantes?)\\b"), "person", "personas"),
            Triple(Regex("\\b(coches?|vehiculos?|furgonetas?|camiones?|autobuses?)\\b"), "vehicle", "vehículos"),
            Triple(Regex("\\b(bicis?|bicicletas?|motos?|patinetes?)\\b"), "bike", "bicis y motos"))

        /** Minúsculas y sin tildes, como `norm` del JS (mismo rango de diacríticos). */
        fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")

        fun clase(q: String): Pair<String?, String> {
            val n = norm(q)
            for ((re, id, nombre) in CLASES) if (re.containsMatchIn(n)) return id to nombre
            return null to "objetos"
        }

        fun ventana(q: String, now: Long, zone: ZoneId): Window? {
            val n = norm(q)
            val medianoche = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            Regex("\\b(?:entre|de)\\s+(?:las?\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s+(?:y|a|hasta)\\s+(?:las?\\s+)?(\\d{1,2})(?::(\\d{2}))?",
                  RegexOption.IGNORE_CASE).find(n)?.let { m ->
                val h1 = m.groupValues[1].toInt(); val m1 = m.groupValues[2].ifEmpty { "0" }.toInt()
                val h2 = m.groupValues[3].toInt(); val m2 = m.groupValues[4].ifEmpty { "0" }.toInt()
                if (h1 < 24 && h2 < 24) {
                    var a = medianoche + h1 * HORA + m1 * MIN
                    var b = medianoche + h2 * HORA + m2 * MIN
                    if (b <= a) b += DIA
                    if (a > now) { a -= DIA; b -= DIA }
                    return Window(a, b, "entre las $h1:${"%02d".format(m1)} y las $h2:${"%02d".format(m2)}")
                }
            }
            Regex("\\bultim[oa]s?\\s+(\\d+|media)\\s*(min(?:uto)?s?|h(?:ora)?s?)\\b").find(n)?.let { m ->
                val media = m.groupValues[1] == "media"
                val cuanto = if (media) 0.5 else m.groupValues[1].toDouble()
                val esHora = m.groupValues[2].startsWith("h")
                val span = (cuanto * (if (esHora) HORA else MIN)).toLong()
                val unidad = if (esHora) (if (cuanto == 1.0 || media) "hora" else "horas")
                             else (if (cuanto == 1.0) "minuto" else "minutos")
                val c = if (cuanto == Math.floor(cuanto)) cuanto.toLong().toString() else cuanto.toString()
                return Window(now - span, now, if (media) "en la última media $unidad" else "en los últimos $c $unidad")
            }
            if (Regex("\\b(ahora|en este momento|ahora mismo)\\b").containsMatchIn(n)) return Window(now - 2 * MIN, now, "ahora")
            if (Regex("\\b(ultima hora|esta hora)\\b").containsMatchIn(n)) return Window(now - HORA, now, "en la última hora")
            if (Regex("\\bhoy\\b").containsMatchIn(n)) return Window(medianoche, now, "hoy")
            if (Regex("\\bayer\\b").containsMatchIn(n)) return Window(medianoche - DIA, medianoche, "ayer")
            if (Regex("\\b(esta noche|de noche|madrugada)\\b").containsMatchIn(n)) return Window(medianoche, medianoche + 7 * HORA, "esta madrugada")
            if (Regex("\\b(esta semana|7 dias|ultima semana)\\b").containsMatchIn(n)) return Window(now - 7 * DIA, now, "esta semana")
            if (Regex("\\b(siempre|todo|en total|historico)\\b").containsMatchIn(n)) return Window(null, null, "en toda la memoria")
            return null
        }

        /** El enrutado de `_route`, separado para poder compararlo con el JS. */
        fun parse(q: String, now: Long, zone: ZoneId): Parsed {
            val n = norm(q)
            val (cls, nombre) = clase(q)
            (Regex("\\bobjeto\\s+(\\d+)\\b").find(n) ?: Regex("^\\s*#?(\\d{1,6})\\s*$").find(n))?.let {
                return Parsed(Intent.OBJETO, null, cls, nombre, it.groupValues[1].toInt())
            }
            val v = ventana(q, now, zone)
            if (Regex("\\b(ahora mismo|que ves|que hay|estado)\\b").containsMatchIn(n) && v == null)
                return Parsed(Intent.AHORA, null, cls, nombre, null)
            val intent = when {
                Regex("\\b(raro|anomal|extran|inusual|avisos?|alertas?|llamativ)\\w*").containsMatchIn(n) -> Intent.ANOMALIAS
                Regex("\\b(rapid|corriend|veloz|deprisa)\\w*").containsMatchIn(n) -> Intent.RAPIDO
                Regex("\\b(cuant|numero de|total de|contar)\\w*").containsMatchIn(n) -> Intent.CONTAR
                Regex("\\b(que paso|que hubo|resumen|como fue|actividad)\\b").containsMatchIn(n) -> Intent.RESUMEN
                v != null && cls != null -> Intent.CONTAR
                v != null -> Intent.RESUMEN
                else -> Intent.AYUDA
            }
            return Parsed(intent, v, if (intent == Intent.RESUMEN && v != null && cls == null) null else cls,
                          if (intent == Intent.RESUMEN && v != null && cls == null) "objetos" else nombre, null)
        }

        private fun singular(c: String?) = when (c) {
            "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici o moto"; else -> "objeto"
        }

        private fun nombre(c: String) = when (c) {
            "person" -> "personas"; "vehicle" -> "vehículos"; "bike" -> "bicis"; else -> c
        }
    }

    private fun hora(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    /** Una pregunta, una línea. */
    fun ask(raw: String): String {
        val q = raw.trim()
        val now = clock()
        if (q.isEmpty()) return "Pregúntame algo. Por ejemplo " + AYUDA.take(3).joinToString(", ") { "«$it»" } + "."
        // «hola, cuánta gente pasó hoy»: el saludo no se come la pregunta.
        val resto = SALUDO.replaceFirst(norm(q), "").trim(' ', ',', '.', '!', '¡', '?', '¿')
        if (resto.isNotEmpty() && resto.length < norm(q).trim().length) return ask(resto)
        return try { charla(norm(q), now) ?: answer(parse(q, now, zone), now) }
        catch (e: Exception) { "No he podido consultar la memoria: ${e.message}" }
    }

    /**
     * Lo que no es una consulta: saludar, preguntar qué sabe hacer, quién es,
     * qué guarda. Va ANTES de la gramática de consultas y no la toca, así que
     * la paridad con ask.js sigue valiendo para todo lo que pregunta datos.
     * Las respuestas son fijas o salen de la escena viva; nada se inventa.
     */
    private fun charla(n: String, now: Long): String? {
        fun hay(re: String) = Regex(re).containsMatchIn(n)
        val ventana = ventana(n, now, zone)
        return when {
            hay("\\b(que (puedes|sabes) (hacer|responder|contestar)|que (te )?puedo preguntar|que preguntas|" +
                "cuales son las preguntas|preguntas (que )?(tienes|puedes|sabes)|ayuda|como funcionas|para que sirves|" +
                "que haces|instrucciones|comandos|opciones|ejemplos)\\b") -> capacidades()
            hay("\\b(quien eres|que eres|como te llamas|tu nombre|eres una? (ia|inteligencia|robot|persona))\\b") ->
                "Soy ARGOS. Miro esta cámara, cuento y sigo lo que se mueve, aprendo lo normal de la escena y aviso de " +
                "lo raro. No reconozco caras ni identifico a nadie, y no guardo vídeo: solo recorridos durante 30 días."
            hay("\\b(grabas|guardas|privacidad|me (estas )?grabando|subes|envias|nube|internet)\\b") ->
                "No grabo vídeo ni fotos. Guardo solo recorridos (clase, hora, velocidad y unos puntos del camino) " +
                "durante 30 días, en este teléfono. Nada sale de él. Puedes borrarlo desde el botón «Memoria»."
            hay("\\b(drones?|remote id|aeronaves?)\\b") -> conRadio { r ->
                if (r.drones.isEmpty()) "No oigo ningún dron con Remote ID ahora mismo."
                else "Oigo ${cuantos(r.drones.size, "dron", "drones")}: ${r.drones.joinToString("; ")}."
            }
            hay("\\b(rastreador(es)?|localizador(es)?|airtags?|tiles?|smarttags?|(me )?(estan|esta) siguiendo|me siguen)\\b") -> conRadio { r ->
                if (r.rastreadores.isEmpty()) "No oigo ningún rastreador cerca. Ojo: uno apagado o sin batería no se oye."
                else "Oigo " + y(r.rastreadores.entries.map { "${it.value} ${it.key}" }) + "." +
                    (r.avisos.firstOrNull { it.contains("desde hace") }?.let { " Aviso: $it." } ?: "")
            }
            hay("\\b(bluetooth|aparatos? cerca|dispositivos?( cerca)?|radio)\\b") -> conRadio { r ->
                "Oigo ${cuantos(r.aparatos, "aparato Bluetooth", "aparatos Bluetooth")} en el último minuto" +
                    (if (r.rastreadores.isNotEmpty()) ", de ellos ${cuantos(r.rastreadores.values.sum(), "rastreador", "rastreadores")}" else "") +
                    (if (r.drones.isNotEmpty()) " y ${cuantos(r.drones.size, "dron", "drones")}" else "") + "."
            }
            hay("\\b(gps|gnss|satelites?|inhibidor(es)?|interferencias?|jammer|suplantacion|spoofing)\\b") -> conRadio { r -> "GNSS: ${r.gnss}." }
            hay("^\\W*(muchas )?(gracias|genial|perfecto|vale|ok|de acuerdo)\\W*$") -> "De nada. Aquí sigo mirando."
            hay("\\bque hora es\\b") -> "Son las ${hora(now)}."
            hay("^\\W*(hola|buenas( tardes| noches)?|buenos dias|hey|ey|saludos)\\W*$") ||
            hay("^\\W*(que tal|como estas|como vas)\\W*$") -> {
                val l = src.live()
                val estado = when {
                    l == null -> "Estoy arrancando la cámara."
                    l.people + l.vehicles == 0 -> "Ahora mismo no veo a nadie."
                    else -> "Ahora veo " + y(listOfNotNull(l.people.takeIf { it > 0 }?.let { cuantos(it, "persona", "personas") },
                        l.vehicles.takeIf { it > 0 }?.let { cuantos(it, "vehículo", "vehículos") })) + "."
                }
                "Hola. $estado Pregúntame «qué ves», «cuánta gente pasó hoy» o «algo raro», o di «ayuda»."
            }
            // Presencia en el momento, dicha de mil maneras.
            // Sin ventana de tiempo y sin pedir nada concreto (raro, rápido, parado),
            // que eso lo contesta la gramática de consultas.
            ventana == null && !hay("\\b(raro|anomal|extran|inusual|fuera de lo|corr|rapid|aparcad|parad)\\w*") &&
            hay("\\b(hay (alguien|gente|personas?|nadie|coches?)|ves (a )?(alguien|gente)|" +
                "que (esta|estas) (pasando|viendo)|que pasa ahora|quien hay|alguien ahi)\\b") -> answer(
                Parsed(Intent.AHORA, null, null, "objetos", null), now)
            else -> null
        }
    }

    private fun conRadio(f: (RadioLive) -> String): String =
        src.radio()?.let(f) ?: "La radio está apagada. Actívala en el panel (botón «Radio») para oír rastreadores, " +
            "drones con Remote ID e interferencias de GPS. Solo escucha; no emite nada."

    private fun capacidades() =
        "Puedo contarte: lo que veo ahora («qué ves», «hay alguien»); cuánta gente o cuántos vehículos pasaron " +
        "(«cuánta gente pasó en la última hora», «vehículos esta semana»); qué pasó en un rato («qué pasó entre las 3 y las 4», " +
        "«resumen de hoy»); lo raro («algo raro hoy»); lo más rápido («lo más rápido de hoy»); y un objeto concreto («objeto 47»). " +
        "Con la radio encendida, también rastreadores, drones e interferencias de GPS («hay drones», «algún rastreador»). " +
        "Y qué guardo y qué no («qué guardas»)."

    private fun cuantos(n: Int, uno: String, varios: String) = if (n == 1) "1 $uno" else "$n $varios"
    private fun y(partes: List<String>) = if (partes.size == 1) partes[0] else partes.dropLast(1).joinToString(", ") + " y " + partes.last()

    private fun rows(w: Window?, cls: String?, now: Long): List<TrackStore.Row> =
        src.rows(w?.desde?.let { it / 1000.0 }, w?.hasta?.let { it / 1000.0 }, now / 1000.0)
            // Contar como persona o vehículo solo lo que clasificó el detector:
            // una mancha alta no es una persona.
            .filter { cls == null || (it.fromDet && it.cls == cls) }

    private fun answer(p: Parsed, now: Long): String = when (p.intent) {
        Intent.AYUDA -> "No te he entendido. Prueba con " +
            AYUDA.take(3).joinToString(", ") { "«$it»" } + ", o di «ayuda» para ver todo lo que sé."
        Intent.AHORA -> src.live()?.let { l ->
            listOfNotNull(
                l.people.takeIf { it > 0 }?.let { cuantos(it, "persona", "personas") },
                l.vehicles.takeIf { it > 0 }?.let { cuantos(it, "vehículo", "vehículos") },
                (l.tracks - l.people - l.vehicles).takeIf { it > 0 }?.let { cuantos(it, "movimiento sin clase", "movimientos sin clase") },
            ).let { if (it.isEmpty()) "Ahora no veo a nadie" else "Ahora: " + y(it) } +
                (if (l.moving) "; la cámara se está moviendo." else ".") +
                (if (l.unusual.isNotEmpty()) " Fuera de lo normal: ${l.unusual.first()}." else "")
        } ?: "La cámara no está analizando ahora mismo."
        Intent.OBJETO -> {
            val r = src.rows(null, null, now / 1000.0).firstOrNull { it.key.endsWith("-${p.obj}") }
            if (r == null) "No tengo el objeto ${p.obj} en la memoria."
            else buildString {
                val que = when (r.cls) { "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici"; else -> r.cls }
                append("Objeto ${p.obj}: ${if (r.fromDet) que else "movimiento sin clase"}, visto a las ${hora((r.start * 1000).toLong())}")
                append(", ${(r.end - r.start).toInt()} s en escena")
                if (r.maxSpeedMs.isFinite()) append(", hasta ${"%.1f".format(java.util.Locale("es"), r.maxSpeedMs * 3.6)} km/h")
                if (r.events.isNotEmpty()) append(", eventos: ${r.events.joinToString(", ")}")
                append(".")
            }
        }
        Intent.CONTAR -> {
            val w = p.window
            if (w == null) "¿En qué periodo? Dime «hoy», «última hora» o «entre las 3 y las 4»."
            else {
                val rs = rows(w, p.cls, now)
                if (rs.isEmpty()) "${when (p.cls) { "person" -> "Ninguna persona"; "vehicle" -> "Ningún vehículo"; "bike" -> "Ninguna bici ni moto"; else -> "Ningún objeto" }} ${w.etiqueta}."
                else "${cuantos(rs.size, singular(p.cls), p.clsNombre)} ${w.etiqueta}. El primero a las ${hora((rs.last().start * 1000).toLong())}, " +
                    "el último a las ${hora((rs.first().start * 1000).toLong())}."
            }
        }
        Intent.RESUMEN -> {
            val w = p.window
            if (w == null) "¿De cuándo? Dime «hoy», «última hora» o un rango de horas."
            else {
                val rs = rows(w, p.cls, now)
                val evs = rs.sumOf { it.events.size }
                if (rs.isEmpty()) "Sin actividad registrada ${w.etiqueta}."
                else {
                    val por = rs.groupingBy { if (it.fromDet) it.cls else "movimiento" }.eachCount()
                        .entries.sortedByDescending { it.value }
                        .joinToString(", ") { if (it.key == "movimiento") cuantos(it.value, "movimiento sin clase", "movimientos sin clase")
                                              else cuantos(it.value, singular(it.key), nombre(it.key)) }
                    w.etiqueta.replaceFirstChar { it.uppercase() } + ": " + por + (if (evs > 0) ", y ${cuantos(evs, "evento", "eventos")}." else ".")
                }
            }
        }
        Intent.ANOMALIAS -> {
            val l = src.live()
            if (l != null && l.unusual.isNotEmpty()) "${cuantos(l.unusual.size, "cosa", "cosas")} fuera de lo normal ahora. La más fuerte: ${l.unusual.first()}."
            else {
                val w = p.window ?: Window(null, null, "en toda la memoria")
                val evs = rows(w, null, now).flatMap { it.events }
                val raros = evs.filter { it == "inusual" || it == "anómalo" }
                if (evs.isEmpty()) "Nada marcado como anómalo ${w.etiqueta}."
                else "${cuantos(evs.size, "evento", "eventos")} ${w.etiqueta}" + (if (raros.isNotEmpty()) ", ${raros.size} fuera de lo normal" else "") + ": " +
                    evs.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value}× ${it.key}" } + "."
            }
        }
        Intent.RAPIDO -> {
            val w = p.window ?: Window(null, null, "en toda la memoria")
            val rs = rows(w, p.cls, now).filter { it.maxSpeedMs.isFinite() }
            if (rs.isEmpty()) "No tengo velocidades fiables ${w.etiqueta}. Hace falta que la escala métrica se haya aprendido con la cámara quieta; sin eso no doy números inventados."
            else {
                val top = rs.maxBy { it.maxSpeedMs }
                "Objeto ${top.key.substringAfter("-")} (${if (top.fromDet) nombre(top.cls) else "sin clase"}), " +
                    "${"%.1f".format(java.util.Locale("es"), top.maxSpeedMs * 3.6)} km/h a las ${hora((top.start * 1000).toLong())}. " +
                    "De ${rs.size} con velocidad medida ${w.etiqueta}."
            }
        }
    }
}
