package com.eddie.surco

import android.content.ComponentName
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lo que hace Android Auto, desde fuera de la app: se conecta al servicio de Surco como un
 * navegador de Media3 (el mismo protocolo que el coche), recorre el árbol, busca y toca una
 * canción. Corre en el emulador del CI después de la prueba de humo (con la biblioteca ya leída).
 */
@RunWith(AndroidJUnit4::class)
class AutoTest {
    private val thread = HandlerThread("navegador").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var browser: MediaBrowser

    /** Media3 exige usar el navegador desde el hilo con el que se creó. */
    private fun <T> onLooper(block: () -> T): T {
        var out: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post { out = runCatching(block); done.countDown() }
        assertTrue("el hilo del navegador no responde", done.await(20, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    private fun <T> ListenableFuture<LibraryResult<T>>.ok(): T {
        val r = get(20, TimeUnit.SECONDS)
        assertEquals("código de resultado", LibraryResult.RESULT_SUCCESS, r.resultCode)
        return r.value!!
    }

    @Before fun connect() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(ctx, ComponentName(ctx.packageName, "com.eddie.surco.player.PlaybackService"))
        val f = onLooper { MediaBrowser.Builder(ctx, token).setApplicationLooper(thread.looper).buildAsync() }
        browser = f.get(30, TimeUnit.SECONDS)
    }

    @After fun close() { onLooper { browser.pause(); browser.release() }; thread.quitSafely() }

    private fun children(id: String): List<MediaItem> = onLooper { browser.getChildren(id, 0, 200, null) }.ok()

    @Test fun navegaBuscaYToca() {
        val root = onLooper { browser.getLibraryRoot(null) }.ok()
        val tabs = children(root.mediaId)
        assertEquals(listOf("Para ti", "Listas", "Álbumes", "Artistas"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })

        val albums = children("albumes")
        assertTrue("álbumes: ${albums.map { it.mediaMetadata.title }}", albums.any { it.mediaMetadata.title.toString() == "Disco de Prueba" })
        val disco = albums.first { it.mediaMetadata.title.toString() == "Disco de Prueba" }
        val songs = children(disco.mediaId)
        assertEquals(2, songs.size)
        assertTrue(songs.all { it.mediaMetadata.isPlayable == true })

        val home = children("inicio")
        assertTrue("Para ti: ${home.map { it.mediaMetadata.title }}", home.first().mediaMetadata.title.toString() == "Mezclar todo")

        // Buscar en la pantalla del coche.
        onLooper { browser.search("prueba dos", null) }.get(20, TimeUnit.SECONDS)
        val hits = onLooper { browser.getSearchResult("prueba dos", 0, 50, null) }.ok()
        assertTrue("búsqueda: ${hits.map { it.mediaMetadata.title }}", hits.firstOrNull()?.mediaMetadata?.title.toString() == "Prueba Dos")

        // Tocar la segunda canción del disco: suena el disco entero, desde ella.
        val second = songs[1]
        onLooper { browser.setMediaItem(second); browser.prepare(); browser.play() }
        val deadline = System.currentTimeMillis() + 20_000
        var state = ""
        while (System.currentTimeMillis() < deadline) {
            state = onLooper { "${browser.mediaItemCount}|${browser.currentMediaItemIndex}|${browser.currentMediaItem?.mediaMetadata?.title}|${browser.playbackState}" }
            if (state.startsWith("2|1|${second.mediaMetadata.title}|${Player.STATE_READY}")) break
            Thread.sleep(300)
        }
        assertEquals("2|1|${second.mediaMetadata.title}|${Player.STATE_READY}", state)
        println("Android Auto (navegador de Media3): pestañas ${tabs.size}, álbumes ${albums.size}, búsqueda ${hits.size}, suena «${second.mediaMetadata.title}» en su disco")
    }
}
