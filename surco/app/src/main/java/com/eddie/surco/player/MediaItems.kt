package com.eddie.surco.player

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.eddie.surco.data.SavedQueue
import com.eddie.surco.data.Track
import com.eddie.surco.data.keptQueue

const val EXTRA_RG_TRACK = "rg_track"
const val EXTRA_RG_ALBUM = "rg_album"
/** Carpeta del archivo: con el álbum dice si dos canciones son del mismo disco (fundido entre canciones). */
const val EXTRA_DIR = "dir"
/** Para pedir el mezclador del DAC USB antes de que suene: frecuencia, bits, formato y canales del archivo. */
const val EXTRA_SR = "sr"
const val EXTRA_BITS = "bits"
const val EXTRA_FORMAT = "format"
const val EXTRA_CHANNELS = "channels"

/** La canción como elemento de la cola. La URI va también en requestMetadata: el MediaController no la transmite. */
fun Track.toMediaItem(): MediaItem {
    val art = if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
    val extras = Bundle().apply {
        x("rg_track").toFloatOrNull()?.let { putFloat(EXTRA_RG_TRACK, it) }
        x("rg_album").toFloatOrNull()?.let { putFloat(EXTRA_RG_ALBUM, it) }
        putString(EXTRA_DIR, dir)
        putInt(EXTRA_SR, sampleRate)
        putInt(EXTRA_BITS, x("bits").take(2).trim().toIntOrNull() ?: 16)
        putString(EXTRA_FORMAT, format)
        // jaudiotagger da «2», «Stereo», «Joint Stereo», «1», «Mono»…
        putInt(EXTRA_CHANNELS, x("channels").let { c -> c.toIntOrNull() ?: if (c.contains("mono", true)) 1 else 2 })
    }
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title).setArtist(artist).setAlbumTitle(album).setAlbumArtist(albumArtist)
                .setGenre(genre).setArtworkUri(art)
                .setExtras(extras)
                .apply { if (year > 0) setRecordingYear(year) }
                .build()
        ).build()
}

/**
 * La cola guardada lista para el reproductor. Las canciones que ya no existen se quitan, y el
 * índice y la posición se recolocan sobre lo que queda (si la que sonaba ya no está, se empieza
 * por la siguiente desde el principio).
 */
@OptIn(UnstableApi::class)
fun restoreQueue(q: SavedQueue, byId: Map<Long, Track>): MediaSession.MediaItemsWithStartPosition? {
    val (kept, start, same) = keptQueue(q.ids, q.index) { it in byId } ?: return null
    return MediaSession.MediaItemsWithStartPosition(
        kept.map { byId.getValue(q.ids[it]).toMediaItem() }, start, if (same) q.positionMs else 0L,
    )
}
