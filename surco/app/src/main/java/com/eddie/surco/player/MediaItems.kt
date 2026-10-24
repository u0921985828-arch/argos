package com.eddie.surco.player

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.eddie.surco.data.Track

const val EXTRA_RG_TRACK = "rg_track"
const val EXTRA_RG_ALBUM = "rg_album"

/** La canción como elemento de la cola. La URI va también en requestMetadata: el MediaController no la transmite. */
fun Track.toMediaItem(): MediaItem {
    val art = if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
    val extras = Bundle().apply {
        x("rg_track").toFloatOrNull()?.let { putFloat(EXTRA_RG_TRACK, it) }
        x("rg_album").toFloatOrNull()?.let { putFloat(EXTRA_RG_ALBUM, it) }
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
