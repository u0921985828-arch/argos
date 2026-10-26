package com.eddie.surco.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.eddie.surco.data.AlbumRef
import com.eddie.surco.data.crossfadeGain
import com.eddie.surco.data.currentFirstOrder
import com.eddie.surco.data.fadesBetween
import com.eddie.surco.data.insertIntoOrder
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.eddie.surco.audio.SurcoExtractorsFactory
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
    // Fundido entre canciones: baja al final y sube al empezar la siguiente.
    private var xfadeMul = 1f
    private var xfadeIn = false

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
            // WAV en coma flotante con cabecera extensible: Media3 los leería como enteros (ruido).
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, SurcoExtractorsFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Sube al empezar solo si llega sola (no al saltar a mano) y si la anterior bajó.
                xfadeIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastFadedOut
                applyReplayGain()
                saveQueue()
                listenedId = null // también con «Repetir una»: es otra escucha
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && fadeMul < 1f && fadeTarget < 1f && fadeRunnable == null) fadeTo(1f)
                if (isPlaying) errorStreak = 0 else saveQueue()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Volver al principio de la misma canción también cuenta como otra escucha.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs < 1000) listenedId = null
            }
            override fun onPlayerError(error: PlaybackException) = skipBroken(error)
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
                // Se lee la caché fuera del hilo principal y se descarta lo que ya no existe.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    scope.launch {
                        val q = UserStore.state.value.queue
                        val r = withContext(Dispatchers.IO) {
                            val byId = LibraryScanner(this@PlaybackService).loadCache().filter { File(it.path).exists() }.associateBy { it.id }
                            restoreQueue(q, byId)
                        }
                        if (r == null) result.setException(UnsupportedOperationException()) else {
                            exo.shuffleModeEnabled = q.shuffle
                            exo.repeatMode = q.repeat
                            result.set(r)
                        }
                    }
                    return result
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
        handler.post(xfadeTick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private fun applyVolume() { exo.volume = (rgGain * fadeMul * sleepMul * xfadeMul).coerceIn(0f, 1f) }

    private fun albumOf(item: MediaItem?): AlbumRef? = item?.let {
        AlbumRef(it.mediaMetadata.albumTitle?.toString().orEmpty(), it.mediaMetadata.extras?.getString(EXTRA_DIR).orEmpty())
    }

    /** ¿Baja al final la que suena? Si hay siguiente y no es del mismo disco (cuando se respetan). */
    private var lastFadedOut = false
    private fun fadesOut(p: com.eddie.surco.audio.Prefs): Boolean {
        val next = exo.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return false
        return fadesBetween(albumOf(exo.currentMediaItem), albumOf(exo.getMediaItemAt(next)), p.crossfadeKeepAlbums)
    }

    private val xfadeTick = object : Runnable {
        override fun run() {
            val p = SurcoSettings.state.value
            val g = if (p.crossfadeSec > 0 && exo.isPlaying) {
                val out = fadesOut(p)
                lastFadedOut = out
                crossfadeGain(exo.currentPosition, exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L, p.crossfadeSec * 1000L, xfadeIn, out)
            } else if (p.crossfadeSec > 0) xfadeMul else 1f
            if (g != xfadeMul) { xfadeMul = g; applyVolume() }
            handler.postDelayed(this, 50)
        }
    }

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

    private var errorStreak = 0

    /**
     * Un archivo que no se puede abrir (borrado, tarjeta quitada, sin permiso, dañado) se quita de
     * la cola con un aviso y se sigue con la siguiente; sin esto, ExoPlayer se queda parado en el
     * error y el botón de reproducir lo repite. Como mucho 10 seguidas, para no dar vueltas.
     */
    private fun skipBroken(error: PlaybackException) {
        val title = exo.currentMediaItem?.mediaMetadata?.title ?: "la canción"
        // Solo los fallos del archivo (no se encuentra, no se puede leer o descodificar) quitan la
        // canción; uno de la salida de audio no es culpa suya: se para y se avisa.
        val fileProblem = error.errorCode in 2000..4999
        if (!fileProblem) {
            Toast.makeText(this, "Error de salida de audio (${error.errorCodeName}). Pulsa reproducir para reintentar.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "No se pudo reproducir «$title»: se quita de la cola.", Toast.LENGTH_SHORT).show()
        if (exo.mediaItemCount == 0) return
        exo.removeMediaItem(exo.currentMediaItemIndex)
        if (exo.mediaItemCount > 0 && ++errorStreak < 10) { exo.prepare(); exo.play() }
    }

    /** Índices de la cola en el orden en que suena con el aleatorio. */
    private fun shuffledOrder(): List<Int> {
        val tl = exo.currentTimeline
        val out = ArrayList<Int>()
        var i = tl.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && out.size < exo.mediaItemCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    /** Las órdenes de reproducir/pausar de la notificación y de la app pasan por aquí: con fundido. */
    private inner class FadingPlayer(p: ExoPlayer) : ForwardingPlayer(p) {
        override fun pause() {
            if (SurcoSettings.state.value.fade && exo.isPlaying) fadeTo(0f) { exo.pause() } else exo.pause()
        }

        // Al activar el aleatorio, la que suena va primero: si no, lo que cae delante no suena nunca.
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled && !exo.shuffleModeEnabled && exo.mediaItemCount > 1) {
                exo.setShuffleOrder(DefaultShuffleOrder(currentFirstOrder(exo.mediaItemCount, exo.currentMediaItemIndex, Random), Random.nextLong()))
            }
            exo.shuffleModeEnabled = shuffleModeEnabled
        }

        // «A la cola» llega sin índice (va al final también del orden aleatorio); «A continuación»,
        // con el índice de detrás de la que suena. Así no se confunden aunque la actual sea la última.
        override fun addMediaItems(mediaItems: MutableList<MediaItem>) = addOrdered(exo.mediaItemCount, mediaItems, playNext = false)

        override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) =
            addOrdered(index, mediaItems, playNext = index == exo.currentMediaItemIndex + 1)

        private fun addOrdered(index: Int, mediaItems: MutableList<MediaItem>, playNext: Boolean) {
            if (!exo.shuffleModeEnabled || exo.mediaItemCount == 0) { exo.addMediaItems(index, mediaItems); return }
            val order = shuffledOrder()
            val cur = exo.currentMediaItemIndex
            exo.addMediaItems(index, mediaItems)
            val next = insertIntoOrder(order, index, mediaItems.size, cur, playNext)
            exo.setShuffleOrder(DefaultShuffleOrder(next.toIntArray(), Random.nextLong()))
        }

        override fun play() {
            // Fundido solo al reanudar la misma canción; una canción nueva empieza con su ataque entero.
            val resuming = exo.playbackState == Player.STATE_READY && exo.currentPosition > 500
            if (SurcoSettings.state.value.fade && !exo.isPlaying && resuming) {
                fadeRunnable?.let { handler.removeCallbacks(it) }
                fadeMul = 0f; fadeTarget = 0f; applyVolume()
                exo.play()
                fadeTo(1f)
            } else {
                if (fadeMul < 1f) {
                    if (exo.isPlaying) fadeTo(1f) // pausa a medio fundido: vuelve
                    else { fadeRunnable?.let { handler.removeCallbacks(it) }; fadeMul = 1f; fadeTarget = 1f; applyVolume() }
                }
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
        UserStore.saveQueue(SavedQueue(ids, exo.currentMediaItemIndex, exo.currentPosition.coerceAtLeast(0), exo.shuffleModeEnabled, exo.repeatMode))
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
