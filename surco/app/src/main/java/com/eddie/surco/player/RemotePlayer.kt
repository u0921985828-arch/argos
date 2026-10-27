package com.eddie.surco.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.data.DidlItem
import com.eddie.surco.data.MiniHttp
import com.eddie.surco.data.RemoteStep
import com.eddie.surco.data.SoapResult
import com.eddie.surco.data.Upnp
import com.eddie.surco.data.UpnpDevice
import com.eddie.surco.ui.Notice
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/**
 * Lo que pasa de un reproductor a otro al cambiar de salida: la cola EN EL ORDEN EN QUE SUENA
 * (con aleatorio, el orden aleatorio), dónde va y si suena. El aleatorio no viaja como modo: la
 * cola ya va en su orden, y así la que suena sigue siendo la misma al ir y al volver.
 */
data class Handoff(val items: List<MediaItem>, val index: Int, val positionMs: Long, val playWhenReady: Boolean, val repeatMode: Int)

/** La cola de [this] en el orden en que va a sonar. */
fun Player.handoff(): Handoff {
    val n = mediaItemCount
    if (n == 0) return Handoff(emptyList(), 0, 0, false, repeatMode)
    val tl = currentTimeline
    val order = ArrayList<Int>(n)
    var i = tl.getFirstWindowIndex(shuffleModeEnabled)
    while (i != C.INDEX_UNSET && order.size < n) {
        order += i
        i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    if (order.size < n) { order.clear(); order.addAll(0 until n) }
    val at = order.indexOf(currentMediaItemIndex).coerceAtLeast(0)
    val ended = playbackState == Player.STATE_ENDED
    return Handoff(order.map { getMediaItemAt(it) }, at, if (ended) 0 else currentPosition.coerceAtLeast(0), playWhenReady && !ended, repeatMode)
}

/** Sigue en [this] lo que traía [h] (al volver al móvil o al pasar a otro aparato). */
fun Player.resumeFrom(h: Handoff) {
    shuffleModeEnabled = false
    repeatMode = h.repeatMode
    if (h.items.isEmpty()) { clearMediaItems(); return }
    setMediaItems(h.items, h.index.coerceIn(h.items.indices), h.positionMs)
    prepare()
    playWhenReady = h.playWhenReady
}

/**
 * La música del móvil, servida en la wifi para el aparato que la toca (DLNA o Chromecast). Las
 * canciones son content:// de MediaStore: se abren por ContentResolver al pedirlas. Mientras sirve,
 * la wifi y la CPU no se duermen (con la pantalla apagada, la tele se quedaría sin datos a mitad).
 */
class LanMedia(ctx: Context) {
    private val app = ctx.applicationContext
    val context: Context get() = app
    private val http = MiniHttp()
    private val served = HashMap<String, Served>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile var lan: Lan? = null; private set

    /** Una canción servida: su dirección, tipo, portada (si la hay) y la URI original. */
    class Served(val url: String, val mime: String, val artUrl: String?, val uri: Uri)

    /** Arranca el servidor en la wifi de ahora. false: no hay wifi. */
    @Synchronized
    fun start(): Boolean {
        val l = Lan.find(app) ?: return false
        lan = l
        if (!http.running) http.start()
        if (wifiLock == null) {
            wifiLock = runCatching {
                app.getSystemService(WifiManager::class.java)?.createWifiLock(wifiLockMode(), "surco:enviar")
                    ?.apply { setReferenceCounted(false) }
            }.getOrNull()
        }
        if (wakeLock == null) {
            wakeLock = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "surco:enviar")
                ?.apply { setReferenceCounted(false) }
        }
        keepAwake()
        return true
    }

    /** Se renueva mientras se envía (cada sondeo): con plazo, un cierre raro no deja el móvil despierto. */
    fun keepAwake() {
        runCatching { wifiLock?.acquire() }
        runCatching { wakeLock?.acquire(10 * 60_000L) }
    }

    @Synchronized
    fun stop() {
        http.stop()
        http.clear()
        served.clear()
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        lan = null
    }

    /**
     * La dirección por la que el aparato pide [item]. Sin consultas a MediaStore salvo que el
     * formato no se sepa: el Chromecast la pide en el hilo principal. null: sin wifi o sin URI.
     */
    @Synchronized
    fun serve(item: MediaItem): Served? {
        val l = lan ?: return null
        val uri = uriOf(item) ?: return null
        served[uri.toString()]?.let { return it }
        if (served.size > 2_000) { http.clear(); served.clear() }
        val format = item.mediaMetadata.extras?.getString(EXTRA_FORMAT).orEmpty()
        val mime = Upnp.mimeForFormat(format) ?: runCatching { app.contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg"
        val cr = app.contentResolver
        val src = MiniHttp.Source(mime, { sizeOf(uri) }, { cr.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()) })
        val base = "http://${l.address.hostAddress}:${http.port}"
        val url = base + http.register(src, Upnp.extForMime(mime))
        val art = item.mediaMetadata.artworkUri?.let { a ->
            base + http.register(MiniHttp.Source("image/jpeg", { -1L }, { cr.openInputStream(a) ?: throw FileNotFoundException(a.toString()) }), "jpg")
        }
        return Served(url, mime, art, uri).also { served[uri.toString()] = it }
    }

    /** Metadatos DIDL para DLNA (consulta MediaStore: fuera del hilo principal). */
    fun didl(item: MediaItem, s: Served): DidlItem {
        var dur = 0L
        var size = -1L
        runCatching {
            app.contentResolver.query(s.uri, arrayOf(MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { dur = c.getLong(0); size = c.getLong(1) }
            }
        }
        val m = item.mediaMetadata
        return DidlItem(
            title = m.title?.toString().orEmpty().ifEmpty { Res.str(R.string.cast_unknown_title) },
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString().orEmpty(),
            durationMs = dur, mime = s.mime, url = s.url, artUrl = s.artUrl, size = size,
        )
    }

    // El modo de ExoPlayer para lo que llega por red (WAKE_MODE_NETWORK). Está marcado como obsoleto,
    // pero el que lo sustituye (LOW_LATENCY) solo actúa con la pantalla encendida, y aquí hace falta
    // justo con ella apagada.
    @Suppress("DEPRECATION")
    private fun wifiLockMode() = WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private fun sizeOf(uri: Uri): Long = runCatching {
        app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

    companion object {
        /** La URI de la canción: la del reproductor, la de requestMetadata o, por el _ID, la de MediaStore. */
        fun uriOf(item: MediaItem): Uri? = item.localConfiguration?.uri
            ?: item.requestMetadata.mediaUri
            ?: item.mediaId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
    }
}

/**
 * La cola sonando en un aparato DLNA (tele, Sonos, receptor), como un Player de Media3: la sesión,
 * la notificación, los mandos del sistema y la propia app lo manejan igual que al ExoPlayer local.
 * El aparato pide cada canción al servidor del móvil ([LanMedia]). Estado y posición se sondean
 * cada segundo (GetTransportInfo + GetPositionInfo); al acabar una, pasa a la siguiente él solo si
 * admite SetNextAVTransportURI (sin hueco), o se la da Surco. Las órdenes SOAP van en un solo hilo
 * y en orden: dos a la vez confunden a muchas teles.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(
    val device: UpnpDevice,
    private val media: LanMedia,
    start: Handoff,
    private val onLost: (RemotePlayer) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private class Entry(val uid: Any, val item: MediaItem)

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "surco-dlna").apply { isDaemon = true } }
    private val entries = ArrayList<Entry>()
    private var uids = 0L
    private var index = 0
    private var playWhenReady = start.playWhenReady
    private var playbackState = Player.STATE_BUFFERING
    private var positionMs = 0L
    private var durationMs = C.TIME_UNSET
    private var repeat = start.repeatMode
    private var shuffle = false
    private var volume = -1 // 0–100 del aparato; -1 hasta saberlo
    private var muted = false
    private var error: PlaybackException? = null
    private var closed = false

    // Lo que tiene el aparato.
    private var gen = 0
    private var currentUrl: String? = null
    private var nextUrl: String? = null
    private var nextFor: Any? = null
    private var nextSupported = true
    private var sawPlaying = false
    private var pendingSeekMs = 0L
    private var lastCommand = 0L
    private var failures = 0
    private var foreign = 0
    private var polling = false
    private var ticks = 0
    private var playlistCache: List<MediaItemData>? = null

    private val hasVolume = device.renderingUrl != null
    // La red de cuando empezó: al volver al móvil el servidor se para, y el Stop final aún tiene que salir.
    private val lan: Lan? = media.lan

    init {
        start.items.forEach { entries += Entry(uids++, it) }
        index = start.index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        positionMs = start.positionMs
        if (entries.isEmpty()) playbackState = Player.STATE_ENDED
        main.post {
            if (closed) return@post
            if (entries.isNotEmpty()) load(index, positionMs, playWhenReady)
            invalidateState()
            main.postDelayed(poll, 1_000)
        }
    }

    // ---------- Estado para Media3 ----------

    override fun getState(): State {
        val items = playlistCache ?: entries.mapIndexed { i, e ->
            MediaItemData.Builder(e.uid).setMediaItem(e.item).setIsSeekable(true)
                .setDurationUs(if (i == index && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
                .build()
        }.also { playlistCache = it }
        val state = when {
            entries.isEmpty() && playbackState != Player.STATE_IDLE -> Player.STATE_ENDED
            else -> playbackState
        }
        val b = State.Builder()
            .setAvailableCommands(commands())
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(if (state == Player.STATE_IDLE) error else null)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(positionMs)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(if (hasVolume) VOLUME_STEPS else 0).build())
        if (hasVolume && volume >= 0) b.setDeviceVolume((volume * VOLUME_STEPS / 100f).roundToInt().coerceIn(0, VOLUME_STEPS)).setIsDeviceMuted(muted)
        return b.build()
    }

    private fun commands(): Player.Commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
    ).apply {
        if (hasVolume) addAll(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
    }.build()

    /** Lo que trae este reproductor, para seguir en otro (al volver al móvil). */
    fun snapshot(): Handoff = handoff()

    // ---------- Órdenes ----------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        lastCommand = SystemClock.elapsedRealtime()
        if (currentUrl == null || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return done()
        return if (playWhenReady) {
            val seekTo = pendingSeekMs.also { pendingSeekMs = 0 }
            onIo({ startPlaying(seekTo) })
        } else {
            onIo({ call(Upnp.pause(device)) })
        }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (playbackState != Player.STATE_IDLE || entries.isEmpty()) return done()
        error = null; failures = 0
        return load(index, positionMs, playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> {
        playbackState = Player.STATE_IDLE
        gen++; currentUrl = null; nextUrl = null; nextFor = null
        return onIo({ call(Upnp.stop(device)) })
    }

    override fun handleRelease(): ListenableFuture<*> {
        closed = true
        main.removeCallbacks(poll)
        // La tele se para: la música vuelve al móvil (o a otro aparato).
        runCatching { io.execute { runCatching { call(Upnp.stop(device)) } } }
        io.shutdown()
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        queueNext()
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        // Aleatorio: lo que queda detrás de la que suena se baraja de verdad (el orden se ve en la cola).
        if (shuffleModeEnabled && !shuffle && index + 2 < entries.size) {
            val rest = entries.subList(index + 1, entries.size)
            val mixed = rest.shuffled()
            rest.clear(); rest.addAll(mixed)
            playlistCache = null
            queueNext()
        }
        shuffle = shuffleModeEnabled
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        entries.clear()
        mediaItems.forEach { entries += Entry(uids++, it) }
        playlistCache = null
        if (entries.isEmpty()) {
            gen++; currentUrl = null; nextUrl = null; nextFor = null
            if (playbackState != Player.STATE_IDLE) playbackState = Player.STATE_ENDED
            return onIo({ call(Upnp.stop(device)) })
        }
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(entries.indices)
        val pos = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        if (playbackState == Player.STATE_IDLE) { positionMs = pos; return done() }
        return load(index, pos, playWhenReady)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val wasEmpty = entries.isEmpty()
        entries.addAll(index, mediaItems.map { Entry(uids++, it) })
        playlistCache = null
        if (wasEmpty) {
            this.index = 0
            return if (playbackState == Player.STATE_IDLE) done() else load(0, 0, playWhenReady)
        }
        if (index <= this.index) this.index += mediaItems.size
        queueNext()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val wasCurrent = index in fromIndex until toIndex
        entries.subList(fromIndex, toIndex).clear()
        playlistCache = null
        if (entries.isEmpty()) {
            gen++; currentUrl = null; nextUrl = null; nextFor = null
            if (playbackState != Player.STATE_IDLE) playbackState = Player.STATE_ENDED
            return onIo({ call(Upnp.stop(device)) })
        }
        if (wasCurrent) {
            index = fromIndex.coerceAtMost(entries.size - 1)
            return if (playbackState == Player.STATE_IDLE) done() else load(index, 0, playWhenReady)
        }
        if (index >= toIndex) index -= toIndex - fromIndex
        queueNext()
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val cur = entries.getOrNull(index)?.uid
        val moved = ArrayList(entries.subList(fromIndex, toIndex))
        entries.subList(fromIndex, toIndex).clear()
        entries.addAll(newIndex, moved)
        index = entries.indexOfFirst { it.uid == cur }.coerceAtLeast(0)
        playlistCache = null
        queueNext()
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex !in entries.indices) return done()
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        if (playbackState == Player.STATE_IDLE) { index = mediaItemIndex; this.positionMs = pos; playlistCache = null; return done() }
        if (mediaItemIndex != index || currentUrl == null || playbackState == Player.STATE_ENDED) {
            return load(mediaItemIndex, pos, playWhenReady)
        }
        this.positionMs = pos
        lastCommand = SystemClock.elapsedRealtime()
        if (!sawPlaying) {
            // Cargada pero sin haber sonado: muchas teles no saltan aún; se salta al darle a Play.
            pendingSeekMs = pos
            return done()
        }
        return onIo({ call(Upnp.seek(device, pos)) })
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        setVolume((deviceVolume * 100f / VOLUME_STEPS).roundToInt())

    // Sin saber aún el volumen del aparato, una tecla no lo lleva a un valor inventado.
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        if (volume < 0) done() else setVolume(volume + 100 / VOLUME_STEPS)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        if (volume < 0) done() else setVolume(volume - 100 / VOLUME_STEPS)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        this.muted = muted
        val req = Upnp.setMute(device, muted) ?: return done()
        return onIo({ call(req) })
    }

    private fun setVolume(v: Int): ListenableFuture<*> {
        volume = v.coerceIn(0, 100)
        val req = Upnp.setVolume(device, volume) ?: return done()
        return onIo({ call(req) })
    }

    // ---------- El aparato ----------

    private fun call(req: com.eddie.surco.data.SoapRequest): SoapResult {
        val l = lan ?: throw IOException("sin wifi")
        return Dlna.call(l, req)
    }

    /** Carga la canción [i] en el aparato y, si [play], la pone a sonar desde [startMs]. */
    private fun load(i: Int, startMs: Long, play: Boolean): ListenableFuture<*> {
        val e = entries.getOrNull(i) ?: return done()
        val g = ++gen
        index = i; positionMs = startMs.coerceAtLeast(0); durationMs = C.TIME_UNSET
        playbackState = Player.STATE_BUFFERING
        sawPlaying = false; currentUrl = null; nextUrl = null; nextFor = null; foreign = 0
        pendingSeekMs = 0
        playlistCache = null
        lastCommand = SystemClock.elapsedRealtime()
        return onIo({
            val s = media.serve(e.item) ?: throw IOException("sin wifi")
            val didl = media.didl(e.item, s)
            val meta = Upnp.didl(didl)
            var r = call(Upnp.setUri(device, s.url, meta))
            if (r is SoapResult.Fault) {
                // Hay teles que no cambian de canción sin pararse antes (705 «transporte bloqueado»).
                call(Upnp.stop(device))
                r = call(Upnp.setUri(device, s.url, meta))
            }
            if (r is SoapResult.Fault) throw RejectedUri(r.code)
            if (play) startPlaying(startMs)
            s.url to didl.durationMs
        }) { res ->
            if (g != gen) return@onIo
            res.onSuccess { (url, dur) ->
                currentUrl = url
                if (dur > 0) durationMs = dur
                playlistCache = null
                if (!play) {
                    pendingSeekMs = startMs
                    playbackState = Player.STATE_READY
                    // Se pidió Play mientras cargaba.
                    if (playWhenReady) { val seekTo = pendingSeekMs.also { pendingSeekMs = 0 }; onIo({ startPlaying(seekTo) }) }
                } else if (!playWhenReady) {
                    // Se pidió pausa mientras cargaba (y ya se le había dado a Play).
                    onIo({ call(Upnp.pause(device)) })
                }
                queueNext()
            }.onFailure { failed(it) }
        }
    }

    private class RejectedUri(val code: Int) : IOException("SetAVTransportURI $code")

    /** En el hilo del aparato: Play y, si hay que empezar a mitad, esperar a que suene y saltar. */
    private fun startPlaying(startMs: Long) {
        call(Upnp.play(device))
        if (startMs < 1_500) return
        repeat(10) {
            val st = (call(Upnp.getTransportInfo(device)) as? SoapResult.Ok)?.let { Upnp.transportState(it.values) }
            if (st == "PLAYING") { call(Upnp.seek(device, startMs)); return }
            Thread.sleep(300)
        }
    }

    /** Le dice al aparato cuál va después (sin hueco entre canciones), si lo admite. */
    private fun queueNext() {
        val cur = currentUrl ?: return
        if (!nextSupported) return
        val n = Upnp.nextIndex(index, entries.size, repeat)
        if (n == null || n == index) {
            // Sin siguiente, o «repetir una»: lo que hubiera de antes se cambia por la misma canción.
            if (nextUrl == null) return
            nextUrl = null; nextFor = null
            val req = Upnp.setNextUri(device, cur, "")
            onIo({ call(req) })
            return
        }
        val e = entries[n]
        if (nextFor == e.uid) return
        val g = gen
        onIo({
            val s = media.serve(e.item) ?: throw IOException("sin wifi")
            s.url to call(Upnp.setNextUri(device, s.url, Upnp.didl(media.didl(e.item, s))))
        }) { res ->
            if (g != gen) return@onIo
            res.onSuccess { (url, r) ->
                when {
                    r is SoapResult.Ok -> { nextUrl = url; nextFor = e.uid }
                    // No lo tiene: Surco pasa de canción al ver que se ha parado.
                    r is SoapResult.Fault -> nextSupported = false
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            tick()
            main.postDelayed(this, 1_000)
        }
    }

    private fun tick() {
        if (polling || playbackState == Player.STATE_IDLE || currentUrl == null) return
        polling = true
        if (++ticks % 60 == 0) media.keepAwake()
        val g = gen
        val askVolume = hasVolume && (ticks % 5 == 1 || volume < 0)
        try {
            io.execute {
                val r = runCatching {
                    val t = call(Upnp.getTransportInfo(device))
                    val p = call(Upnp.getPositionInfo(device))
                    val v = if (askVolume) Upnp.getVolume(device)?.let { call(it) } else null
                    Triple(t, p, v)
                }
                main.post {
                    polling = false
                    if (!closed && g == gen) applyPoll(r)
                }
            }
        } catch (_: RejectedExecutionException) {
            polling = false
        }
    }

    private fun applyPoll(r: Result<Triple<SoapResult, SoapResult, SoapResult?>>) {
        val (t, p, v) = r.getOrElse {
            if (++failures >= 4) lost()
            return
        }
        failures = 0
        val tv = (t as? SoapResult.Ok)?.values.orEmpty()
        val pv = (p as? SoapResult.Ok)?.values.orEmpty()
        (v as? SoapResult.Ok)?.let { Upnp.volume(it.values) }?.let { volume = it }
        val state = Upnp.transportState(tv)
        val pos = Upnp.position(pv)
        val since = SystemClock.elapsedRealtime() - lastCommand
        when (state) {
            "PLAYING" -> {
                sawPlaying = true
                playbackState = Player.STATE_READY
                // Alguien le dio al Play del mando de la tele.
                if (!playWhenReady && since > 3_000) playWhenReady = true
            }
            "PAUSED_PLAYBACK" -> {
                playbackState = Player.STATE_READY
                if (playWhenReady && since > 3_000) playWhenReady = false
            }
            "TRANSITIONING" -> if (playWhenReady) playbackState = Player.STATE_BUFFERING
        }
        // Justo después de saltar, la tele aún dice la posición de antes.
        if (since > 1_500) pos.relMs?.let { positionMs = it }
        pos.durationMs?.let { if (it != durationMs) { durationMs = it; playlistCache = null } }

        // Otra app ha puesto otra cosa en el aparato: se devuelve la música al móvil.
        val mine = pos.trackUri.isEmpty() || pos.trackUri == currentUrl || pos.trackUri == nextUrl
        foreign = if (mine || !sawPlaying) 0 else foreign + 1
        if (foreign >= 3) { lost(); return }

        when (Upnp.judge(state, pos.trackUri, nextUrl, sawPlaying)) {
            RemoteStep.RENDERER_ADVANCED -> {
                val idx = entries.indexOfFirst { it.uid == nextFor }
                if (idx < 0) {
                    // La que tenía apuntada ya no está en la cola: la que toca de verdad.
                    Upnp.nextIndex(index, entries.size, repeat)?.let { load(it, 0, true) }
                } else {
                    index = idx; currentUrl = nextUrl; nextUrl = null; nextFor = null
                    positionMs = pos.relMs ?: 0; durationMs = pos.durationMs ?: C.TIME_UNSET
                    playlistCache = null
                    queueNext()
                }
            }
            RemoteStep.ENDED -> if (since > 3_000) {
                val n = Upnp.nextIndex(index, entries.size, repeat)
                if (n == null) {
                    playbackState = Player.STATE_ENDED
                    sawPlaying = false
                    if (durationMs != C.TIME_UNSET) positionMs = durationMs
                } else {
                    load(n, 0, true)
                }
            }
            RemoteStep.NOTHING -> Unit
        }
        invalidateState()
    }

    private fun failed(t: Throwable) {
        if (t is RejectedUri) {
            // La canción no le vale (formato): aviso y a la siguiente; con 3 seguidas, se deja.
            Notice.fromService(media.context, Res.str(R.string.cast_rejected, device.name))
            val n = Upnp.nextIndex(index, entries.size, Player.REPEAT_MODE_OFF)
            if (n != null && ++failures < 3) { load(n, 0, playWhenReady); invalidateState(); return }
        }
        lost()
    }

    private fun lost() {
        if (closed) return
        playbackState = Player.STATE_IDLE
        error = PlaybackException(Res.str(R.string.cast_lost, device.name), null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        invalidateState()
        onLost(this)
    }

    // ---------- Hilos ----------

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** [work] en el hilo del aparato; [then] después, en el principal. El futuro acaba tras [then]. */
    private fun <T> onIo(work: () -> T, then: (Result<T>) -> Unit = {}): ListenableFuture<*> {
        val f = SettableFuture.create<Any>()
        try {
            io.execute {
                val r = runCatching(work)
                main.post {
                    try { if (!closed) then(r) } finally { f.set(Unit) }
                }
            }
        } catch (_: RejectedExecutionException) {
            f.set(Unit)
        }
        return f
    }

    private companion object {
        /** Pasos del volumen del sistema: cada tecla sube o baja un 4 % del aparato. */
        const val VOLUME_STEPS = 25
    }
}
