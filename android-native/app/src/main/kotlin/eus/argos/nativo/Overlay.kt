package eus.argos.nativo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import eus.argos.core.Box

/**
 * Las cajas encima de la vista previa.
 *
 * La etiqueta solo dice una clase cuando la clase la dio el DETECTOR
 * (`klassSrc = "det"`). Lo que sale de la resta de fondo dice «movimiento»:
 * su clase es la proporción de la mancha, y llamarla «coche» es exactamente la
 * alucinación que se vio en campo sobre un andamio, una barandilla y una cara.
 */
class Overlay(ctx: Context) : View(ctx) {
    @Volatile private var frame: Pipeline.Frame? = null

    private val det = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(0, 230, 118); isAntiAlias = true
    }
    private val moving = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(200, 255, 196, 0)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f); isAntiAlias = true
    }
    private val text = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun show(f: Pipeline.Frame) {
        frame = f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        // La vista previa va en FIT_CENTER con la misma proporción que el
        // análisis: el mismo campo de visión, escalado y centrado.
        val s = minOf(width.toFloat() / f.w, height.toFloat() / f.h)
        val ox = (width - f.w * s) / 2
        val oy = (height - f.h * s) / 2
        for (b in f.boxes) {
            val fromDet = b.klassSrc == "det"
            val x1 = ox + b.x1.toFloat() * s; val y1 = oy + b.y1.toFloat() * s
            val x2 = ox + b.x2.toFloat() * s; val y2 = oy + b.y2.toFloat() * s
            canvas.drawRect(x1, y1, x2, y2, if (fromDet) det else moving)
            canvas.drawText(if (fromDet) nombre(b.klass) else "movimiento", x1 + 6, y1 - 10, text)
        }
    }

    private fun nombre(k: String) = when (k) {
        "person" -> "persona"; "vehicle" -> "vehículo"; "bike" -> "bici"
        else -> k
    }
}
