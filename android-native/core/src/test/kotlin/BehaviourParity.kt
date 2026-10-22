package eus.argos.paridad

import eus.argos.core.BehaviourModel
import java.io.File

/**
 * Paridad del modelo de comportamiento con behaviour.js: lo aprendido de 240
 * recorridos (madurez, perfiles de celda) y la puntuación de cinco recorridos
 * nuevos (normal, contramano, corriendo, vehículo por la acera, zona nueva).
 * Además, que lo guardado y recargado puntúe igual.
 */
fun main(args: Array<String>) {
    val lines = File(args.getOrElse(0) { "android-native/build/behaviour_ref.txt" }).readLines()
    fun num(s: String) = if (s == "NaN" || s == "null") Double.NaN else s.toDouble()
    fun igual(a: Double, b: Double, tol: Double = 1e-9) = (a.isNaN() && b.isNaN()) ||
        Math.abs(a - b) <= tol * maxOf(1.0, Math.abs(a), Math.abs(b))
    fun recorrido(l: String): BehaviourModel.Path {
        val (cls, n, pts) = l.split(" ", limit = 3)
        val tr = pts.split(";").map { it.split(",").map { v -> num(v) } }
        return BehaviourModel.Path(tr.map { doubleArrayOf(it[0], it[1]) }, DoubleArray(tr.size) { tr[it][2] }, null, cls)
    }
    val m = BehaviourModel(1280.0, 720.0)
    var fallos = 0
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        when {
            l.startsWith("LEARN ") -> {
                val n = l.split(" ")[1].toInt()
                for (k in 1..n) m.learn(recorrido(lines[i + k]))
                i += n
            }
            l.startsWith("MAT ") -> {
                val e = l.split(" ").drop(1); val mt = m.maturity()
                val ok = e[0].toInt() == mt.cellsTotal && e[1].toInt() == mt.cellsTouched &&
                    e[2].toInt() == mt.cellsMature && e[3].toInt() == mt.directional &&
                    e[4].toInt() == mt.observations && (e[5] == "true") == mt.ready
                if (!ok) fallos++
                println("madurez: ${mt.cellsMature} celdas maduras de ${mt.cellsTouched} -> ${if (ok) "IGUAL" else "DISTINTA"}")
            }
            l.startsWith("PROF ") -> {
                val e = l.split(" ")
                val p = m.profile(e[1].toDouble(), e[2].toDouble())
                val ok = if (e[3] == "null") p == null else p != null && p.n == e[3].toInt() &&
                    igual(p.flow.concentration, num(e[4])) && p.modes.size == e[5].toInt() &&
                    (e[5] == "0" || e[6].split(";").zip(p.modes).all { (s, mo) ->
                        val v = s.split(",").map { num(it) }
                        igual(mo.heading, v[0]) && igual(mo.concentration, v[1]) && igual(mo.share, v[2]) }) &&
                    (if (e[7] == "null") p.speed == null else e[7].split(",").map { num(it) }.let { q ->
                        p.speed != null && igual(p.speed.p05, q[0]) && igual(p.speed.p50, q[1]) &&
                            igual(p.speed.p95, q[2]) && igual(p.speed.width, q[3]) }) &&
                    igual(p.classes["person"] ?: Double.NaN, num(e[8]))
                if (!ok) { fallos++; println("  perfil distinto en ${e[1]},${e[2]}") }
            }
            l.startsWith("SCORE ") -> {
                val (_, nombre, resto) = l.split(" ", limit = 3)
                val tr = recorrido(resto)
                val e = lines[i + 1].removePrefix("RES ").substringBefore(" | ").split(" ")
                fun cmp(mm: BehaviourModel): Boolean {
                    val s = mm.score(tr)
                    return igual(s.score, num(e[0])) && s.mature == (e[1] == "true") && igual(s.coverage, num(e[2])) &&
                        (!s.mature || (igual(Math.round(s.direction * 1000) / 1000.0, num(e[3])) &&
                         igual(Math.round(s.speedExcess * 100) / 100.0, num(e[4])) &&
                         igual(Math.round(s.classShare * 1000) / 1000.0, num(e[5])) &&
                         igual(Math.round(s.unknownFrac * 100) / 100.0, num(e[6]))))
                }
                val s = m.score(tr)
                val ok = cmp(m)
                val recargado = BehaviourModel.deserialize(m.serialize())!!
                // La persistencia guarda velocidades con 3 decimales: la puntuación
                // recargada se compara con tolerancia, no al bit.
                val sr = recargado.score(tr)
                val okGuardado = Math.abs(sr.score - s.score) < 1e-3 && sr.mature == s.mature
                if (!ok || !okGuardado) fallos++
                println("puntuación $nombre: ${"%.3f".format(s.score)} · ${s.reasons.joinToString(" / ").ifEmpty { "normal" }} " +
                        "-> ${if (ok) "IGUAL" else "DISTINTA"}${if (okGuardado) "" else " (recargado distinto)"}")
                i++
            }
        }
        i++
    }
    println(if (fallos == 0) "comportamiento: TODO IGUAL" else "comportamiento: $fallos FALLOS")
    if (fallos != 0) System.exit(1)
}
