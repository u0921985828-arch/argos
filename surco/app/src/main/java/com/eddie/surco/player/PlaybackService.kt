package com.eddie.surco.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.eddie.surco.ui.Notice
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.eddie.surco.data.AlbumRef
import com.eddie.surco.data.crossfadeGain
import com.eddie.surco.data.overlapOutGain
import android.os.SystemClock
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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.eddie.surco.R
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.common.MediaMetadata
import android.os.Bundle
import com.google.common.collect.ImmutableList
import com.eddie.surco.data.BrowseIds
import com.eddie.surco.data.BrowseNode
import com.eddie.surco.MainActivity
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoAudioRenderer
import com.eddie.surco.audio.SurcoAudioSink
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.audio.OutputRoute
import com.eddie.surco.audio.SoundProfile
import com.eddie.surco.audio.outputKey
import com.eddie.surco.audio.routeName
import com.eddie.surco.audio.switchOutput
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.SavedQueue
import com.eddie.surco.data.UserStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.pow

/**
 * Servicio de reproducción: ExoPlayer con el motor de sonido de Surco, ReplayGain,
 * fundidos al pausar, temporizador de apagado, conteo de escuchas y cola que se reanuda.
 */
/** Margen para poder subir canciones con ReplayGain positivo sin pasar del volumen máximo. */
const val RG_HEADROOM_DB = 6f

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    /** «Enviar a»: cambia el reproductor de la sesión (este móvil, DLNA o Chromecast). */
    private var router: RemoteRouter? = null
    private val car by lazy { CarLibrary(this) }

    /** Lo hace [block] fuera del hilo principal y devuelve su futuro (el coche no puede esperar al disco). */
    private fun <T> later(block: () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        // Con el servicio ya cerrándose, la corrutina no llegaría a correr y el futuro no acabaría nunca.
        if (!scope.isActive) { f.setException(IllegalStateException("servicio cerrado")); return f }
        scope.launch { runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }
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
    /** ListenBrainz ya sabe lo que suena (se le dice una vez por canción). */
    private var lbNow = false
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
                xfadeIn = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || overlapping) && lastFadedOut
                // Elegir otra canción a mano corta la cola del cruce.
                if (!overlapping && reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) stopGhost()
                // Saltar a mano (o elegir otra en la cola) durante un fundido: la nueva entra entera.
                if (!xfadeIn && xfadeMul != 1f) { xfadeMul = 1f; applyVolume() }
                lastFadedOut = false
                applyReplayGain()
                saveQueue()
                listenedId = null // también con «Repetir una»: es otra escucha
            }
            // El widget de la pantalla de inicio sigue a la canción y al reproducir/pausar.
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) publishWidget()
                // Los botones propios de la notificación siguen a la canción y al aleatorio.
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) publishButtons()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) wantMixer()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && fadeMul < 1f && fadeTarget < 1f && fadeRunnable == null) fadeTo(1f)
                if (isPlaying) errorStreak = 0 else saveQueue()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Volver al principio de la misma canción también cuenta como otra escucha.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs < 1000) listenedId = null
                // Volver al principio a mano no es una entrada en fundido.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !overlapping) { xfadeIn = false; stopGhost() }
            }
            override fun onPlayerError(error: PlaybackException) = skipBroken(error)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Cualquier pausa (cascos fuera, llamada, temporizador) corta también la cola del cruce:
                // el segundo reproductor no tiene foco de audio ni se entera de los cascos.
                if (!playWhenReady) stopGhost()
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
        // Notificación de música: icono de Surco en la barra de estado y canal con nombre en español.
        // Canal propio: el de Media3 ya existía (2.4 y antes) con su nombre en inglés, y un canal
        // creado no se renombra; se borra el viejo.
        NotificationManagerCompat.from(this).deleteNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setChannelId("musica").setChannelName(R.string.canal_musica).build()
                .apply { setSmallIcon(R.drawable.ic_stat_surco) },
        )
        session = MediaLibrarySession.Builder(this, FadingPlayer(exo), object : MediaLibrarySession.Callback {
                // Favorita y aleatorio: dos botones propios en la notificación, la pantalla de bloqueo,
                // los mandos del sistema y el coche. Cualquier mando puede pedirlos.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(CMD_FAV).add(CMD_SHUFFLE).build(),
                        )
                        .build()

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_FAV.customAction -> {
                            val id = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                            UserStore.toggleFavorite(id) // el colector de favoritas rehace los botones
                        }
                        // Por el FadingPlayer: al activarlo, la que suena va primero.
                        CMD_SHUFFLE.customAction -> session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    // «Pon … en Surco» (asistente de voz): llega solo la frase, sin canción.
                    val req = mediaItems.singleOrNull()?.takeIf { it.requestMetadata.mediaUri == null && it.mediaId.isBlank() }?.requestMetadata
                    val voice = req?.searchQuery
                    if (req != null && voice != null) return later {
                        val b = car.get()
                        // El asistente a veces separa título, artista y álbum: si los da, mandan sobre la frase.
                        val ex = req.extras
                        val parts = listOfNotNull(ex?.getString(MediaStore.EXTRA_MEDIA_TITLE), ex?.getString(MediaStore.EXTRA_MEDIA_ARTIST), ex?.getString(MediaStore.EXTRA_MEDIA_ALBUM))
                            .filter { it.isNotBlank() }
                        val hits = when {
                            parts.isNotEmpty() -> b.tree.search(parts.joinToString(" ")).ifEmpty { b.tree.search(voice) }.map { it.id }
                            voice.isBlank() -> b.tree.playContext(BrowseIds.SHUFFLE_ALL)?.first.orEmpty().shuffled()
                            else -> b.tree.search(voice).map { it.id }
                        }
                        // Sin nada que case, error: una lista vacía pararía lo que suena y vaciaría la cola.
                        if (hits.isEmpty()) throw UnsupportedOperationException("nada casa con «$voice»")
                        handler.post { exo.shuffleModeEnabled = false }
                        hits.mapNotNull { b.byId[it]?.toMediaItem() }.toMutableList()
                    }
                    return Futures.immediateFuture(
                        // La URI no viaja por el controlador: se toma de requestMetadata o, si no, del
                        // mediaId, que es el _ID de MediaStore. Lo que no tenga ninguna de las dos se descarta.
                        mediaItems.mapNotNull { item ->
                            val uri = item.requestMetadata.mediaUri
                                ?: item.mediaId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                            uri?.let { item.buildUpon().setUri(it).build() }
                        }.toMutableList()
                    )
                }

                // Tocar una canción en el coche: suena su carpeta (álbum, lista, artista…) desde ella.
                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val id = mediaItems.singleOrNull()?.mediaId
                    if (id == null || (!id.contains("|t:") && id != BrowseIds.SHUFFLE_ALL)) {
                        return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                    }
                    return later {
                        val (list, at, shuffle) = car.get().resolve(id) ?: throw UnsupportedOperationException(id)
                        // Como en la app: un disco o una lista suenan en su orden (aunque el aleatorio
                        // estuviera puesto); «Mezclar todo» ya llega mezclado.
                        handler.post { exo.shuffleModeEnabled = false }
                        val items = (if (shuffle) list.shuffled() else list).map { it.toMediaItem() }
                        MediaSession.MediaItemsWithStartPosition(items, if (shuffle) 0 else at, C.TIME_UNSET)
                    }
                }

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val extras = Bundle().apply {
                        putInt(CarLibrary.CONTENT_STYLE_BROWSABLE, CarLibrary.STYLE_LIST)
                        putInt(CarLibrary.CONTENT_STYLE_PLAYABLE, CarLibrary.STYLE_LIST)
                    }
                    val root = MediaItem.Builder().setMediaId(BrowseIds.ROOT).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(getString(R.string.app_name)).setIsBrowsable(true).setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
                    ).build()
                    return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
                }

                override fun onGetChildren(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                    page: Int, pageSize: Int, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                    val b = car.get()
                    val nodes = b.tree.children(parentId) ?: return@later LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_BAD_VALUE)
                    val from = (page.toLong() * pageSize).coerceAtMost(nodes.size.toLong()).toInt()
                    val to = (from.toLong() + pageSize).coerceAtMost(nodes.size.toLong()).toInt()
                    LibraryResult.ofItemList(nodes.subList(from, to).map { car.toItem(it, b.byId) }, params)
                }

                override fun onGetItem(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
                ): ListenableFuture<LibraryResult<MediaItem>> = later {
                    val b = car.get()
                    b.tree.item(mediaId)?.let { LibraryResult.ofItem(car.toItem(it, b.byId), null) }
                        ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }

                override fun onSearch(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<Void>> {
                    // Se cuenta fuera del hilo principal y se avisa desde él (es el hilo de la sesión).
                    val f = SettableFuture.create<LibraryResult<Void>>()
                    scope.launch {
                        val n = runCatching { withContext(Dispatchers.IO) { car.get().tree.search(query).size } }.getOrDefault(0)
                        session.notifySearchResultChanged(browser, query, n, params)
                        f.set(LibraryResult.ofVoid())
                    }
                    return f
                }

                override fun onGetSearchResult(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                    page: Int, pageSize: Int, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                    val b = car.get()
                    val hits = b.tree.search(query)
                    val from = (page.toLong() * pageSize).coerceAtMost(hits.size.toLong()).toInt()
                    val to = (from.toLong() + pageSize).coerceAtMost(hits.size.toLong()).toInt()
                    LibraryResult.ofItemList(hits.subList(from, to).map { s ->
                        car.toItem(BrowseNode(BrowseIds.song(CarLibrary.SEARCH + query, s.id), s.title, s.artist, playable = true, songId = s.id), b.byId)
                    }, params)
                }

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
            .setSessionActivity(open)
            .build()
        // «Enviar a»: la misma sesión cambia de reproductor (este móvil, un aparato DLNA o un Chromecast).
        session?.let { router = RemoteRouter(this, it, it.player) }

        // Favorita marcada desde la app o «Sonando»: el corazón del widget grande la sigue.
        scope.launch {
            UserStore.state.map { it.favorites }.distinctUntilChanged().drop(1).collect {
                SurcoWidget.refresh(this@PlaybackService)
                publishButtons()
            }
        }
        publishButtons()

        // Por dónde sale el sonido y, con un DAC USB, su mezclador (bit-perfect o sin remuestrear).
        OutputRoute.start(this)
        // Ecualizador de cada salida: al conectar unos cascos se pone el suyo (y se guarda el de la anterior).
        scope.launch {
            OutputRoute.state.filterNotNull().map { it to outputKey(it.kind.name, it.name) }
                .distinctUntilChangedBy { it.second }.collect { (route, key) -> followOutput(route, key) }
        }
        // Ajustes que viven en el reproductor
        scope.launch {
            SurcoSettings.state.collect { p ->
                wantMixer()
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

    /** El que suena ahora: el local o, con «Enviar a», el del aparato. */
    private val active: Player get() = session?.player ?: exo

    /** Corazón y aleatorio según la canción y el modo de ahora. */
    private fun publishButtons() {
        val s = session ?: return
        val fav = active.currentMediaItem?.mediaId?.toLongOrNull()?.let { UserStore.isFavorite(it) } == true
        val shuffle = active.shuffleModeEnabled
        s.setMediaButtonPreferences(ImmutableList.of(
            CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (fav) R.string.n_unfav else R.string.n_fav))
                .setSessionCommand(CMD_FAV)
                .setEnabled(active.currentMediaItem != null)
                .build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(if (shuffle) R.string.n_shuffle_on else R.string.n_shuffle_off))
                .setSessionCommand(CMD_SHUFFLE)
                .build(),
        ))
    }

    private fun followOutput(route: OutputRoute.Route, key: String) {
        val p = SurcoSettings.state.value
        if (!p.perOutputEq || p.outputKey == key) {
            if (p.outputKey != key) SurcoSettings.update { it.copy(outputKey = key) }
            return
        }
        val (profiles, next) = switchOutput(p.outputProfiles, SoundProfile(p.dsp, p.preset), p.outputKey, key)
        SurcoSettings.update { it.copy(outputProfiles = profiles, outputKey = key, dsp = next.dsp, preset = next.preset) }
        // Solo se avisa si cambia lo que suena: la primera vez de unos cascos sigue igual.
        if (next.dsp != p.dsp) Notice.fromService(this, getString(R.string.eq_for_output, routeName(this, route)))
    }

    /** El mezclador del DAC USB para la canción que suena (lo aplica OutputRoute si hay DAC y Android 14+). */
    private fun wantMixer() {
        val e = exo.currentMediaItem?.mediaMetadata?.extras
        OutputRoute.want(OutputRoute.Wanted(
            enabled = SurcoSettings.state.value.usbBitPerfect,
            sampleRate = e?.getInt(EXTRA_SR) ?: 0,
            encoding = OutputRoute.encodingFor(e?.getString(EXTRA_FORMAT).orEmpty(), e?.getInt(EXTRA_BITS) ?: 16),
            channels = e?.getInt(EXTRA_CHANNELS)?.takeIf { it > 0 } ?: 2,
        ))
    }

    private fun publishWidget() {
        // El mosaico de ajustes rápidos también se entera (si está puesto).
        runCatching { TileService.requestListeningState(this, ComponentName(this, PlayTile::class.java)) }
        val p = active
        val item = p.currentMediaItem
        val md = p.mediaMetadata
        SurcoWidget.publish(this, WidgetState(
            trackId = item?.mediaId?.toLongOrNull(),
            title = md.title?.toString() ?: item?.mediaMetadata?.title?.toString() ?: getString(R.string.app_name),
            artist = (md.artist ?: item?.mediaMetadata?.artist)?.toString()?.ifBlank { null } ?: getString(R.string.w_unknown),
            // Lo que va a pasar, no el instante: sin parpadeo entre pausa y reproducir al cargar.
            playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE,
        ))
    }

    private fun applyVolume() { exo.volume = (rgGain * fadeMul * sleepMul * xfadeMul).coerceIn(0f, 1f) }

    private fun albumOf(item: MediaItem?): AlbumRef? = item?.let {
        AlbumRef(it.mediaMetadata.albumTitle?.toString().orEmpty(), it.mediaMetadata.extras?.getString(EXTRA_DIR).orEmpty())
    }

    /** ¿Baja al final la que suena? Si hay siguiente y no es del mismo disco (cuando se respetan). */
    private var lastFadedOut = false
    private fun fadesOut(p: com.eddie.surco.audio.Prefs): Boolean {
        // «Repetir una» vuelve a la misma: Media3 da como siguiente la de la cola (trata ONE como OFF).
        if (exo.repeatMode == Player.REPEAT_MODE_ONE) return false
        val next = exo.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return false
        return fadesBetween(albumOf(exo.currentMediaItem), albumOf(exo.getMediaItemAt(next)), p.crossfadeKeepAlbums)
    }

    // Cruce con solape: la cola de la canción que se va suena en un segundo reproductor (sin foco
    // de audio ni sesión) mientras la siguiente ya entra en el principal.
    private var ghost: ExoPlayer? = null
    private var ghostStart = 0L
    private var ghostLen = 0L
    private var ghostVol = 1f

    private fun ghostPlayer(): ExoPlayer = ghost ?: ExoPlayer.Builder(this, RenderersFactory { h, _, l, _, _ ->
        arrayOf<Renderer>(SurcoAudioRenderer(this, h, l, SurcoAudioSink.create(this, quiet = true)))
    })
        .setMediaSourceFactory(DefaultMediaSourceFactory(this, SurcoExtractorsFactory()))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build().also { ghost = it }

    /** Corta la cola del cruce (pausa, salto, otra canción a mano, cerrar). */
    private fun stopGhost() {
        ghost?.let { it.stop(); it.clearMediaItems() }
        ghostLen = 0L
        ghostFor = null
    }

    /** Para qué canción está preparado el segundo reproductor (en pausa, un poco antes del cruce). */
    private var ghostFor: String? = null

    /**
     * Prepara la cola del cruce un par de segundos antes, en pausa: abrir el archivo, el
     * decodificador y su AudioTrack lleva 100–300 ms, y sin esto el cruce empezaba con un hueco.
     */
    private fun prepareGhost() {
        val item = exo.currentMediaItem ?: return
        if (ghostFor == item.mediaId) return
        val g = ghostPlayer()
        g.volume = 0f
        g.setMediaItem(item, exo.currentPosition)
        g.playbackParameters = exo.playbackParameters
        g.playWhenReady = false
        g.prepare()
        ghostFor = item.mediaId
    }

    /** La que suena sigue en el segundo reproductor y el principal pasa ya a la siguiente. */
    private fun startOverlap(remainingMs: Long) {
        val item = exo.currentMediaItem ?: return
        if (ghostFor != item.mediaId) prepareGhost()
        val g = ghost ?: return
        ghostVol = exo.volume
        g.volume = ghostVol
        // Donde va ahora la principal (el reproductor preparado está unos segundos atrás).
        g.seekTo(exo.currentPosition)
        g.play()
        ghostStart = SystemClock.elapsedRealtime(); ghostLen = remainingMs
        overlapping = true
        exo.seekToNextMediaItem()
        overlapping = false
        // La que entra sube desde 0 como al pasar sola de una a otra.
        xfadeIn = true; lastFadedOut = true
        xfadeMul = 0f; applyVolume()
    }
    private var overlapping = false

    private val xfadeTick = object : Runnable {
        override fun run() {
            val p = SurcoSettings.state.value
            val fadeMs = p.crossfadeSec * 1000L
            var busy = false
            if (ghostLen > 0) {
                val el = SystemClock.elapsedRealtime() - ghostStart
                if (el >= ghostLen) stopGhost() else ghost?.volume = ghostVol * overlapOutGain(el, ghostLen)
                busy = true
            }
            val g = if (fadeMs > 0 && exo.isPlaying) {
                val pos = exo.currentPosition
                val dur = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                // Cerca del final o del principio, cada 50 ms; en medio de la canción, cada 500.
                val nearEnd = dur > 0 && dur - pos < fadeMs + 600
                busy = busy || nearEnd || (xfadeIn && pos < fadeMs + 600)
                val out = nearEnd && fadesOut(p)
                if (nearEnd) lastFadedOut = out
                // Al llegar al cruce, las dos a la vez (no con «parar al acabar esta canción»). Antes,
                // la cola se deja preparada; se cruza cuando está lista (o ya al final, si no llega).
                val f = min(fadeMs, dur / 4)
                val crossing = out && ghostLen == 0L && !SleepTimer.endOfTrack.value
                if (crossing && dur - pos <= f + 2_500) prepareGhost()
                val ready = ghost?.playbackState == Player.STATE_READY
                if (crossing && dur - pos <= f && dur - pos > 200 && (ready || dur - pos < 600)) {
                    startOverlap(dur - pos)
                    0f // la que entra empieza en silencio y sube en las vueltas siguientes
                } else crossfadeGain(pos, dur, fadeMs, xfadeIn, out)
            } else if (fadeMs > 0) xfadeMul else 1f
            // Pasos de menos de medio punto no se oyen y cada cambio de volumen viaja a cada mando.
            if (kotlin.math.abs(g - xfadeMul) >= 0.005f || (g == 1f && xfadeMul != 1f) || (g == 0f && xfadeMul != 0f)) { xfadeMul = g; applyVolume() }
            handler.postDelayed(this, if (busy) 50 else 500)
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
        val title = exo.currentMediaItem?.mediaMetadata?.title ?: getString(R.string.app_err_the_song)
        // Solo los fallos del archivo (no se encuentra, no se puede leer o descodificar) quitan la
        // canción; uno de la salida de audio no es culpa suya: se para y se avisa.
        val fileProblem = error.errorCode in 2000..4999
        if (!fileProblem) {
            Notice.fromService(this, getString(R.string.app_err_output, error.errorCodeName), long = true)
            return
        }
        Notice.fromService(this, getString(R.string.app_err_play, title))
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
            stopGhost()
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

    // Con «Enviar a» la sesión cambia de reproductor: el widget, el mosaico y los botones de la
    // notificación siguen al nuevo (el local ya los avisa desde su propio oyente).
    private var watched: Player? = null
    private val remoteListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publishWidget(); publishButtons() }
    }

    private fun followActive() {
        val p = session?.player ?: return
        val remote = p.takeIf { it !is FadingPlayer }
        if (remote === watched) return
        watched?.removeListener(remoteListener)
        watched = remote
        remote?.addListener(remoteListener)
        publishWidget(); publishButtons()
    }

    private fun onTick() {
        followActive()
        // Escuchas: cuenta al pasar la mitad (o 4 minutos), como Last.fm.
        val id = exo.currentMediaItem?.mediaId
        val pos = exo.currentPosition
        if (id != listenedId) { listenedId = id; listenedMs = 0; counted = false; lastPos = pos; lbNow = false }
        if (exo.isPlaying) {
            val d = pos - lastPos
            if (d in 1..2_000) listenedMs += d
            val dur = exo.duration
            // ListenBrainz (si se activa): «sonando ahora» al empezar y la escucha al contarla.
            if (!lbNow && dur > 0) { lbNow = true; exo.currentMediaItem?.let { Scrobbler.nowPlaying(this, it, dur) } }
            val need = if (dur > 0) min(dur / 2, 240_000L) else 30_000L
            if (!counted && id != null && listenedMs >= need) {
                counted = true
                id.toLongOrNull()?.let { UserStore.recordPlay(it) }
                if (dur > 0) exo.currentMediaItem?.let { Scrobbler.listened(this, it, dur, System.currentTimeMillis() / 1000 - listenedMs / 1000) }
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
        // Sonando en otro aparato también cuenta: no se para el servicio.
        val p = session?.player ?: exo
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveQueue()
        // El widget se queda con la canción, en pausa.
        SurcoWidget.last?.let { SurcoWidget.publish(this, it.copy(playing = false)) }
        UserStore.flush()
        OutputRoute.stop()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        ghost?.release(); ghost = null
        router?.release(); router = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private companion object {
        val CMD_FAV = SessionCommand("surco.FAVORITA", Bundle.EMPTY)
        val CMD_SHUFFLE = SessionCommand("surco.ALEATORIO", Bundle.EMPTY)
    }
}
