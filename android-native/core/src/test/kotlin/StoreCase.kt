package eus.argos.paridad

import eus.argos.core.Trajectory
import eus.argos.core.TrackStore
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

/** C5 de la fase 4: guardar, resumir, borrar un objeto y aplicar la retención. */
fun main() {
    val dir = Files.createTempDirectory("argos-memoria").toFile()
    val z = ZoneOffset.UTC
    val store = TrackStore(dir, z)
    val dia = LocalDate.of(2026, 10, 22)
    val t0 = dia.atTime(18, 0).toEpochSecond(z).toDouble()
    val viejo = dia.minusDays(40).atTime(9, 0).toEpochSecond(z).toDouble()
    fun tr(id: Int, cls: String, det: Boolean, ev: List<String> = emptyList()) =
        Trajectory(id, cls, det, 10.0 * id, 10.0 * id + 6, 1.3, listOf(doubleArrayOf(10.0 * id, 100.0, 200.0)), ev)
    store.save(tr(1, "person", true), t0); store.save(tr(2, "person", true, listOf("detenido")), t0)
    store.save(tr(3, "vehicle", true), t0); store.save(tr(4, "car", false), t0)
    store.save(tr(5, "person", true), viejo)
    val s = store.summary(dia)
    val okResumen = s.people == 2 && s.vehicles == 1 && s.unclassified == 1 && s.perHour[18] == 2 &&
        s.events["detenido"] == 1
    val clave = store.rows(dia).first { it.cls == "vehicle" }.key
    val okBorrar = store.delete(clave) && store.summary(dia).vehicles == 0 && store.summary(dia).people == 2
    val borrados = TrackStore(dir, z).applyRetention(t0)
    val okRetencion = borrados == 1 && store.rows(dia.minusDays(40)).isEmpty() && store.summary(dia).people == 2
    val sinImagen = dir.listFiles()!!.all { f -> f.readText().none { it.code == 0 } && f.length() < 4096 }
    println("resumen ${if (okResumen) "bien" else "MAL"} · borrar un objeto ${if (okBorrar) "bien" else "MAL"} · " +
            "retención ($borrados fichero de hace 40 días) ${if (okRetencion) "bien" else "MAL"} · " +
            "solo texto ${if (sinImagen) "sí" else "NO"}")
    dir.deleteRecursively()
    val ok = okResumen && okBorrar && okRetencion && sinImagen
    println(if (ok) "C5 memoria: CUMPLE" else "C5 memoria: NO")
    if (!ok) System.exit(1)
}
