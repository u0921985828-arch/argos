package com.eddie.surco.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/** Una canción con todas las etiquetas que se han podido leer del archivo. */
data class Track(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: Long,
    val genre: String,
    val year: Int,
    val composer: String,
    val trackNo: Int,
    val discNo: Int,
    val durationMs: Long,
    val bitrate: Int,      // kbps
    val sampleRate: Int,   // Hz
    val format: String,
    val size: Long,
    val dateAdded: Long,   // segundos
    val dateModified: Long,
    val enriched: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val folder: String get() = File(path).parentFile?.name ?: ""
    val artKey: Long get() = if (albumId > 0) albumId else -id

    fun x(key: String): String = extra[key].orEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("path", path); put("title", title); put("artist", artist)
        put("album", album); put("albumArtist", albumArtist); put("albumId", albumId)
        put("genre", genre); put("year", year); put("composer", composer)
        put("trackNo", trackNo); put("discNo", discNo); put("durationMs", durationMs)
        put("bitrate", bitrate); put("sampleRate", sampleRate); put("format", format)
        put("size", size); put("dateAdded", dateAdded); put("dateModified", dateModified)
        put("enriched", enriched); put("extra", JSONObject(extra as Map<*, *>))
    }

    companion object {
        fun fromJson(o: JSONObject): Track {
            val ex = o.optJSONObject("extra")
            val map = buildMap { ex?.keys()?.forEach { k -> put(k, ex.optString(k)) } }
            return Track(
                o.getLong("id"), o.optString("path"), o.optString("title"), o.optString("artist"),
                o.optString("album"), o.optString("albumArtist"), o.optLong("albumId"),
                o.optString("genre"), o.optInt("year"), o.optString("composer"),
                o.optInt("trackNo"), o.optInt("discNo"), o.optLong("durationMs"),
                o.optInt("bitrate"), o.optInt("sampleRate"), o.optString("format"),
                o.optLong("size"), o.optLong("dateAdded"), o.optLong("dateModified"),
                o.optBoolean("enriched"), map,
            )
        }
    }
}
