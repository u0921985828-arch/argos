package com.eddie.surco

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.eddie.surco.audio.SurcoSettings
import org.junit.Assert.assertNotNull
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

    private fun shot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "widget").apply { mkdirs() }
        dev.waitForIdle()
        Thread.sleep(600)
        dev.takeScreenshot(File(dir, name))
    }

    @Test fun pellizcarLaCuadricula() {
        SurcoSettings.init(ctx)
        SurcoSettings.update { it.copy(albumColumns = 2) }
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("no se abrió Surco", dev.wait(Until.hasObject(By.text("Biblioteca")), 15_000))
        dev.findObject(By.text("Biblioteca")).click()
        assertTrue("no está la pestaña Álbumes", dev.wait(Until.hasObject(By.text("Álbumes")), 5_000))
        dev.findObject(By.text("Álbumes")).click()
        assertTrue("no sale el álbum de prueba", dev.wait(Until.hasObject(By.text("Disco de Prueba")), 5_000))
        val grid = dev.findObject(By.scrollable(true))
        assertNotNull("no encuentro la cuadrícula", grid)
        shot("rejilla_2.png")

        // Cerrar los dedos: más portadas por fila, hasta 5 (y no más).
        grid.pinchClose(0.8f)
        waitCols("más de 2") { it > 2 }
        repeat(3) { dev.findObject(By.scrollable(true)).pinchClose(0.8f) }
        waitCols("5") { it == 5 }
        assertTrue("el álbum desapareció a 5 por fila", dev.wait(Until.hasObject(By.text("Disco de Prueba")), 3_000))
        shot("rejilla_5.png")

        // Abrirlos: menos portadas, hasta 2 (y no menos).
        dev.findObject(By.scrollable(true)).pinchOpen(0.8f)
        waitCols("menos de 5") { it < 5 }
        repeat(3) { dev.findObject(By.scrollable(true)).pinchOpen(0.8f) }
        waitCols("2") { it == 2 }
        dev.pressHome()
    }
}
