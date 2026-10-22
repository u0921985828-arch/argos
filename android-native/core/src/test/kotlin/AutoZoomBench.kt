package eus.argos.paridad

import eus.argos.core.AutoZoom
import java.io.File

/**
 * C3 de la fase 4: el zoom automático elige cuadro entero con objetos grandes
 * (bus, zidane, dog) y teselas ×2 con personas pequeñas (plaza), y en la plaza
 * eso encuentra más que el cuadro entero. Doce pasadas por escena, como las
 * haría la app sobre una cámara quieta.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "android-native/build/det" })
    val det = detectorDe(dir)
    val esperado = mapOf("bus" to 0.0, "zidane" to 0.0, "dog" to 0.0, "plaza" to 2.0)
    val todas = metaDe(dir) + Regex("\"(\\w+)\": \\[(\\d+), (\\d+)\\]").findAll(File(dir, "plaza.json").readText())
        .map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.toList()
    var fallos = 0
    for ((name, w, h) in todas) {
        val rgba = File(dir, "$name.rgba").readBytes()
        val az = AutoZoom()
        val modos = StringBuilder()
        var ultimos = 0
        repeat(12) {
            val z = az.next()
            val d = det.detect(rgba, w * 4, w, h, z)
            az.report(z, d)
            modos.append(if (z == 0.0) "C" else "T")
            if (z == az.mode) ultimos = d.count { it.score >= 0.3 }
        }
        val ok = az.mode == esperado[name]
        if (!ok) fallos++
        println("C3 $name: pasadas $modos · termina en ${if (az.mode == 0.0) "cuadro entero" else "teselas x2"} " +
                "con $ultimos objetos >= 0,3 -> ${if (ok) "CUMPLE" else "NO"}")
    }
    if (args.contains("exigir") && fallos > 0) System.exit(1)
}
