package com.eddie.surco

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.eddie.surco.audio.EQ_FREQS
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lo que gasta Surco: tiempo de procesador del proceso (app y servicio de reproducción, que van
 * juntos) por segundo de reloj, en pausa y sonando, con la pantalla apagada y encendida. El
 * emulador no tiene batería de verdad: el procesador es lo que se puede medir y comparar entre
 * versiones. Solo la lanza bateria-surco.sh (necesita los audios largos que deja allí).
 */
@RunWith(AndroidJUnit4::class)
class BatteryTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val dev = UiDevice.getInstance(inst)
    private val thread = HandlerThread("bateria").apply { start() }
    private val handler = Handler(thread.looper)

    private fun <T> onLooper(block: () -> T): T {
        var out: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post { out = runCatching(block); done.countDown() }
        assertTrue("el hilo del mando no responde", done.await(20, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    /** Porcentaje de un núcleo que usa el proceso durante [secs] segundos. */
    private fun measure(what: String, secs: Int): String {
        Thread.sleep(3_000) // que se asiente (arranque del decodificador, fundidos)
        val cpu0 = Process.getElapsedCpuTime(); val t0 = SystemClock.elapsedRealtime()
        Thread.sleep(secs * 1_000L)
        val cpu = Process.getElapsedCpuTime() - cpu0; val wall = SystemClock.elapsedRealtime() - t0
        val line = String.format(Locale.ROOT, "%s: %.1f %% de un núcleo (%d ms de procesador en %d s)", what, cpu * 100.0 / wall, cpu, wall / 1000)
        Log.i("SurcoBateria", line)
        return line
    }

    private fun item(file: File, title: String) = MediaItem.Builder()
        .setMediaId("bateria-" + file.name)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.fromFile(file)).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    @Test fun consumo() {
        SurcoSettings.init(ctx)
        val before = SurcoSettings.state.value
        val dir = File(ctx.getExternalFilesDir(null), "bateria")
        val mp3 = File(dir, "bateria.mp3"); val flac = File(dir, "bateria.flac")
        assertTrue("faltan los audios de la medida en $dir", mp3.exists() && flac.exists())
        // Sin nada que cambie la señal: lo que se mide es leer, decodificar y entregar.
        SurcoSettings.update {
            it.copy(dsp = it.dsp.copy(eqOn = false, bassDb = 0f, trebleDb = 0f, balance = 0f, mono = false, crossfeed = 0),
                replayGain = ReplayGainMode.OFF, speed = 1f, crossfadeSec = 0, skipSilence = false)
        }
        val token = SessionToken(ctx, ComponentName(ctx.packageName, "com.eddie.surco.player.PlaybackService"))
        val c = onLooper { MediaController.Builder(ctx, token).setApplicationLooper(thread.looper).buildAsync() }.get(30, TimeUnit.SECONDS)
        fun play(i: MediaItem) = onLooper {
            c.repeatMode = Player.REPEAT_MODE_ONE
            c.setMediaItem(i); c.prepare(); c.play()
        }
        try {
            onLooper { c.pause() }
            dev.sleep()
            measure("en pausa, pantalla apagada", 30)
            play(item(mp3, "Batería MP3"))
            measure("MP3 320 kbps sonando, pantalla apagada", 60)
            play(item(flac, "Batería FLAC"))
            measure("FLAC 24 bits/96 kHz sonando, pantalla apagada", 60)
            SurcoSettings.update { it.copy(dsp = it.dsp.copy(eqOn = true, bands = List(EQ_FREQS.size) { i -> if (i < 2 || i > EQ_FREQS.size - 3) 4f else 0f })) }
            measure("FLAC 24/96 con ecualizador, pantalla apagada", 60)
            SurcoSettings.update { it.copy(dsp = it.dsp.copy(eqOn = false)) }
            // Con la app delante: lo que añade la interfaz (Inicio, minirreproductor).
            dev.wakeUp()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            measure("FLAC sonando con Surco abierto y la pantalla encendida", 30)
            onLooper { c.pause() }
            dev.pressHome()
            measure("en pausa otra vez, Surco en segundo plano", 30)
        } finally {
            onLooper { c.stop(); c.clearMediaItems(); c.release() }
            SurcoSettings.update { before }
            thread.quitSafely()
        }
    }
}
