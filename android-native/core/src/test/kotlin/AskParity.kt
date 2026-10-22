package eus.argos.paridad

import eus.argos.core.Ask
import eus.argos.core.TrackStore
import eus.argos.core.Trajectory
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

/**
 * El chat contra ask.js: cada frase de la referencia tiene que entenderse
 * igual (intención, ventana de tiempo exacta, etiqueta y clase). Y luego unas
 * respuestas de verdad sobre una memoria sembrada: cifras que cuadran con lo
 * guardado y que no cuentan como persona lo que no clasificó el detector.
 */
fun main(args: Array<String>) {
    val ref = File(args.getOrElse(0) { "android-native/build/ask_ref.txt" }).readLines().filter { it.isNotBlank() }
    val now = java.time.LocalDateTime.of(2026, 10, 22, 18, 37).toInstant(ZoneOffset.UTC).toEpochMilli()
    var fallos = 0
    for (l in ref) {
        val c = l.split("\t")
        val p = Ask.parse(c[0], now, ZoneOffset.UTC)
        val js = c[1].split(" ")
        val okIntent = p.intent.name == js[0] && (p.intent != Ask.Intent.OBJETO || p.obj == js[1].toInt())
        val v = Ask.ventana(c[0], now, ZoneOffset.UTC)
        val okVentana = if (c[2] == "-") v == null else v != null &&
            (v.desde?.toString() ?: "null") == c[2] && (v.hasta?.toString() ?: "null") == c[3] && v.etiqueta == c[4]
        val okClase = (Ask.clase(c[0]).first ?: "null") == c[5]
        if (!(okIntent && okVentana && okClase)) {
            fallos++
            println("  DISTINTO «${c[0]}»: KT ${p.intent} ${v?.desde} ${v?.hasta} «${v?.etiqueta}» ${Ask.clase(c[0]).first} · JS ${c.drop(1)}")
        }
    }
    println("chat: ${ref.size} frases entendidas como ask.js, $fallos distintas")

    // Respuestas sobre una memoria sembrada.
    val dir = Files.createTempDirectory("argos-chat").toFile()
    val store = TrackStore(dir, ZoneOffset.UTC)
    val t0 = now / 1000.0 - 3 * 3600          // hace tres horas
    fun tr(id: Int, cls: String, det: Boolean, dt: Double, v: Double = Double.NaN, ev: List<String> = emptyList()) =
        Trajectory(id, cls, det, dt, dt + 8, v, listOf(doubleArrayOf(dt, 1.0, 1.0)), ev)
    store.save(tr(1, "person", true, 600.0, 1.4), t0)
    store.save(tr(2, "person", true, 9000.0, 1.2), t0)          // hace 30 min
    store.save(tr(3, "person", false, 9100.0), t0)              // una mancha alta: no es una persona
    store.save(tr(4, "vehicle", true, 9500.0, 8.0, listOf("inusual")), t0)
    val ask = Ask(object : Ask.Source {
        override fun rows(desdeS: Double?, hastaS: Double?, nowS: Double) = store.rowsBetween(desdeS, hastaS, nowS)
        override fun live() = Ask.Live(2, 0, 3, listOf("objeto 9: va contra el flujo habitual"), false, true, emptyList())
    }, ZoneOffset.UTC) { now }
    val casos = listOf(
        "cuánta gente pasó en la última hora" to "1 persona en la última hora",
        "cuántas personas hoy" to "2 personas hoy",
        "lo más rápido de hoy" to "Objeto 4 (vehículos), 28,8 km/h",
        "algo raro" to "1 cosa fuera de lo normal ahora",
        "qué ves" to "Ahora: 2 personas y 1 movimiento sin clase",
        "objeto 4" to "Objeto 4: vehículo,",
        // Lo que se le dijo en el teléfono y no entendió.
        "hola qué tal" to "Hola. Ahora veo 2 personas.",
        "cuáles son las preguntas que tienes para responder" to "Puedo contarte",
        "qué puedes hacer" to "Puedo contarte",
        "quién eres" to "Soy ARGOS",
        "me estás grabando" to "No grabo vídeo",
        "hay alguien" to "Ahora: 2 personas",
        "hay alguien hoy" to "Hoy: 2 personas, 1 vehículo, 1 movimiento sin clase",
        "gracias" to "De nada",
        "blablá" to "No te he entendido",
        "hay drones" to "La radio está apagada",
    )
    for ((q, esperado) in casos) {
        val r = ask.ask(q)
        val ok = r.startsWith(esperado)
        if (!ok) fallos++
        println("  «$q» -> $r${if (ok) "" else "   [esperaba: $esperado…]"}")
    }
    // Con la radio encendida.
    val conRadio = Ask(object : Ask.Source {
        override fun rows(desdeS: Double?, hastaS: Double?, nowS: Double) = store.rowsBetween(desdeS, hastaS, nowS)
        override fun live() = null
        override fun radio() = Ask.RadioLive(7, mapOf("rastreador Find My lejos de su dueño" to 1),
            listOf("multirrotor 1581F5FKD223S00AB123 a 120 m"), "normal",
            listOf("rastreador Find My lejos de su dueño cerca desde hace 12 min"))
    }, ZoneOffset.UTC) { now }
    for ((q, esperado) in listOf(
        "hay drones?" to "Oigo 1 dron: multirrotor 1581F5FKD223S00AB123 a 120 m.",
        "me están siguiendo con un airtag" to "Oigo 1 rastreador Find My lejos de su dueño. Aviso:",
        "cuántos aparatos bluetooth hay" to "Oigo 7 aparatos Bluetooth",
        "hay interferencias en el gps" to "GNSS: normal.",
    )) {
        val r = conRadio.ask(q)
        val ok = r.startsWith(esperado)
        if (!ok) fallos++
        println("  «$q» -> $r${if (ok) "" else "   [esperaba: $esperado…]"}")
    }
    dir.deleteRecursively()
    println(if (fallos == 0) "chat: TODO BIEN" else "chat: $fallos FALLOS")
    if (fallos != 0) System.exit(1)
}
