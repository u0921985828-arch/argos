package com.eddie.surco.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.google.common.util.concurrent.ListenableFuture

/** Un hueco de la cola: [index] es la posición en la lista del reproductor. */
data class QueueEntry(val index: Int, val trackId: Long)

/** Conecta la interfaz con el servicio de reproducción. */
class PlayerConnection(ctx: Context) {
    private val future: ListenableFuture<MediaController>
    var controller: MediaController? by mutableStateOf(null); private set
    var currentId by mutableStateOf<Long?>(null); private set
    var currentIndex by mutableIntStateOf(C.INDEX_UNSET); private set
    var isPlaying by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF); private set
    var speed by mutableFloatStateOf(1f); private set
    /** La cola en el orden en que va a sonar (con aleatorio, el orden aleatorio). */
    var queue by mutableStateOf<List<QueueEntry>>(emptyList()); private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player, events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED))
        }
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                controller = c; c.addListener(listener); sync(c, true)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync(p: Player, queueChanged: Boolean) {
        currentId = p.currentMediaItem?.mediaId?.toLongOrNull()
        currentIndex = p.currentMediaItemIndex
        isPlaying = p.isPlaying
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        speed = p.playbackParameters.speed
        if (queueChanged) queue = buildQueue(p)
    }

    private fun buildQueue(p: Player): List<QueueEntry> {
        val n = p.mediaItemCount
        if (n == 0) return emptyList()
        val tl = p.currentTimeline
        val out = ArrayList<QueueEntry>(n)
        var i = tl.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < n) {
            out += QueueEntry(i, p.getMediaItemAt(i).mediaId.toLongOrNull() ?: -1L)
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return out
    }

    /** Lo que suena según el propio reproductor, por si ya no está en la biblioteca (borrada mientras suena). */
    fun currentAsTrack(): Track? {
        val item = controller?.currentMediaItem ?: return null
        val id = item.mediaId.toLongOrNull() ?: return null
        val m = item.mediaMetadata
        return Track(
            id = id, path = "", title = m.title?.toString().orEmpty(), artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString().orEmpty(), albumArtist = m.albumArtist?.toString().orEmpty(), albumId = 0,
            genre = m.genre?.toString().orEmpty(), year = m.recordingYear ?: 0, composer = "", trackNo = 0, discNo = 0,
            durationMs = 0, bitrate = 0, sampleRate = 0, format = "", size = 0, dateAdded = 0, dateModified = 0,
        )
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val durationMs: Long get() = controller?.duration?.takeIf { it > 0 } ?: 0

    fun play(list: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        val items = list.map { it.toMediaItem() }
        if (shuffled) {
            // Con resetPosition empieza por la primera del orden aleatorio: suena la lista entera.
            c.shuffleModeEnabled = true
            c.setMediaItems(items, true)
        } else {
            c.shuffleModeEnabled = false
            c.setMediaItems(items, start.coerceIn(list.indices), 0)
        }
        c.prepare(); c.play()
    }

    /** Detrás de la que suena. */
    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(tracks, 0)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
    }

    /** Al final de la cola. */
    fun enqueue(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(tracks, 0)
        c.addMediaItems(tracks.map { it.toMediaItem() }) // sin índice: «a la cola» también en aleatorio
    }

    fun jumpTo(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.play() } }
    fun removeAt(index: Int) { controller?.removeMediaItem(index) }
    fun move(from: Int, to: Int) { if (from != to) controller?.moveMediaItem(from, to) }

    /** Quita todo menos lo que suena. */
    fun clearUpcoming() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
    }

    /** Al abrir la app sin nada en la cola, deja preparada la última (en pausa). */
    fun restoreIfEmpty(byId: Map<Long, Track>) {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val q = UserStore.state.value.queue
        val r = restoreQueue(q, byId) ?: return
        c.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs)
        c.repeatMode = q.repeat
        c.shuffleModeEnabled = q.shuffle
        c.prepare()
    }

    /** «Seguir escuchando»: la cola de la última vez donde se quedó, sonando. */
    fun resume(byId: Map<Long, Track>) {
        val c = controller ?: return
        restoreIfEmpty(byId)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun next() { controller?.seekToNext() }
    fun prev() { controller?.seekToPrevious() }
    fun seek(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }
}
