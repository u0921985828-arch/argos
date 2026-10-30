package com.eddie.surco.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.data.UpnpDevice
import com.eddie.surco.ui.Notice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A dónde va el sonido. */
sealed interface Output {
    data object Phone : Output
    /** Un aparato DLNA/UPnP (tele, Sonos, receptor). */
    data class Renderer(val device: UpnpDevice) : Output
    /** Un Chromecast (o tele/altavoz con Chromecast integrado), por su ruta de MediaRouter. */
    data class Cast(val id: String, val name: String) : Output
}

/** Un Chromecast encontrado en la wifi. */
data class CastDevice(val id: String, val name: String, val description: String)

/**
 * La salida elegida, compartida entre la interfaz y el servicio (mismo proceso, como SleepTimer).
 * La interfaz pide con [choose]; el servicio, por su [RemoteRouter], cambia el reproductor de la
 * sesión y publica [active].
 */
object Outputs {
    private val _active = MutableStateFlow<Output>(Output.Phone)
    val active: StateFlow<Output> = _active.asStateFlow()
    /** Nombre del Chromecast con el que se está conectando (tarda unos segundos), o null. */
    private val _connecting = MutableStateFlow<String?>(null)
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    // Lo pone el RemoteRouter del servicio. Una función y no el router: un campo estático con algo
    // que guarda un Context es una fuga (y lint lo marca).
    internal var onChoose: ((Output) -> Unit)? = null

    /** Desde el hilo principal. Sin servicio (no debería pasar con la app abierta) no hace nada. */
    fun choose(o: Output) { onChoose?.invoke(o) }

    internal fun set(o: Output) { _active.value = o; _connecting.value = null }
    internal fun connecting(name: String?) { _connecting.value = name }
}

/**
 * Chromecast con el receptor por defecto de Google. En un móvil sin servicios de Google (o con
 * ellos viejos) CastContext no existe: todo esto devuelve null o listas vacías y queda solo DLNA.
 */
object CastSupport {
    // Sin servicios de Google no se vuelve a intentar en todo el proceso.
    private var unavailable = false
    private var selector: MediaRouteSelector? = null
    private var scanning = false
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    /** El CastContext, o null sin servicios de Google. Desde el hilo principal. */
    fun context(ctx: Context): CastContext? {
        if (unavailable) return null
        return try {
            // Con el ejecutor directo, la primera vez se crea aquí mismo; después es la misma.
            val task = CastContext.getSharedInstance(ctx.applicationContext, MoreExecutors.directExecutor())
            when {
                !task.isComplete -> null
                task.isSuccessful -> task.result
                else -> { unavailable = true; null }
            }
        } catch (e: Exception) {
            // Sin servicios de Google, o el módulo de Cast no carga: solo DLNA.
            unavailable = true
            null
        }
    }

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh(router)
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh(router)
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh(router)
    }

    /** Buscar Chromecast mientras la hoja «Enviar a» está abierta (búsqueda activa: gasta batería). */
    fun startScan(ctx: Context) {
        val c = context(ctx) ?: return
        val sel = runCatching { c.mergedSelector }.getOrNull() ?: return
        val r = MediaRouter.getInstance(ctx.applicationContext)
        selector = sel
        r.addCallback(sel, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        scanning = true
        refresh(r)
    }

    fun stopScan(ctx: Context) {
        if (!scanning) return
        scanning = false
        MediaRouter.getInstance(ctx.applicationContext).removeCallback(callback)
    }

    private fun refresh(r: MediaRouter) {
        val sel = selector ?: return
        _devices.value = r.routes.filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(sel) }
            .map { CastDevice(it.id, it.name, it.description ?: "") }
    }

    /** Elegir la ruta abre la sesión de Cast; el CastPlayer avisa al [RemoteRouter] cuando está. */
    fun select(ctx: Context, id: String): Boolean {
        val r = MediaRouter.getInstance(ctx.applicationContext)
        val route = r.routes.firstOrNull { it.id == id } ?: return false
        r.selectRoute(route)
        return true
    }

    fun endSession(ctx: Context) {
        runCatching { context(ctx)?.sessionManager?.endCurrentSession(true) }
    }

    fun currentName(ctx: Context): String? =
        runCatching { context(ctx)?.sessionManager?.currentCastSession?.castDevice?.friendlyName }.getOrNull()
}

/**
 * Opciones de Cast: el receptor por defecto (reproduce MP3, AAC, FLAC, Ogg/Opus y WAV por HTTP),
 * sin reanudar sesiones viejas y sin la notificación propia de Cast: la de la sesión de Surco ya
 * enseña y maneja lo que suene en el Chromecast. Lo nombra el manifiesto.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setResumeSavedSession(false)
        .setEnableReconnectionService(false)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()
}

/**
 * Lo que se manda al Chromecast: los content:// de MediaStore no le valen, así que cada canción
 * va con la dirección del servidor del móvil en la wifi (y su tipo, que el receptor exige). Al
 * volver, la URI vuelve a ser la de MediaStore (por el _ID).
 */
@OptIn(UnstableApi::class)
class LanItemConverter(private val media: LanMedia) : MediaItemConverter {
    private val base = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        // Sin servirla (sin wifi o sin URI): va tal cual; el receptor dará error y la sesión lo dirá.
        val s = media.serve(mediaItem)
        val lan = if (s == null) {
            mediaItem.buildUpon().setMimeType("audio/mpeg").build()
        } else {
            mediaItem.buildUpon().setUri(s.url).setMimeType(s.mime)
                .setMediaMetadata(mediaItem.mediaMetadata.buildUpon().setArtworkUri(s.artUrl?.let { Uri.parse(it) }).build())
                .build()
        }
        return base.toMediaQueueItem(lan)
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val m = base.toMediaItem(mediaQueueItem)
        val uri = m.mediaId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) } ?: return m
        return m.buildUpon().setUri(uri).setMimeType(null)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()).build()
    }
}

/**
 * Cambia el reproductor de la sesión entre el del móvil, un aparato DLNA ([RemotePlayer]) y un
 * Chromecast ([CastPlayer]). La sesión es la misma: notificación, pantalla de bloqueo, mandos,
 * widget y la propia app siguen funcionando, ahora sobre el aparato. La cola pasa entera al ir y
 * vuelve al móvil al acabar (con la posición). Vive en el servicio, en el hilo principal.
 */
@OptIn(UnstableApi::class)
class RemoteRouter(private val ctx: Context, private val session: MediaSession, private val local: Player) {
    private val media = LanMedia(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var dlna: RemotePlayer? = null
    private var cast: CastPlayer? = null
    private var castWindow: Window? = null
    private var lastCast: Handoff? = null
    private var lastCastAt = 0L

    /** La cola entera del móvil y el trozo que se mandó al Chromecast ([from] hasta [until]). */
    private class Window(val all: List<MediaItem>, val from: Int, val until: Int)

    private val castListener = object : Player.Listener {
        // CastPlayer vacía su cola al cerrarse la sesión: se guarda lo último que tenía.
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.mediaItemCount > 0) { lastCast = player.handoff(); lastCastAt = SystemClock.elapsedRealtime() }
        }
    }

    private val chooser: (Output) -> Unit = { choose(it) }

    init {
        Outputs.onChoose = chooser
        ensureCast()
    }

    private fun ensureCast(): CastPlayer? {
        cast?.let { return it }
        val c = CastSupport.context(ctx) ?: return null
        val p = runCatching { CastPlayer(c, LanItemConverter(media)) }.getOrNull() ?: return null
        p.addListener(castListener)
        p.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = startCast()
            override fun onCastSessionUnavailable() = endCast(keepPlaying = false)
        })
        cast = p
        return p
    }

    fun choose(o: Output) {
        val now = Outputs.active.value
        when (o) {
            Output.Phone -> toPhone(keepPlaying = true)
            is Output.Renderer -> if (!(now is Output.Renderer && now.device.udn == o.device.udn)) toDlna(o.device)
            is Output.Cast -> if (now !is Output.Cast) {
                if (ensureCast() == null) return
                Outputs.connecting(o.name)
                if (!CastSupport.select(ctx, o.id)) Outputs.connecting(null)
                // Si la sesión no llega a abrirse (Chromecast apagado a mitad), no se queda «Conectando…».
                main.postDelayed({ if (Outputs.active.value !is Output.Cast) Outputs.connecting(null) }, 25_000)
            }
        }
    }

    /** Vuelve al móvil. [keepPlaying]: si lo pidió quien escucha; si se perdió el aparato, en pausa. */
    private fun toPhone(keepPlaying: Boolean) {
        Outputs.connecting(null)
        dlna?.let { r ->
            dlna = null
            val h = r.snapshot()
            local.resumeFrom(if (keepPlaying) h else h.copy(playWhenReady = false))
            session.player = local
            r.release()
            media.stop()
            Outputs.set(Output.Phone)
        }
        if (Outputs.active.value is Output.Cast) {
            endCast(keepPlaying)
            CastSupport.endSession(ctx)
        }
    }

    private fun toDlna(device: UpnpDevice) {
        if (Outputs.active.value != Output.Phone) toPhone(keepPlaying = true)
        if (!media.start()) { Notice.fromService(ctx, Res.str(R.string.cast_no_wifi)); return }
        val h = local.handoff()
        local.pause()
        val r = RemotePlayer(device, media, h) { lostOne ->
            if (dlna === lostOne) {
                Notice.fromService(ctx, Res.str(R.string.cast_lost, device.name), long = true)
                toPhone(keepPlaying = false)
            }
        }
        dlna = r
        session.player = r
        Outputs.set(Output.Renderer(device))
        Notice.fromService(ctx, Res.str(R.string.cast_now_on, device.name))
    }

    private fun startCast() {
        val p = cast ?: return
        if (Outputs.active.value is Output.Cast) return
        if (dlna != null) toPhone(keepPlaying = true)
        if (!media.start()) {
            Notice.fromService(ctx, Res.str(R.string.cast_no_wifi))
            CastSupport.endSession(ctx)
            Outputs.connecting(null)
            return
        }
        val h = local.handoff()
        // El Chromecast recibe la cola en un solo mensaje, con tope de tamaño: se le manda un trozo
        // alrededor de la que suena, y al volver se cose con el resto.
        val from = (h.index - 20).coerceAtLeast(0)
        val until = (from + 100).coerceAtMost(h.items.size)
        castWindow = Window(h.items, from, until)
        lastCast = null
        local.pause()
        if (h.items.isNotEmpty()) {
            p.setMediaItems(h.items.subList(from, until), h.index - from, h.positionMs)
            p.repeatMode = h.repeatMode
            p.playWhenReady = h.playWhenReady
        }
        session.player = p
        val name = CastSupport.currentName(ctx) ?: Outputs.connecting.value ?: "Chromecast"
        Outputs.set(Output.Cast(id = "", name = name))
        Notice.fromService(ctx, Res.str(R.string.cast_now_on, name))
    }

    private fun endCast(keepPlaying: Boolean) {
        if (Outputs.active.value !is Output.Cast) { Outputs.connecting(null); return }
        val p = cast
        // Mejor lo de ahora; si la sesión ya se cerró, lo último guardado, con la posición adelantada
        // lo que haya pasado desde entonces (CastPlayer no avisa de cada segundo).
        val got = p?.takeIf { it.mediaItemCount > 0 }?.handoff()
            ?: lastCast?.let { h -> if (h.playWhenReady) h.copy(positionMs = h.positionMs + SystemClock.elapsedRealtime() - lastCastAt) else h }
        val w = castWindow
        val h = when {
            got == null -> null
            w == null -> got
            // El trozo que volvió (con lo que se añadiera o quitara allí), cosido con el resto.
            else -> Handoff(w.all.subList(0, w.from) + got.items + w.all.subList(w.until, w.all.size), w.from + got.index, got.positionMs, got.playWhenReady, got.repeatMode)
        }
        if (h != null) local.resumeFrom(if (keepPlaying) h else h.copy(playWhenReady = false))
        session.player = local
        castWindow = null; lastCast = null
        media.stop()
        Outputs.set(Output.Phone)
    }

    /** Al cerrar el servicio: todo vuelve al móvil y se suelta lo de Cast. */
    fun release() {
        if (Outputs.active.value != Output.Phone || dlna != null) toPhone(keepPlaying = false)
        cast?.let { it.removeListener(castListener); it.setSessionAvailabilityListener(null); it.release() }
        cast = null
        main.removeCallbacksAndMessages(null)
        media.stop()
        if (Outputs.onChoose === chooser) Outputs.onChoose = null
        Outputs.set(Output.Phone)
    }
}
