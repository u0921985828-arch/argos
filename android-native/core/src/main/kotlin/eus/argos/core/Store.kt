package eus.argos.core

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Memoria de la app: trayectorias, nada más.
 *
 * Las reglas de privacidad del proyecto no son opcionales, y aquí se cumplen
 * por construcción: el formato no tiene sitio para una imagen. Cada línea es
 * clase, tiempos, velocidad máxima, eventos y unos pocos puntos del recorrido.
 * Un fichero por día; al abrir se borran los de más de 30 días; se puede
 * borrar un objeto concreto o todo.
 */
class TrackStore(private val dir: File, private val zone: ZoneId = ZoneId.systemDefault(),
                 private val retentionDays: Long = 30) {

    init { dir.mkdirs() }

    private fun fileFor(day: LocalDate) = File(dir, "trayectorias-$day.tsv")
    private fun dayOf(epochS: Double) = Instant.ofEpochMilli((epochS * 1000).toLong()).atZone(zone).toLocalDate()

    /** Retención: ficheros de días anteriores a hoy − 30. Devuelve cuántos borró. */
    fun applyRetention(nowEpochS: Double): Int {
        val limit = dayOf(nowEpochS).minusDays(retentionDays)
        var n = 0
        dir.listFiles { f -> f.name.startsWith("trayectorias-") && f.name.endsWith(".tsv") }?.forEach { f ->
            val d = runCatching { LocalDate.parse(f.name.removePrefix("trayectorias-").removeSuffix(".tsv")) }.getOrNull()
            if (d != null && d.isBefore(limit) && f.delete()) n++
        }
        return n
    }

    /**
     * Guarda una trayectoria. `t0EpochS` convierte los tiempos de la sesión
     * (segundos desde que arrancó) a hora real.
     */
    @Synchronized
    fun save(tr: Trajectory, t0EpochS: Double) {
        val start = t0EpochS + tr.start; val end = t0EpochS + tr.end
        val pts = tr.points.joinToString(";") { "%.1f,%.0f,%.0f".format(java.util.Locale.ROOT, t0EpochS + it[0], it[1], it[2]) }
        val line = listOf(
            "%d-%d".format((start * 1000).toLong(), tr.id), tr.cls, if (tr.fromDet) "det" else "forma",
            "%.1f".format(java.util.Locale.ROOT, start), "%.1f".format(java.util.Locale.ROOT, end),
            if (tr.maxSpeedMs.isFinite()) "%.2f".format(java.util.Locale.ROOT, tr.maxSpeedMs) else "",
            tr.events.distinct().joinToString(","), pts).joinToString("\t")
        fileFor(dayOf(start)).appendText(line + "\n")
    }

    class Row(val key: String, val cls: String, val fromDet: Boolean, val start: Double, val end: Double,
              val maxSpeedMs: Double, val events: List<String>)

    fun rows(day: LocalDate): List<Row> {
        val f = fileFor(day)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val c = l.split("\t")
            if (c.size < 8) null else Row(c[0], c[1], c[2] == "det", c[3].toDouble(), c[4].toDouble(),
                c[5].toDoubleOrNull() ?: Double.NaN, if (c[6].isEmpty()) emptyList() else c[6].split(","))
        }
    }

    class Summary(val day: LocalDate, val people: Int, val vehicles: Int, val bikes: Int, val unclassified: Int,
                  val perHour: IntArray, val events: Map<String, Int>, val longestStayS: Double)

    /**
     * Resumen del día. Solo cuenta como persona o vehículo lo que clasificó el
     * DETECTOR: una mancha alta no es una persona, y ponerla en el marcador es
     * afirmarlo.
     */
    fun summary(day: LocalDate): Summary {
        val rs = rows(day)
        val perHour = IntArray(24)
        for (r in rs) if (r.fromDet && r.cls == "person")
            perHour[Instant.ofEpochMilli((r.start * 1000).toLong()).atZone(zone).hour]++
        val ev = HashMap<String, Int>()
        for (r in rs) for (e in r.events) ev[e] = (ev[e] ?: 0) + 1
        return Summary(day,
            rs.count { it.fromDet && it.cls == "person" },
            rs.count { it.fromDet && it.cls == "vehicle" },
            rs.count { it.fromDet && it.cls == "bike" },
            rs.count { !it.fromDet }, perHour, ev,
            rs.filter { it.fromDet }.maxOfOrNull { it.end - it.start } ?: 0.0)
    }

    /** Borrado por objeto: la línea desaparece del fichero de su día. */
    @Synchronized
    fun delete(key: String): Boolean {
        val ms = key.substringBefore("-").toLongOrNull() ?: return false
        val f = fileFor(dayOf(ms / 1000.0))
        if (!f.exists()) return false
        val keep = f.readLines().filterNot { it.startsWith("$key\t") }
        val changed = keep.size != f.readLines().size
        f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n") + "\n")
        return changed
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles { f -> f.name.startsWith("trayectorias-") }?.forEach { it.delete() }
    }
}
