package com.eddie.surco.player

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.eddie.surco.data.Track
import com.google.common.util.concurrent.ListenableFuture

/** Conecta la interfaz con el servicio de reproducción. */
class PlayerConnection(ctx: Context) {
    private val future: ListenableFuture<MediaController>
    var controller: MediaController? by mutableStateOf(null); private set
    var currentId by mutableStateOf<Long?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF); private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                controller = c; c.addListener(listener); sync(c)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync(p: Player) {
        currentId = p.currentMediaItem?.mediaId?.toLongOrNull()
        isPlaying = p.isPlaying
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val durationMs: Long get() = controller?.duration?.takeIf { it > 0 } ?: 0

    fun play(list: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        val idx = if (shuffled) list.indices.random() else start.coerceIn(list.indices)
        c.setMediaItems(list.map { it.toMediaItem() }, idx, 0)
        c.prepare(); c.play()
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

    private fun Track.toMediaItem(): MediaItem {
        val art = if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title).setArtist(artist).setAlbumTitle(album).setAlbumArtist(albumArtist)
                    .setGenre(genre).setArtworkUri(art)
                    .apply { if (year > 0) setRecordingYear(year) }
                    .build()
            ).build()
    }
}
