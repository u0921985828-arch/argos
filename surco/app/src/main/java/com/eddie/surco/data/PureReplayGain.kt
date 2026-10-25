package com.eddie.surco.data

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField

/** Ganancias ReplayGain (dB) de pista y de álbum, o null si el archivo no las trae. */
data class ReplayGain(val trackDb: Float?, val albumDb: Float?)

/**
 * ReplayGain no tiene campo propio en jaudiotagger: va en un TXXX (ID3), en un
 * comentario Vorbis (FLAC/Ogg/Opus) o en un campo libre de iTunes (MP4/M4A).
 */
fun readReplayGain(tag: Tag): ReplayGain {
    var track: Float? = null
    var album: Float? = null
    var r128Track: Float? = null
    var r128Album: Float? = null
    val it = tag.fields
    while (it.hasNext()) {
        val f = it.next()
        val (name, value) = when {
            f is AbstractID3v2Frame && f.body is FrameBodyTXXX -> (f.body as FrameBodyTXXX).let { b -> b.description to b.text }
            f is Mp4TagReverseDnsField -> f.descriptor to f.content
            f is TagTextField -> f.id to f.content
            else -> continue
        }
        when (name.orEmpty().trim().uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> track = track ?: parseGainDb(value.orEmpty())
            "REPLAYGAIN_ALBUM_GAIN" -> album = album ?: parseGainDb(value.orEmpty())
            "R128_TRACK_GAIN" -> r128Track = r128ToDb(value.orEmpty())
            "R128_ALBUM_GAIN" -> r128Album = r128ToDb(value.orEmpty())
        }
    }
    // Opus usa R128 (RFC 7845) si no trae REPLAYGAIN_*.
    return ReplayGain(track ?: r128Track, album ?: r128Album)
}

/**
 * R128_*_GAIN: entero Q7.8 respecto a −23 LUFS. ReplayGain 2 mide respecto a −18 LUFS, así que
 * en su escala son 5 dB más: «-2560» (−10 dB a −23 LUFS) → −5 dB.
 */
fun r128ToDb(raw: String): Float? = raw.trim().toIntOrNull()?.let { it / 256f + 5f }?.takeIf { it in -60f..60f }
