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
 *
 * Se dibuja a la frecuencia de la PANTALLA (60, 90 o 120 Hz), no a la del
 * análisis: entre dos análisis cada caja avanza con la velocidad que llevaba
 * (como mucho 120 ms hacia delante) y se acerca a su sitio con suavidad, en
 * vez de saltar cuando llega el siguiente. Lo que se ve es fluido aunque el
 * análisis vaya a 15 o 30 por segundo; el análisis no cambia.
 */
class Overlay(ctx: Context) : View(ctx) {
    @Volatile private var frame: Pipeline.Frame? = null

    // Estelas en coordenadas de ESCENA (imagen + desplazamiento de la cámara):
    // si el móvil se mueve, la estela se queda pegada al suelo, no a la pantalla.
    private val trails = HashMap<Int, ArrayDeque<FloatArray>>()

    /** Caja que se ve (x1..y2), adonde va, y a qué velocidad (px de imagen por ms). */
    private class Anim(var x1: Float, var y1: Float, var x2: Float, var y2: Float,
                       var tx1: Float, var ty1: Float, var tx2: Float, var ty2: Float,
                       var vx: Float = 0f, var vy: Float = 0f, var tMs: Long = 0L)
    private val anim = HashMap<Int, Anim>()

    /** Zonas de la cámara y la que se está dibujando (puntos normalizados 0-1). */
    @Volatile var zonas: List<eus.argos.core.Zona> = emptyList()
    @Volatile var dibujando: List<DoubleArray> = emptyList()
    @Volatile var dibujandoVigilar = true
    private val zonaVig = Paint().apply { style = Paint.Style.FILL; color = Color.argb(55, 255, 60, 60) }
    private val zonaVigBorde = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.argb(220, 255, 80, 80); isAntiAlias = true }
    private val zonaIgn = Paint().apply { style = Paint.Style.FILL; color = Color.argb(70, 120, 120, 130) }
    private val zonaIgnBorde = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(200, 180, 180, 190); isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f) }
    private val punto = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE; isAntiAlias = true }

    /** Del toque en pantalla a la imagen (0-1), con el mismo encaje que el dibujo. Null fuera de la imagen. */
    fun aImagen(px: Float, py: Float): DoubleArray? {
        val f = frame ?: return null
        val s = minOf(width.toFloat() / f.w, height.toFloat() / f.h)
        val ox = (width - f.w * s) / 2; val oy = (height - f.h * s) / 2
        val x = (px - ox) / (f.w * s); val y = (py - oy) / (f.h * s)
        return if (x in 0f..1f && y in 0f..1f) doubleArrayOf(x.toDouble(), y.toDouble()) else null
    }

    private fun pintaZona(canvas: Canvas, pts: List<DoubleArray>, vigilar: Boolean, nombre: String?, cerrada: Boolean,
                          ox: Float, oy: Float, ancho: Float, alto: Float) {
        if (pts.isEmpty()) return
        val path = android.graphics.Path()
        pts.forEachIndexed { i, p -> val x = ox + p[0].toFloat() * ancho; val y = oy + p[1].toFloat() * alto; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        if (cerrada) { path.close(); canvas.drawPath(path, if (vigilar) zonaVig else zonaIgn) }
        canvas.drawPath(path, if (vigilar) zonaVigBorde else zonaIgnBorde)
        if (!cerrada) for (p in pts) canvas.drawCircle(ox + p[0].toFloat() * ancho, oy + p[1].toFloat() * alto, 9f, punto)
        if (nombre != null) canvas.drawText(nombre, ox + pts[0][0].toFloat() * ancho + 8, oy + pts[0][1].toFloat() * alto + 30, small)
    }
    private var ultimoDibujo = 0L

    private val det = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(0, 230, 118); isAntiAlias = true
    }
    private val stopped = Paint(det).apply { color = Color.rgb(255, 171, 64) }
    // Fuera de lo normal de la escena: rojo si es claro (≥ 0,5), naranja si es
    // inusual (≥ 0,2), los mismos cortes que el panel de la web.
    private val unusual = Paint(det).apply { color = Color.rgb(255, 120, 0); strokeWidth = 7f }
    private val alert = Paint(det).apply { color = Color.rgb(255, 40, 40); strokeWidth = 9f }
    private val reason = Paint().apply {
        color = Color.rgb(255, 200, 190); textSize = 26f; isAntiAlias = true; setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
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
            val ahora = android.os.SystemClock.uptimeMillis()
            for (b in f.boxes) {
                val x1 = b.x1.toFloat(); val y1 = b.y1.toFloat(); val x2 = b.x2.toFloat(); val y2 = b.y2.toFloat()
                val a = anim[b.id]
                if (a == null) { anim[b.id] = Anim(x1, y1, x2, y2, x1, y1, x2, y2, tMs = ahora); continue }
                val dt = (ahora - a.tMs).coerceIn(1L, 500L).toFloat()
                // Velocidad del centro, suavizada: un análisis con ruido no la tuerce.
                val vx = ((x1 + x2) - (a.tx1 + a.tx2)) / 2 / dt; val vy = ((y1 + y2) - (a.ty1 + a.ty2)) / 2 / dt
                a.vx = 0.6f * a.vx + 0.4f * vx; a.vy = 0.6f * a.vy + 0.4f * vy
                a.tx1 = x1; a.ty1 = y1; a.tx2 = x2; a.ty2 = y2; a.tMs = ahora
            }
            anim.keys.retainAll(vivos)
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
        for (z in zonas) pintaZona(canvas, z.puntos, z.vigilar, (if (z.vigilar) "⚠ " else "") + z.nombre, true, ox, oy, f.w * s, f.h * s)
        pintaZona(canvas, dibujando, dibujandoVigilar, null, false, ox, oy, f.w * s, f.h * s)

        // Dónde va cada caja en ESTE instante de pantalla.
        val ahora = android.os.SystemClock.uptimeMillis()
        val paso = (ahora - ultimoDibujo).coerceIn(1L, 100L).toFloat(); ultimoDibujo = ahora
        val k = 1f - Math.exp(-paso / 35.0).toFloat()
        val pos = HashMap<Int, FloatArray>()
        val copia = synchronized(trails) {
            for ((id, a) in anim) {
                val adelante = (ahora - a.tMs).coerceIn(0L, 120L).toFloat()
                val dx = a.vx * adelante; val dy = a.vy * adelante
                a.x1 += (a.tx1 + dx - a.x1) * k; a.y1 += (a.ty1 + dy - a.y1) * k
                a.x2 += (a.tx2 + dx - a.x2) * k; a.y2 += (a.ty2 + dy - a.y2) * k
                pos[id] = floatArrayOf(a.x1, a.y1, a.x2, a.y2)
            }
            trails.mapValues { it.value.toList() }
        }
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
            val p = pos[b.id] ?: floatArrayOf(b.x1.toFloat(), b.y1.toFloat(), b.x2.toFloat(), b.y2.toFloat())
            val x1 = ox + p[0] * s; val y1 = oy + p[1] * s
            val x2 = ox + p[2] * s; val y2 = oy + p[3] * s
            val an = inf?.anomaly?.score ?: 0.0
            canvas.drawRect(x1, y1, x2, y2, when {
                an >= 0.5 -> alert
                an >= 0.2 -> unusual
                !fromDet -> moving
                inf?.stopped == true -> stopped
                else -> det
            })
            if (an >= 0.2) inf?.anomaly?.reasons?.firstOrNull()?.let { canvas.drawText(it, x1 + 6, y2 + 58, reason) }
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

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        // Mientras haya cajas, se vuelve a dibujar en el siguiente refresco.
        if (frame?.boxes?.isNotEmpty() == true) postInvalidateOnAnimation()
    }

    private fun nombre(k: String) = when (k) {
        "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici"
        else -> k
    }
}
