package com.eddie.surco

import android.content.ComponentName
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.player.toMediaItem
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * El cruce entre canciones con solape de verdad: con 3 s de cruce, al final de «Prueba Uno» suenan
 * a la vez su cola y el principio de «Prueba Dos» (dos reproductores activos en el sistema de
 * audio a la vez), y después queda solo la segunda.
 */
@RunWith(AndroidJUnit4::class)
class CrossfadeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val thread = HandlerThread("cruce").apply { start() }
    private val handler = Handler(thread.looper)

    private fun <T> onLooper(block: () -> T): T {
        var out: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post { out = runCatching(block); done.countDown() }
        assertTrue("el hilo del mando no responde", done.await(20, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    @Test fun lasDosSuenanALaVez() {
        SurcoSettings.init(ctx)
        val before = SurcoSettings.state.value
        SurcoSettings.update { it.copy(crossfadeSec = 3, crossfadeKeepAlbums = false) }
        val am = ctx.getSystemService(AudioManager::class.java)
        fun players() = am.activePlaybackConfigurations.size
        val tracks = LibraryScanner(ctx).loadCache()
        val uno = tracks.first { it.title == "Prueba Uno" }
        val dos = tracks.first { it.title == "Prueba Dos" }
        val token = SessionToken(ctx, ComponentName(ctx.packageName, "com.eddie.surco.player.PlaybackService"))
        val c = onLooper { MediaController.Builder(ctx, token).setApplicationLooper(thread.looper).buildAsync() }.get(30, TimeUnit.SECONDS)
        try {
            onLooper {
                c.shuffleModeEnabled = false
                c.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                c.setMediaItems(listOf(uno.toMediaItem(), dos.toMediaItem()), 0, 12_000)
                c.prepare(); c.play()
            }
            // La base, nada más empezar (la cola del cruce se prepara unos segundos antes de cruzar).
            Thread.sleep(500)
            val base = players()
            var most = base
            var second = false
            val end = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < end) {
                most = maxOf(most, players())
                if (onLooper { c.currentMediaItemIndex } == 1) second = true
                if (second && most > base) break
                Thread.sleep(100)
            }
            Log.i("SurcoAuto", "cruce: reproductores activos $base → como mucho $most; suena la segunda: $second")
            assertTrue("no pasó a «Prueba Dos»", second)
            assertTrue("no sonaron las dos a la vez (reproductores activos: $base → $most)", most > base)
            // Al acabar el cruce queda solo la que entra.
            Thread.sleep(3_500)
            assertTrue("la cola de la primera no se apagó (${players()} activos, antes $base)", players() <= base)
        } finally {
            onLooper { c.pause(); c.release() }
            SurcoSettings.update { it.copy(crossfadeSec = before.crossfadeSec, crossfadeKeepAlbums = before.crossfadeKeepAlbums) }
            thread.quitSafely()
        }
    }
}
