package eus.argos.nativo

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import eus.argos.core.TrackStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * La memoria del día: cuántos, cuándo y qué pasó.
 *
 * Solo hay trayectorias —clase, tiempos, velocidad, eventos, unos puntos— y
 * eso es lo único que se puede enseñar, porque es lo único que se guarda. Se
 * borra un recorrido con una pulsación larga o todo con el botón; lo de más de
 * 30 días ya se borró al abrir la app.
 */
class ResumenActivity : ComponentActivity() {
    private lateinit var store: TrackStore
    private lateinit var cabecera: TextView
    private lateinit var lista: ListView
    private var claves = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TrackStore(File(filesDir, "memoria"))
        cabecera = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 32, 32, 16) }
        lista = ListView(this)
        val borrar = Button(this).apply {
            text = "Borrar toda la memoria"
            setOnClickListener {
                AlertDialog.Builder(this@ResumenActivity)
                    .setMessage("Se borran todas las trayectorias guardadas. No se puede deshacer.")
                    .setPositiveButton("Borrar") { _, _ -> store.deleteAll(); pinta() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            val k = claves.getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this).setMessage("¿Borrar este recorrido?")
                .setPositiveButton("Borrar") { _, _ -> store.delete(k); pinta() }
                .setNegativeButton("Cancelar", null).show()
            true
        }
        val olvidar = Button(this).apply {
            text = "Olvidar lo aprendido de las escenas"
            setOnClickListener {
                AlertDialog.Builder(this@ResumenActivity)
                    .setMessage("Se olvida qué es normal en cada escena, la del móvil y las de las cámaras por red " +
                                "(rumbos, velocidades, clases por zona). " +
                                "Hazlo si has movido la cámara a otro sitio.")
                    .setPositiveButton("Olvidar") { _, _ ->
                        File(filesDir, "memoria/comportamiento.txt").delete()
                        File(filesDir, "memoria/escenas").deleteRecursively(); pinta()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18))
            addView(cabecera); addView(borrar); addView(olvidar)
            addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        pinta()
    }

    private fun pinta() {
        val hoy = LocalDate.now()
        val s = store.summary(hoy)
        val horas = (0 until 24).filter { s.perHour[it] > 0 }
            .joinToString("  ") { "%02dh %s".format(it, "▮".repeat(minOf(12, s.perHour[it]))) }
        val ev = if (s.events.isEmpty()) "ninguno" else s.events.entries.joinToString(" · ") { "${it.key} ${it.value}" }
        cabecera.text = "Hoy, $hoy\n" +
            "personas ${s.people} · vehículos ${s.vehicles} · bicis ${s.bikes} · movimiento sin clase ${s.unclassified}\n" +
            "eventos: $ev\n" +
            (if (horas.isNotEmpty()) "por hora: $horas\n" else "") +
            (if (s.longestStayS > 0) "estancia más larga: ${s.longestStayS.toInt()} s\n" else "") +
            normalidad() +
            "\nSolo se guardan recorridos, ninguna imagen. Lo de más de 30 días se borra solo. " +
            "Mantén pulsado un recorrido para borrarlo."
        val zona = ZoneId.systemDefault()
        val filas = store.rows(hoy).sortedByDescending { it.start }
        claves = filas.map { it.key }
        val textos = filas.map { r ->
            val h = Instant.ofEpochMilli((r.start * 1000).toLong()).atZone(zona).toLocalTime().withNano(0)
            val quien = if (r.fromDet) nombre(r.cls) else "movimiento"
            val v = if (r.maxSpeedMs.isFinite()) " · hasta %.1f m/s".format(java.util.Locale("es"), r.maxSpeedMs) else ""
            val e = if (r.events.isEmpty()) "" else " · " + r.events.joinToString(", ")
            "$h  $quien · ${(r.end - r.start).toInt()} s$v$e"
        }
        lista.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, textos) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
        }
    }

    private fun normalidad(): String {
        val f = File(filesDir, "memoria/comportamiento.txt")
        val m = f.takeIf { it.exists() }?.let { runCatching { eus.argos.core.BehaviourModel.deserialize(it.readText()) }.getOrNull() }
        val red = File(filesDir, "memoria/escenas").listFiles { x -> x.name.endsWith(".txt") }?.size ?: 0
        val otras = if (red == 0) "" else "cámaras por red con lo suyo aprendido: $red\n"
        if (m == null) return "lo normal de la escena: sin aprender todavía\n" + otras
        val mt = m.maturity()
        return "lo normal de la escena: ${mt.cellsMature} zonas aprendidas, ${mt.directional} con flujo propio" +
            (if (mt.ready) "" else " (aún aprendiendo)") + "\n" + otras
    }

    private fun nombre(k: String) = when (k) {
        "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici"; else -> k
    }
}
