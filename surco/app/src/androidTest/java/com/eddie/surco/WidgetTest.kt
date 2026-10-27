package com.eddie.surco

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddie.surco.player.SurcoWidget
import com.eddie.surco.player.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * El widget de verdad: un anfitrión (como el lanzador) pone el widget de Surco, se comprueba que
 * enseña la canción, se pulsa «reproducir» y el widget pasa a «pausar» cuando suena (el toque
 * va por el receptor de teclas multimedia hasta el servicio). Además, deja una imagen de cada
 * tamaño para revisarla (rama `capturas-surco`). Requiere `appwidget grantbind` (auto-surco.sh).
 */
@RunWith(AndroidJUnit4::class)
class WidgetTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    // Con los textos de la app (en el idioma que tenga), no con literales.
    private val play by lazy { ctx.getString(R.string.w_play) }
    private val pause by lazy { ctx.getString(R.string.w_pause) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> main(block: () -> T): T { var out: Any? = null; inst.runOnMainSync { out = block() }; return out as T }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) { if (main(check)) return; Thread.sleep(300) }
        throw AssertionError("el widget no llegó a: $what (play=«$play», pause=«$pause»)")
    }

    @Test fun enLaPantallaDeInicio() {
        val host = AppWidgetHost(ctx, 7070)
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = host.allocateAppWidgetId()
        try {
            assertTrue("sin permiso para poner widgets (appwidget grantbind)", mgr.bindAppWidgetIdIfAllowed(id, ComponentName(ctx, SurcoWidget::class.java)))
            val view: AppWidgetHostView = main {
                host.startListening()
                host.createView(ctx, id, mgr.getAppWidgetInfo(id)).also { v ->
                    // Medido como en un lanzador: 4×1 celdas.
                    val w = (320 * ctx.resources.displayMetrics.density).toInt(); val h = (72 * ctx.resources.displayMetrics.density).toInt()
                    v.layoutParams = FrameLayout.LayoutParams(w, h)
                    v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    v.layout(0, 0, w, h)
                }
            }
            // El sistema pide el contenido al proveedor (onUpdate): la última canción de la cola.
            SurcoWidget.refresh(ctx)
            // El diseño inicial ya trae «Surco» y el botón (sin acción): se espera a lo que solo pone
            // el proveedor, la descripción del botón según el estado.
            waitFor("enseñar una canción") {
                view.findViewById<View>(R.id.w_play_box)?.contentDescription?.toString() in setOf(play, pause) &&
                    view.findViewById<TextView>(R.id.w_title)?.text?.isNotBlank() == true
            }
            // Si ya sonaba (lo dejó así otra prueba), se pausa primero para empezar desde «Reproducir».
            if (main { view.findViewById<View>(R.id.w_play_box).contentDescription == pause }) {
                main { view.findViewById<View>(R.id.w_play_box).performClick() }
                waitFor("«Reproducir» de partida") { view.findViewById<View>(R.id.w_play_box)?.contentDescription == play }
            }
            val title = main { view.findViewById<TextView>(R.id.w_title).text.toString() }

            // Reproducir desde el widget → suena → el botón pasa a «Pausar».
            main { view.findViewById<View>(R.id.w_play_box).performClick() }
            waitFor("«Pausar» tras pulsar reproducir") { view.findViewById<View>(R.id.w_play_box)?.contentDescription == pause }
            main { view.findViewById<View>(R.id.w_play_box).performClick() }
            waitFor("«Reproducir» tras pulsar pausar") { view.findViewById<View>(R.id.w_play_box)?.contentDescription == play }
            Log.i("SurcoAuto", "widget: enseña «$title», reproducir y pausar desde el widget, bien")
        } finally {
            main { host.stopListening() }
            host.deleteAppWidgetId(id)
        }
    }

    /** Una imagen de cada tamaño con datos de ejemplo, para revisarla a ojo. */
    @Test fun capturas() {
        val dir = File(ctx.getExternalFilesDir(null), "widget").apply { mkdirs() }
        val s = WidgetState(null, "Bohemian Rhapsody", "Queen", playing = true)
        val d = ctx.resources.displayMetrics.density
        val sizes = mapOf(R.layout.widget_small to (150 to 64), R.layout.widget_wide to (340 to 76), R.layout.widget_big to (340 to 150))
        for ((layout, wh) in sizes) {
            val name = ctx.resources.getResourceEntryName(layout)
            main {
                val parent = FrameLayout(ctx)
                val v = SurcoWidget.views(ctx, layout, s, null, 0xFFE08A4B.toInt(), fav = true).apply(ctx, parent)
                val w = (wh.first * d).toInt(); val h = (wh.second * d).toInt()
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w + 48, h + 48, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.drawColor(0xFF3A4A5C.toInt()) // fondo de pantalla de inicio cualquiera
                c.translate(24f, 24f); v.draw(c)
                File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue(File(dir, "$name.png").length() > 0)
        }
        assertEquals(3, dir.listFiles()!!.count { it.name.startsWith("widget_") && it.name.endsWith(".png") })
    }
}
