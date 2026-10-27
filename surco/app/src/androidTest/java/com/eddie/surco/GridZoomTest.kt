package com.eddie.surco

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.eddie.surco.audio.SurcoSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * La cuadrícula de álbumes con dos dedos de verdad: cerrar los dedos pone más portadas por fila
 * (hasta 5) y abrirlos, menos (hasta 2). Deja una captura de cada extremo para revisarla (rama
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

    /** La tarjeta del álbum de prueba (se busca cada vez: cambia de tamaño con cada pellizco). */
    private fun album() = dev.findObject(By.text("Disco de Prueba")).let { it.parent ?: it }.also { dev.waitForIdle() }

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

        // Cerrar los dedos: más portadas por fila, hasta 5 (y no más). Sobre la portada del álbum
        // (con la biblioteca de la prueba la cuadrícula no se desplaza: no es «scrollable»).
        album().pinchClose(0.8f)
        waitCols("más de 2") { it > 2 }
        repeat(3) { album().pinchClose(0.8f) }
        waitCols("5") { it == 5 }
        assertTrue("el álbum desapareció a 5 por fila", dev.wait(Until.hasObject(By.text("Disco de Prueba")), 3_000))
        shot("rejilla_5.png")

        // Abrirlos: menos portadas, hasta 2 (y no menos).
        album().pinchOpen(0.8f)
        waitCols("menos de 5") { it < 5 }
        repeat(3) { album().pinchOpen(0.8f) }
        waitCols("2") { it == 2 }
        dev.pressHome()
    }
}
