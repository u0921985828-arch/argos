package com.eddie.surco

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.eddie.surco.audio.SurcoSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * La cuadrícula de álbumes con dos dedos de verdad: cerrar los dedos pone más portadas por fila
 * (2, 3, 5 y 8, como Fibonacci) y abrirlos, menos (hasta 2). Deja una captura de cada extremo para revisarla (rama
 * `capturas-surco`). Con la app en español (auto-surco.sh) y la biblioteca de la prueba de humo.
 */
@RunWith(AndroidJUnit4::class)
class GridZoomTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val dev = UiDevice.getInstance(inst)

    private fun cols() = SurcoSettings.state.value.albumColumns

    private fun waitCols(what: String, ok: (Int) -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { if (ok(cols())) return; Thread.sleep(200) }
        throw AssertionError("la cuadrícula no llegó a $what (está en ${cols()} por fila)")
    }

    /**
     * Pellizca la tarjeta del álbum de prueba. Se busca cada vez y se reintenta: con cada pellizco
     * la cuadrícula se recompone y el nodo encontrado puede quedar viejo antes de usarlo.
     */
    private fun pinch(close: Boolean) {
        repeat(5) { n ->
            dev.waitForIdle()
            try {
                val o = dev.findObject(By.text("Disco de Prueba")) ?: dev.findObject(By.desc("Disco de Prueba"))
                if (o != null) {
                    val card = o.parent ?: o
                    if (close) card.pinchClose(0.8f) else card.pinchOpen(0.8f)
                    return
                }
            } catch (e: StaleObjectException) {
                if (n == 4) throw e
            }
            Thread.sleep(300)
        }
        throw AssertionError("no se encuentra el álbum de prueba para pellizcarlo")
    }

    private fun shot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "rejilla").apply { mkdirs() }
        dev.waitForIdle()
        Thread.sleep(600)
        dev.takeScreenshot(File(dir, name))
    }

    @Test fun pellizcarLaCuadricula() {
        SurcoSettings.init(ctx)
        SurcoSettings.update { it.copy(albumColumns = 2) }
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val library = ctx.getString(R.string.app_tab_library)
        val albums = ctx.getString(R.string.app_lib_albums)
        assertTrue("no se abrió Surco", dev.wait(Until.hasObject(By.text(library)), 15_000))
        dev.findObject(By.text(library)).click()
        assertTrue("no está la pestaña $albums", dev.wait(Until.hasObject(By.text(albums)), 5_000))
        dev.findObject(By.text(albums)).click()
        assertTrue("no sale el álbum de prueba", dev.wait(Until.hasObject(By.text("Disco de Prueba")), 5_000))
        shot("rejilla_2.png")

        // Cerrar los dedos: más portadas por fila, hasta 8 (y no más). Sobre la portada del álbum
        // (con la biblioteca de la prueba la cuadrícula no se desplaza: no es «scrollable»).
        pinch(close = true)
        waitCols("más de 2") { it > 2 }
        repeat(3) { pinch(close = true) }
        repeat(2) { pinch(close = true) }
        waitCols("8") { it == 8 }
        // A 8 por fila el nombre sigue debajo de la portada (un disco sin portada no se sabría cuál es).
        assertTrue("a 8 por fila no se ve el nombre del álbum", dev.wait(Until.hasObject(By.text("Disco de Prueba")), 3_000))
        shot("rejilla_8.png")

        // Abrirlos: menos portadas, hasta 2 (y no menos).
        pinch(close = false)
        waitCols("menos de 8") { it < 8 }
        repeat(4) { pinch(close = false) }
        waitCols("2") { it == 2 }
        dev.pressHome()
    }
}
