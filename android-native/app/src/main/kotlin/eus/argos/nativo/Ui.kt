package eus.argos.nativo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lo poco de estilo que hace falta, sin librerías: colores, esquinas y unas
 * piezas que se repiten (fichas, tarjetas, cifras).
 */
object Ui {
    val FONDO = Color.argb(215, 12, 14, 18)
    val TARJETA = Color.argb(200, 18, 21, 27)
    val BORDE = Color.argb(60, 255, 255, 255)
    val TEXTO = Color.rgb(236, 239, 244)
    val SUAVE = Color.rgb(150, 158, 170)
    val VERDE = Color.rgb(0, 230, 118)
    val AMBAR = Color.rgb(255, 183, 77)
    val ROJO = Color.rgb(255, 82, 82)
    val AZUL = Color.rgb(100, 181, 246)

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun fondo(color: Int, radio: Float, borde: Int? = null, ctx: Context? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radio
        if (borde != null && ctx != null) setStroke(dp(ctx, 1), borde)
    }

    /** Ficha pulsable de la barra de arriba. */
    fun ficha(ctx: Context, texto: String, alPulsar: () -> Unit) = TextView(ctx).apply {
        text = texto; setTextColor(TEXTO); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
        background = fondo(Color.argb(150, 40, 44, 52), dp(ctx, 16).toFloat(), BORDE, ctx)
        isClickable = true; setOnClickListener { alPulsar() }
    }

    /** Una cifra grande con su rótulo debajo. Devuelve la vista y el TextView de la cifra. */
    fun cifra(ctx: Context, rotulo: String, color: Int = TEXTO): Pair<LinearLayout, TextView> {
        val n = TextView(ctx).apply { text = "–"; setTextColor(color); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        val r = TextView(ctx).apply { text = rotulo; setTextColor(SUAVE); textSize = 11f; gravity = Gravity.CENTER }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(n); addView(r)
        }
        return v to n
    }

    fun linea(ctx: Context, tam: Float = 13f, color: Int = TEXTO) = TextView(ctx).apply {
        setTextColor(color); textSize = tam; setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    /** Título arriba y valor debajo, en dos tamaños. */
    fun textoFila(titulo: String, valor: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(titulo).append("\n").append(valor)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, titulo.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(SUAVE), titulo.length + 1, sb.length, 0)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), titulo.length + 1, sb.length, 0)
        return sb
    }

    /** Fila de ajustes: toda la fila se pulsa, con altura de dedo (56 dp). */
    fun fila(ctx: Context, titulo: String, valor: String, alPulsar: () -> Unit) = TextView(ctx).apply {
        text = textoFila(titulo, valor); setTextColor(TEXTO); textSize = 16f
        minHeight = dp(ctx, 56); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        background = fondo(Color.argb(110, 40, 44, 52), dp(ctx, 14).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8) }
        isClickable = true; setOnClickListener { alPulsar() }
    }

    /** Botón redondo con icono. */
    fun boton(ctx: Context, icono: Int, descripcion: String, tamDp: Int, color: Int, alPulsar: () -> Unit) =
        android.widget.ImageButton(ctx).apply {
            setImageResource(icono); contentDescription = descripcion
            background = fondo(color, dp(ctx, tamDp / 2).toFloat())
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ctx, tamDp), dp(ctx, tamDp))
            setOnClickListener { alPulsar() }
        }

    /** Acción de la botonera: icono grande (el principal, más aún) y rótulo debajo. */
    fun accion(ctx: Context, icono: Int, rotulo: String, principal: Boolean, alPulsar: () -> Unit): LinearLayout {
        val tam = if (principal) 64 else 52
        val color = if (principal) Color.argb(240, 0, 168, 90) else Color.argb(170, 34, 38, 46)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(boton(ctx, icono, rotulo, tam, color, alPulsar).apply { elevation = dp(ctx, 4).toFloat() })
            addView(TextView(ctx).apply {
                text = rotulo; setTextColor(TEXTO); textSize = 12f; gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, 4) })
        }
    }
}
