package eus.argos.nativo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import eus.argos.core.SceneBrain

/**
 * Lo que se dibuja encima de la vista previa: cajas, quién es cada una, qué
 * está haciendo y por dónde ha pasado.
 *
 * La etiqueta solo dice una clase cuando la dio el DETECTOR. Lo que sale de la
 * resta de fondo dice «movimiento»: su clase sería la proporción de una
 * mancha, y llamarla «coche» es la alucinación que se vio en campo.
 *
 * La estatura sale con su intervalo y marcada «≈»: la escala se ancla en una
 * estatura media asumida, y ese error es sistemático. No es una medida.
 */
class Overlay(ctx: Context) : View(ctx) {
    @Volatile private var frame: Pipeline.Frame? = null

    // Estelas en coordenadas de ESCENA (imagen + desplazamiento de la cámara):
    // si el móvil se mueve, la estela se queda pegada al suelo, no a la pantalla.
    private val trails = HashMap<Int, ArrayDeque<FloatArray>>()

    private val det = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(0, 230, 118); isAntiAlias = true
    }
    private val stopped = Paint(det).apply { color = Color.rgb(255, 171, 64) }
    private val moving = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(170, 255, 196, 0)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f); isAntiAlias = true
    }
    private val trail = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint().apply {
        color = Color.WHITE; textSize = 32f; isAntiAlias = true; setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val small = Paint(text).apply { textSize = 26f; color = Color.rgb(220, 220, 220) }

    fun show(f: Pipeline.Frame) {
        // Las estelas se actualizan aquí, en el hilo del análisis, y se copian
        // al dibujar: el hilo de la interfaz nunca ve un mapa a medias.
        synchronized(trails) {
            val vivos = HashSet<Int>()
            for (b in f.boxes) {
                vivos.add(b.id)
                val q = trails.getOrPut(b.id) { ArrayDeque() }
                q.addLast(floatArrayOf(((b.x1 + b.x2) / 2 + f.camX).toFloat(), (b.y2 + f.camY).toFloat()))
                if (q.size > 45) q.removeFirst()
            }
            trails.keys.retainAll(vivos)
        }
        frame = f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        val s = minOf(width.toFloat() / f.w, height.toFloat() / f.h)
        val ox = (width - f.w * s) / 2
        val oy = (height - f.h * s) / 2
        val cx = f.camX.toFloat(); val cy = f.camY.toFloat()

        val copia = synchronized(trails) { trails.mapValues { it.value.toList() } }
        for ((id, pts) in copia) {
            if (pts.size < 2) continue
            val fromDet = f.info[id]?.fromDet == true
            for (i in 1 until pts.size) {
                val a = (255 * i / pts.size)
                trail.color = if (fromDet) Color.argb(a, 0, 230, 118) else Color.argb(a / 2, 255, 196, 0)
                canvas.drawLine(ox + (pts[i - 1][0] - cx) * s, oy + (pts[i - 1][1] - cy) * s,
                                ox + (pts[i][0] - cx) * s, oy + (pts[i][1] - cy) * s, trail)
            }
        }

        for (b in f.boxes) {
            val inf = f.info[b.id]
            val fromDet = b.klassSrc == "det"
            val x1 = ox + b.x1.toFloat() * s; val y1 = oy + b.y1.toFloat() * s
            val x2 = ox + b.x2.toFloat() * s; val y2 = oy + b.y2.toFloat() * s
            canvas.drawRect(x1, y1, x2, y2,
                if (!fromDet) moving else if (inf?.stopped == true) stopped else det)
            canvas.drawText(if (fromDet) "${nombre(b.klass)} #${b.id}" else "movimiento", x1 + 6, y1 - 12, text)
            if (inf != null && fromDet) {
                val linea = StringBuilder()
                if (inf.speedMs.isFinite()) linea.append("%.1f m/s".format(java.util.Locale("es"), inf.speedMs))
                else if (inf.stopped) linea.append("quieto")
                if (inf.dwellS >= 1) { if (linea.isNotEmpty()) linea.append(" · "); linea.append("${inf.dwellS.toInt()} s") }
                inf.stature?.let {
                    if (linea.isNotEmpty()) linea.append(" · ")
                    linea.append("≈%.2f m (%.2f–%.2f)".format(java.util.Locale("es"), it.m, it.lo, it.hi))
                }
                if (linea.isNotEmpty()) canvas.drawText(linea.toString(), x1 + 6, y2 + 30, small)
            }
        }
    }

    private fun nombre(k: String) = when (k) {
        "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici"
        else -> k
    }
}
