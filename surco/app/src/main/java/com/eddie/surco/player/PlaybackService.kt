package com.eddie.surco.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.eddie.surco.MainActivity
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoAudioRenderer
import com.eddie.surco.audio.SurcoAudioSink
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.SavedQueue
import com.eddie.surco.data.UserStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

/**
 * Servicio de reproducción: ExoPlayer con el motor de sonido de Surco, ReplayGain,
 * fundidos al pausar, temporizador de apagado, conteo de escuchas y cola que se reanuda.
 */
/** Margen para poder subir canciones con ReplayGain positivo sin pasar del volumen máximo. */
const val RG_HEADROOM_DB = 6f

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    // Volumen = ReplayGain × fundido × temporizador. Se aplica con player.volume: actúa
    // en el instante, no con el retraso del búfer de audio.
    private var rgGain = 1f
    private var fadeMul = 1f
    private var sleepMul = 1f
    private var fadeTarget = 1f

    // Conteo de escuchas
    private var listenedId: String? = null
    private var listenedMs = 0L
    private var lastPos = 0L
    private var counted = false
    private var ticks = 0

    override fun onCreate() {
        super.onCreate()
        SurcoSettings.init(this)
        UserStore.init(this)

        // Solo audio: un único renderizador, con la salida sin pérdida de Surco.
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(SurcoAudioRenderer(this, handler, audioListener, SurcoAudioSink.create(this)))
        }
        exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyReplayGain()
                saveQueue()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && fadeMul < 1f && fadeTarget < 1f) fadeTo(1f)
                if (!isPlaying) saveQueue()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    SleepTimer.cancel()
                    exo.pauseAtEndOfMediaItems = false
                }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, FadingPlayer(exo))
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    // La URI no viaja por el controlador: se toma de requestMetadata o, si no, del
                    // mediaId, que es el _ID de MediaStore. Lo que no tenga ninguna de las dos se descarta.
                    mediaItems.mapNotNull { item ->
                        val uri = item.requestMetadata.mediaUri
                            ?: item.mediaId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                        uri?.let { item.buildUpon().setUri(it).build() }
                    }.toMutableList()
                )

                // Reanudar desde la notificación, el Bluetooth o el sistema tras cerrar la app.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val byId = LibraryScanner(this@PlaybackService).loadCache().associateBy { it.id }
                    val r = restoreQueue(UserStore.state.value.queue, byId)
                        ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
                    return Futures.immediateFuture(r)
                }
            })
            .build()

        // Ajustes que viven en el reproductor
        scope.launch {
            SurcoSettings.state.collect { p ->
                exo.playbackParameters = PlaybackParameters(p.speed, if (p.keepPitch) 1f else p.speed)
                exo.skipSilenceEnabled = p.skipSilence
                applyReplayGain()
            }
        }
        scope.launch {
            SleepTimer.endOfTrack.collect { exo.pauseAtEndOfMediaItems = it }
        }
        handler.post(tick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private fun applyVolume() { exo.volume = (rgGain * fadeMul * sleepMul).coerceIn(0f, 1f) }

    private fun applyReplayGain() {
        val mode = SurcoSettings.state.value.replayGain
        val ex = exo.currentMediaItem?.mediaMetadata?.extras
        val db = when (mode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> ex?.getFloat(EXTRA_RG_TRACK, Float.NaN)
            ReplayGainMode.ALBUM -> ex?.getFloat(EXTRA_RG_ALBUM, Float.NaN)?.takeUnless { it.isNaN() }
                ?: ex?.getFloat(EXTRA_RG_TRACK, Float.NaN)
        }?.takeUnless { it.isNaN() }
        // El volumen no puede pasar de 1: con ReplayGain todo baja RG_HEADROOM_DB para que las
        // canciones flojas (ganancia positiva) también se puedan subir. Sin etiqueta cuenta como 0 dB.
        rgGain = if (mode == ReplayGainMode.OFF) 1f else 10f.pow((min(db ?: 0f, RG_HEADROOM_DB) - RG_HEADROOM_DB) / 20f)
        applyVolume()
    }

    private var fadeRunnable: Runnable? = null

    /** Lleva el fundido a [target] en ~[ms] y luego ejecuta [then]. */
    private fun fadeTo(target: Float, ms: Long = 250, then: (() -> Unit)? = null) {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeTarget = target
        val steps = 10
        val start = fadeMul
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                fadeMul = start + (target - start) * i / steps
                applyVolume()
                if (i < steps) handler.postDelayed(this, ms / steps) else { fadeRunnable = null; then?.invoke() }
            }
        }
        fadeRunnable = r
        handler.post(r)
    }

    /** Las órdenes de reproducir/pausar de la notificación y de la app pasan por aquí: con fundido. */
    private inner class FadingPlayer(p: ExoPlayer) : ForwardingPlayer(p) {
        override fun pause() {
            if (SurcoSettings.state.value.fade && exo.isPlaying) fadeTo(0f) { exo.pause() } else exo.pause()
        }
        override fun play() {
            if (SurcoSettings.state.value.fade && !exo.isPlaying) {
                fadeRunnable?.let { handler.removeCallbacks(it) }
                fadeMul = 0f; fadeTarget = 0f; applyVolume()
                exo.play()
                fadeTo(1f)
            } else {
                if (fadeMul < 1f) fadeTo(1f)
                exo.play()
            }
        }
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 500)
        }
    }

    private fun onTick() {
        // Escuchas: cuenta al pasar la mitad (o 4 minutos), como Last.fm.
        val id = exo.currentMediaItem?.mediaId
        val pos = exo.currentPosition
        if (id != listenedId) { listenedId = id; listenedMs = 0; counted = false; lastPos = pos }
        if (exo.isPlaying) {
            val d = pos - lastPos
            if (d in 1..2_000) listenedMs += d
            val dur = exo.duration
            val need = if (dur > 0) min(dur / 2, 240_000L) else 30_000L
            if (!counted && id != null && listenedMs >= need) {
                counted = true
                id.toLongOrNull()?.let { UserStore.recordPlay(it) }
            }
            if (++ticks % 10 == 0) saveQueue()
        }
        lastPos = pos

        // Temporizador: baja el volumen los últimos 10 s y pausa.
        val end = SleepTimer.endAt.value
        if (end != null) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) {
                SleepTimer.cancel()
                exo.pause()
                sleepMul = 1f
            } else {
                sleepMul = if (left < 10_000) left / 10_000f else 1f
            }
            applyVolume()
        } else if (sleepMul != 1f) {
            sleepMul = 1f; applyVolume()
        }
    }

    private fun saveQueue() {
        val n = exo.mediaItemCount
        if (n == 0) return
        val ids = List(n) { exo.getMediaItemAt(it).mediaId.toLongOrNull() ?: -1L }
        UserStore.saveQueue(SavedQueue(ids, exo.currentMediaItemIndex, exo.currentPosition.coerceAtLeast(0)))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveQueue()
        UserStore.flush()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
