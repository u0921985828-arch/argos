package com.eddie.surco

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.player.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Los mandos de «Sonando» pulsados con el dedo y comprobados en el reproductor: reproducir y
 * pausar, siguiente, anterior, repetir (apagado → todo → una → apagado) y aleatorio. Son los que
 * el recorrido de botones no puede ver (cambian un icono o un estado, no un texto).
 */
@RunWith(AndroidJUnit4::class)
class PlayerButtonsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val dev = UiDevice.getInstance(inst)
    private val thread = HandlerThread("mandos").apply { start() }
    private val handler = Handler(thread.looper)

    @org.junit.After fun stopThread() { thread.quitSafely() }

    private fun <T> onLooper(block: () -> T): T {
        var out: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post { out = runCatching(block); done.countDown() }
        assertTrue("el hilo del mando no responde", done.await(20, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(150) }
        throw AssertionError("no pasó: $what")
    }

    /** El mando de «Sonando» con ese nombre: el de la fila de «Repetir» (el minirreproductor repite nombres). */
    private fun control(name: String): UiObject2 {
        val repeat = dev.wait(Until.findObject(By.desc(ctx.getString(R.string.player_repeat))), 5_000)
            ?: throw AssertionError("no está «Sonando» abierta")
        val y = repeat.visibleCenter.y
        return dev.findObjects(By.desc(name)).minByOrNull { abs(it.visibleCenter.y - y) }
            ?: throw AssertionError("no encuentro «$name»")
    }

    @Test fun mandosDeSonando() {
        val tracks = LibraryScanner(ctx).loadCache()
        val list = listOf("Prueba Uno", "Prueba Dos", "Prueba Tres").map { t -> tracks.first { it.title == t } }
        val token = SessionToken(ctx, ComponentName(ctx.packageName, "com.eddie.surco.player.PlaybackService"))
        val c = onLooper { MediaController.Builder(ctx, token).setApplicationLooper(thread.looper).buildAsync() }.get(30, TimeUnit.SECONDS)
        try {
            onLooper {
                c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF
                c.setMediaItems(list.map { it.toMediaItem() }, 0, 0); c.prepare(); c.pause()
            }
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            // «Sonando» desde el minirreproductor (su título).
            val mini = dev.wait(Until.findObject(By.text("Prueba Uno")), 15_000) ?: throw AssertionError("no sale el minirreproductor")
            mini.click()
            assertTrue("no se abrió «Sonando»", dev.wait(Until.hasObject(By.text(ctx.getString(R.string.player_now_playing_header))), 5_000))

            control(ctx.getString(R.string.player_play_pause)).click()
            waitFor("empezar a sonar") { onLooper { c.isPlaying } }
            control(ctx.getString(R.string.player_play_pause)).click()
            waitFor("pausar") { !onLooper { c.isPlaying } }

            control(ctx.getString(R.string.player_next)).click()
            waitFor("pasar a la segunda") { onLooper { c.currentMediaItemIndex } == 1 }
            control(ctx.getString(R.string.player_previous)).click()
            waitFor("volver a la primera") { onLooper { c.currentMediaItemIndex } == 0 }

            val modes = listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF)
            for (m in modes) {
                control(ctx.getString(R.string.player_repeat)).click()
                waitFor("repetir → $m") { onLooper { c.repeatMode } == m }
            }
            control(ctx.getString(R.string.player_shuffle)).click()
            waitFor("aleatorio encendido") { onLooper { c.shuffleModeEnabled } }
            control(ctx.getString(R.string.player_shuffle)).click()
            waitFor("aleatorio apagado") { !onLooper { c.shuffleModeEnabled } }
            assertEquals(0, onLooper { c.currentMediaItemIndex })
            Log.i("SurcoAuto", "mandos de «Sonando»: reproducir, pausar, siguiente, anterior, repetir ×3 y aleatorio, bien")
        } finally {
            onLooper { c.pause(); c.release() }
            dev.pressBack(); dev.pressHome()
        }
    }

    private fun controller() = SessionToken(ctx, ComponentName(ctx.packageName, "com.eddie.surco.player.PlaybackService")).let { token ->
        onLooper { MediaController.Builder(ctx, token).setApplicationLooper(thread.looper).buildAsync() }.get(30, TimeUnit.SECONDS)
    }

    /** Artistas de la cola en el orden en que sonará con el aleatorio. */
    private fun shuffledArtists(c: MediaController): List<String> = onLooper {
        val tl = c.currentTimeline
        val out = ArrayList<String>()
        var i = tl.getFirstWindowIndex(true)
        while (i != androidx.media3.common.C.INDEX_UNSET && out.size < c.mediaItemCount) {
            out += c.getMediaItemAt(i).mediaMetadata.artist.toString()
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        out
    }

    /** El aleatorio no pone seguidas dos del mismo artista: al activarlo y al mandar una lista ya mezclada. */
    @Test fun aleatorioRepartido() {
        val uno = LibraryScanner(ctx).loadCache().first { it.title == "Prueba Uno" }
        fun item(artist: String) = uno.toMediaItem().let { m -> m.buildUpon().setMediaMetadata(m.mediaMetadata.buildUpon().setArtist(artist).build()).build() }
        val items = listOf("A", "A", "A", "B", "B", "C").map(::item)
        val c = controller()
        try {
            repeat(8) { round ->
                onLooper {
                    c.shuffleModeEnabled = false
                    c.setMediaItems(items, 0, 0); c.prepare(); c.pause()
                    c.shuffleModeEnabled = true
                }
                // El mando enseña primero un orden provisional (sin mezclar) hasta que llega el del
                // servicio: se espera al de verdad, que empieza por la que suena y no repite artista.
                var order = emptyList<String>()
                waitFor("aleatorio repartido al activarlo (ronda $round): $order") {
                    order = shuffledArtists(c)
                    onLooper { c.shuffleModeEnabled } && order.size == items.size && order.first() == "A" && order.zipWithNext().none { (a, b) -> a == b }
                }
                // Lista nueva con el aleatorio ya puesto (lo que hace «Mezclar» en la app).
                onLooper { c.setMediaItems(items.reversed(), true) }
                waitFor("lista nueva") { shuffledArtists(c).let { it.size == items.size && it.zipWithNext().none { (a, b) -> a == b } } }
            }
            Log.i("SurcoAuto", "aleatorio repartido: 8 rondas sin dos del mismo artista seguidos")
        } finally {
            onLooper { c.shuffleModeEnabled = false; c.pause(); c.release() }
        }
    }

    /** «Radio de la canción» desde el menú de «Sonando»: sigue la que suena, detrás lo demás, sin repetir. */
    @Test fun radioDesdeElMenu() {
        val tracks = LibraryScanner(ctx).loadCache()
        val uno = tracks.first { it.title == "Prueba Uno" }
        val c = controller()
        try {
            onLooper { c.shuffleModeEnabled = false; c.setMediaItems(listOf(uno.toMediaItem()), 0, 0); c.prepare(); c.pause() }
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val mini = dev.wait(Until.findObject(By.text("Prueba Uno")), 15_000) ?: throw AssertionError("no sale el minirreproductor")
            mini.click()
            assertTrue("no se abrió «Sonando»", dev.wait(Until.hasObject(By.text(ctx.getString(R.string.player_now_playing_header))), 5_000))
            // La forma de onda (Prueba Uno es un tono fijo: barras iguales) para mirarla en la captura.
            Thread.sleep(2_000)
            val dir = java.io.File(ctx.getExternalFilesDir(null), "feria").apply { mkdirs() }
            dev.takeScreenshot(java.io.File(dir, "sonando_onda.png"))
            (dev.wait(Until.findObject(By.desc(ctx.getString(R.string.player_more))), 5_000) ?: throw AssertionError("no está «Más»")).click()
            val label = ctx.getString(R.string.player_radio)
            val radio = dev.wait(Until.findObject(By.text(label)), 4_000) ?: run {
                // La hoja sale a medias: se sube para ver el resto del menú.
                dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 4, 25)
                dev.wait(Until.findObject(By.text(label)), 4_000)
            } ?: throw AssertionError("no sale «Radio de la canción» en el menú")
            radio.click()
            waitFor("la radio en la cola") { onLooper { c.mediaItemCount } >= 2 }
            val ids = onLooper { List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId } }
            assertEquals("empieza por la que sonaba", uno.id.toString(), ids.first())
            assertEquals("sin repetidas: $ids", ids.size, ids.toSet().size)
            assertEquals(0, onLooper { c.currentMediaItemIndex })
            Log.i("SurcoAuto", "radio de «Prueba Uno»: ${ids.size} canciones, empieza por ella y sin repetir")
        } finally {
            onLooper { c.pause(); c.release() }
            dev.pressBack(); dev.pressHome()
        }
    }
}
