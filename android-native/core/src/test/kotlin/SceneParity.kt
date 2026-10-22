package eus.argos.paridad

import eus.argos.core.Physics
import eus.argos.core.ScaleSample
import eus.argos.core.SceneScale
import java.io.File

/**
 * Paridad de la escena con brain.js: SceneScale.fit y Physics.update sobre los
 * conjuntos que genera `scripts/ref_scene.js` (peatones con atípicos, plaza con
 * alabeo, calle en diagonal, todos a la misma distancia, y un peatón que anda,
 * se para y sigue). Tolerancia 1e-9 relativa: es la misma aritmética en doble.
 */
fun main(args: Array<String>) {
    val lines = File(args.getOrElse(0) { "android-native/build/scene_ref.txt" }).readLines()
    var i = 0; var fallos = 0; var comparados = 0
    fun num(s: String) = if (s == "NaN") Double.NaN else s.toDouble()
    fun igual(a: Double, b: Double) = (a.isNaN() && b.isNaN()) ||
        Math.abs(a - b) <= 1e-9 * maxOf(1.0, Math.abs(a), Math.abs(b))
    var escala: SceneScale? = null
    while (i < lines.size) {
        val l = lines[i]
        if (l.startsWith("SET ")) {
            val (_, nombre, n) = l.split(" ")
            val pts = (1..n.toInt()).map { lines[i + it].split(" ") }
                .map { ScaleSample(num(it[0]), num(it[1]), num(it[2]), it[3]) }
            i += n.toInt() + 1
            val e = lines[i].split(" ").drop(1)
            val sc = SceneScale().fit(pts)
            if (nombre == "peatones") escala = sc
            val mine = listOf(sc.a, sc.b, sc.sigma, sc.n.toDouble(), if (sc.valid) 1.0 else 0.0, sc.cx,
                              if (sc.usesX) 1.0 else 0.0, sc.colinearity, sc.ySpan,
                              sc.classScale["person"] ?: Double.NaN, sc.classScale["vehicle"] ?: Double.NaN,
                              sc.metersPerPixel(500.0, 300.0), sc.z(500.0, 160.0))
            val theirs = e.mapIndexed { k, s -> if (k == 4 || k == 6) (if (s == "true") 1.0 else 0.0) else num(s) }
            val ok = mine.indices.all { igual(mine[it], theirs[it]) }
            comparados++
            if (!ok) { fallos++; println("  $nombre KT: $mine\n  $nombre JS: $theirs") }
            println("escala $nombre: ${if (sc.valid) "válida" else "no válida"} · a ${"%.4f".format(sc.a)} · " +
                    "sigma ${"%.2f".format(sc.sigma)} -> ${if (ok) "IGUAL" else "DISTINTA"}")
            i++
        } else if (l.startsWith("PHYS ")) {
            val n = l.split(" ")[1].toInt()
            val p = Physics(); var dif = 0
            for (k in 1..n) {
                val (ent, sal) = lines[i + k].split(" | ")
                val e = ent.split(" ").map { num(it) }
                p.setStopThreshold(e[3])
                p.update(e[0], e[1], e[2], escala)
                val s = sal.split(" ")
                val ok = igual(p.speedPx, num(s[0])) && igual(p.speedMs, num(s[1])) &&
                    igual(p.heading, num(s[2])) && igual(p.dwellS, num(s[3])) &&
                    igual(p.straightness, num(s[4])) && p.stopped == (s[5] == "true")
                if (!ok) dif++
            }
            comparados++
            if (dif > 0) fallos++
            println("física: $n pasos · $dif distintos -> ${if (dif == 0) "IGUAL" else "DISTINTA"}")
            i += n + 1
        } else i++
    }
    println(if (fallos == 0) "C4 escena: TODO IGUAL ($comparados comparaciones)" else "C4 escena: $fallos FALLOS")
    if (fallos != 0) System.exit(1)
}
