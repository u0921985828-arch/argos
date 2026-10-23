package eus.argos.paridad

import eus.argos.core.DetBox
import eus.argos.core.DetectionMemory
import eus.argos.core.Entorno

/** Aprender por dónde pasan las cosas y mirar ahí, sin dejar de barrer. */
fun main() {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val w = 960; val h = 540
    // 4×3 teselas de 240×180.
    val todas = (0 until 3).flatMap { j -> (0 until 4).map { i -> intArrayOf(i * 240, j * 180, i * 240 + 240, j * 180 + 180) } }
    val e = Entorno()
    pide("sin entorno aprendido, todas las teselas", e.plan(todas, w, h, emptyList(), 1).size == 12)
    // Los coches pasan por una carretera en la fila del medio.
    repeat(60) { k -> e.anota(listOf(DetBox(100.0 + (k * 13) % 760, 250.0, 130.0 + (k * 13) % 760, 270.0, "vehicle", 0.6)), w, h) }
    val p = e.plan(todas, w, h, emptyList(), 1)
    pide("con entorno aprendido, pocas teselas (${p.size}) y de la carretera", p.size <= 5 && p.count { it[1] == 180 } >= 4)
    val conCielo = e.plan(todas, w, h, listOf(DetBox(50.0, 30.0, 70.0, 50.0, "person", 0.5)), 2)
    pide("lo que se sigue, aunque esté fuera de la carretera, entra", conCielo.any { it[0] == 0 && it[1] == 0 })
    val vistas = HashSet<String>()
    repeat(12) { k -> e.plan(todas, w, h, emptyList(), k + 1).forEach { vistas.add("${it[0]},${it[1]}") } }
    pide("por turno y con el barrido completo, al final se mira todo (${vistas.size}/12)", vistas.size == 12)
    pide("cada 5 pasadas, barrido completo", e.plan(todas, w, h, emptyList(), 5).size == 12)
    val otra = Entorno().apply { lee(e.serializa()) }
    pide("el entorno se guarda y se recupera", otra.aprendido && otra.plan(todas, w, h, emptyList(), 1).size <= 5)
    // Una pasada parcial no borra de la memoria lo que no miró.
    val m = DetectionMemory(confirmPasses = 1)
    val lejos = DetBox(800.0, 400.0, 830.0, 420.0, "vehicle", 0.6)
    m.update(listOf(lejos, DetBox(100.0, 250.0, 130.0, 270.0, "vehicle", 0.6)), 0.0)
    m.updateParcial(listOf(DetBox(104.0, 250.0, 134.0, 270.0, "vehicle", 0.6)), 100.0) { it.x1 > 480 }
    pide("una pasada parcial conserva lo que no miró y renueva lo que sí", m.boxes.size == 2 &&
        m.boxes.any { it.d.x1 == 800.0 && it.t == 0.0 } && m.boxes.any { it.d.x1 == 104.0 && it.seen == 2 })
    m.updateParcial(emptyList(), 5000.0) { it.x1 > 480 }
    pide("y lo no mirado caduca si nadie vuelve a verlo", m.boxes.isEmpty())
    println(if (fallos == 0) "ENTORNO: CUMPLE" else "ENTORNO: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
