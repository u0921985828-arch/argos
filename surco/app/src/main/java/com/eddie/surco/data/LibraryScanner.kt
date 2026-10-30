package com.eddie.surco.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.reference.GenreTypes
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

class LibraryScanner(private val ctx: Context) {

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** Lectura rápida desde la base de datos multimedia de Android. */
    fun queryMediaStore(): List<Track> {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val modern = Build.VERSION.SDK_INT >= 30
        if (modern) cols += listOf(
            MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.BITRATE,
        )
        val out = ArrayList<Track>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            fun s(n: String) = c.getColumnIndex(n).let { if (it < 0) "" else cleanText(c.getString(it).orEmpty()) }
            fun l(n: String) = c.getColumnIndex(n).let { if (it < 0) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                val trk = l(MediaStore.Audio.Media.TRACK).toInt()
                val path = s(MediaStore.Audio.Media.DATA)
                out += Track(
                    id = l(MediaStore.Audio.Media._ID),
                    path = path,
                    title = s(MediaStore.Audio.Media.TITLE).ifBlank { File(path).nameWithoutExtension },
                    artist = clean(s(MediaStore.Audio.Media.ARTIST)),
                    album = clean(s(MediaStore.Audio.Media.ALBUM)),
                    albumArtist = if (modern) clean(s(MediaStore.Audio.Media.ALBUM_ARTIST)) else "",
                    albumId = l(MediaStore.Audio.Media.ALBUM_ID),
                    genre = if (modern) cleanGenre(s(MediaStore.Audio.Media.GENRE)) else "",
                    year = l(MediaStore.Audio.Media.YEAR).toInt(),
                    composer = clean(s(MediaStore.Audio.Media.COMPOSER)),
                    trackNo = if (trk >= 1000) trk % 1000 else trk,
                    discNo = if (trk >= 1000) trk / 1000 else 0,
                    durationMs = l(MediaStore.Audio.Media.DURATION),
                    bitrate = if (modern) (l(MediaStore.Audio.Media.BITRATE) / 1000).toInt() else 0,
                    sampleRate = 0,
                    format = formatFromMime(s(MediaStore.Audio.Media.MIME_TYPE), path),
                    size = l(MediaStore.Audio.Media.SIZE),
                    dateAdded = l(MediaStore.Audio.Media.DATE_ADDED),
                    dateModified = l(MediaStore.Audio.Media.DATE_MODIFIED),
                )
            }
        }
        return out
    }

    /** Lectura profunda del archivo: todas las etiquetas que soporte el formato. */
    fun enrich(t: Track): Track {
        val viaTagger = runCatching { if (t.path.endsWith(".opus", true)) readOpus(t) else readWithTagger(t) }.getOrNull()
        val base = viaTagger ?: runCatching { readWithRetriever(t) }.getOrDefault(t)
        return base.copy(enriched = true)
    }

    private fun readWithTagger(t: Track): Track? {
        val f = File(t.path)
        if (!f.canRead()) return null
        val af = AudioFileIO.read(f)
        val h = af.audioHeader
        val tag: Tag? = af.tag
        // Los WAV (bloque INFO) traen el terminador nulo dentro del texto: «Prueba\u0000».
        fun g(k: FieldKey): String = try { tag?.getFirst(k)?.let { cleanText(it) }.orEmpty() } catch (_: Throwable) { "" }
        fun all(k: FieldKey): String = try {
            tag?.getAll(k)?.map { cleanText(it) }?.filter { it.isNotEmpty() }?.distinct()?.joinToString("; ").orEmpty()
        } catch (_: Throwable) { g(k) }

        val extra = LinkedHashMap<String, String>()
        fun put(key: String, v: String) { if (v.isNotBlank()) extra[key] = v }
        put("bpm", g(FieldKey.BPM))
        put("key", g(FieldKey.KEY))
        put("mood", all(FieldKey.MOOD))
        put("label", g(FieldKey.RECORD_LABEL))
        put("lyricist", all(FieldKey.LYRICIST))
        put("producer", all(FieldKey.PRODUCER))
        put("remixer", all(FieldKey.REMIXER))
        put("conductor", g(FieldKey.CONDUCTOR))
        put("grouping", g(FieldKey.GROUPING))
        put("language", g(FieldKey.LANGUAGE))
        put("country", g(FieldKey.COUNTRY))
        put("media", g(FieldKey.MEDIA))
        put("encoder", g(FieldKey.ENCODER))
        put("comment", g(FieldKey.COMMENT))
        put("isrc", g(FieldKey.ISRC))
        put("catalog", g(FieldKey.CATALOG_NO))
        put("original_year", g(FieldKey.ORIGINAL_YEAR).take(4))
        put("rating", g(FieldKey.RATING))
        put("subtitle", g(FieldKey.SUBTITLE))
        put("disc_total", g(FieldKey.DISC_TOTAL))
        put("track_total", g(FieldKey.TRACK_TOTAL))
        if (g(FieldKey.LYRICS).isNotBlank()) extra["lyrics"] = "Sí"
        tag?.let { tg ->
            runCatching { readReplayGain(tg) }.getOrNull()?.let { rg ->
                rg.trackDb?.let { extra["rg_track"] = "%.2f".format(Locale.ROOT, it) }
                rg.albumDb?.let { extra["rg_album"] = "%.2f".format(Locale.ROOT, it) }
            }
        }
        runCatching { put("channels", h.channels.orEmpty()) }
        runCatching { if (h.bitsPerSample > 0) put("bits", "${h.bitsPerSample} bits") }
        runCatching { put("codec", h.encodingType.orEmpty()) }
        runCatching { put("lossless", if (h.isLossless) "Sí" else "No") }

        val year = g(FieldKey.YEAR).take(4).toIntOrNull() ?: 0
        return t.copy(
            title = g(FieldKey.TITLE).ifBlank { t.title },
            artist = all(FieldKey.ARTIST).ifBlank { t.artist },
            album = g(FieldKey.ALBUM).ifBlank { t.album },
            albumArtist = g(FieldKey.ALBUM_ARTIST).ifBlank { t.albumArtist },
            genre = cleanGenre(all(FieldKey.GENRE)).ifBlank { t.genre },
            year = if (year > 0) year else t.year,
            composer = all(FieldKey.COMPOSER).ifBlank { t.composer },
            trackNo = g(FieldKey.TRACK).substringBefore('/').toIntOrNull() ?: t.trackNo,
            discNo = g(FieldKey.DISC_NO).substringBefore('/').toIntOrNull() ?: t.discNo,
            bitrate = (runCatching { h.bitRateAsNumber.toInt() }.getOrNull() ?: 0).takeIf { it > 0 } ?: t.bitrate,
            sampleRate = runCatching { h.sampleRateAsNumber }.getOrDefault(0),
            format = runCatching { h.format }.getOrNull()?.takeIf { it.isNotBlank() }?.let { shortFormat(it) } ?: t.format,
            extra = extra,
        )
    }

    /** Opus: jaudiotagger 3.0.1 no lo abre; se leen sus comentarios (formato Vorbis) a mano. */
    private fun readOpus(t: Track): Track? {
        val f = File(t.path)
        if (!f.canRead()) return null
        val c = f.inputStream().buffered().use { readOpusTags(it) } ?: return null
        fun g(vararg k: String) = k.firstNotNullOfOrNull { key -> c[key]?.map { cleanText(it) }?.firstOrNull { it.isNotEmpty() } }.orEmpty()
        fun all(vararg k: String) = k.flatMap { key -> c[key].orEmpty() }.map { cleanText(it) }.filter { it.isNotEmpty() }.distinct().joinToString("; ")
        val extra = LinkedHashMap<String, String>()
        fun put(key: String, v: String) { if (v.isNotBlank()) extra[key] = v }
        put("bpm", g("BPM")); put("key", g("INITIALKEY", "KEY")); put("mood", all("MOOD"))
        put("label", g("LABEL", "ORGANIZATION", "PUBLISHER")); put("lyricist", all("LYRICIST")); put("producer", all("PRODUCER"))
        put("remixer", all("REMIXER")); put("conductor", g("CONDUCTOR")); put("grouping", g("GROUPING", "CONTENTGROUP"))
        put("language", g("LANGUAGE")); put("country", g("RELEASECOUNTRY")); put("media", g("MEDIA"))
        put("encoder", g("ENCODER")); put("comment", g("COMMENT", "DESCRIPTION")); put("isrc", g("ISRC"))
        put("catalog", g("CATALOGNUMBER")); put("original_year", g("ORIGINALDATE", "ORIGINALYEAR").take(4))
        if (g("LYRICS", "UNSYNCEDLYRICS").isNotBlank()) extra["lyrics"] = "Sí"
        put("codec", "Opus"); put("lossless", "No")
        val rg = replayGainFromComments(c)
        rg.trackDb?.let { extra["rg_track"] = "%.2f".format(Locale.ROOT, it) }
        rg.albumDb?.let { extra["rg_album"] = "%.2f".format(Locale.ROOT, it) }
        val year = g("DATE", "YEAR").take(4).toIntOrNull() ?: 0
        return t.copy(
            title = g("TITLE").ifBlank { t.title },
            artist = all("ARTIST").ifBlank { t.artist },
            album = g("ALBUM").ifBlank { t.album },
            albumArtist = g("ALBUMARTIST", "ALBUM ARTIST").ifBlank { t.albumArtist },
            genre = cleanGenre(all("GENRE")).ifBlank { t.genre },
            year = if (year > 0) year else t.year,
            composer = all("COMPOSER").ifBlank { t.composer },
            trackNo = g("TRACKNUMBER").substringBefore('/').toIntOrNull() ?: t.trackNo,
            discNo = g("DISCNUMBER").substringBefore('/').toIntOrNull() ?: t.discNo,
            sampleRate = 48_000,
            format = "OPUS",
            extra = extra,
        )
    }

    private fun readWithRetriever(t: Track): Track {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, t.uri)
            fun m(k: Int) = r.extractMetadata(k)?.trim().orEmpty()
            val sr = if (Build.VERSION.SDK_INT >= 31) m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: 0 else 0
            val bits = if (Build.VERSION.SDK_INT >= 31) m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE) else ""
            return t.copy(
                genre = t.genre.ifBlank { cleanGenre(m(MediaMetadataRetriever.METADATA_KEY_GENRE)) },
                albumArtist = t.albumArtist.ifBlank { m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                composer = t.composer.ifBlank { m(MediaMetadataRetriever.METADATA_KEY_COMPOSER) },
                year = if (t.year > 0) t.year else m(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
                discNo = if (t.discNo > 0) t.discNo else m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                bitrate = if (t.bitrate > 0) t.bitrate else (m(MediaMetadataRetriever.METADATA_KEY_BITRATE).toIntOrNull() ?: 0) / 1000,
                sampleRate = sr,
                extra = if (bits.isNotBlank() && bits != "0") mapOf("bits" to "$bits bits") else emptyMap(),
            )
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Letra de la canción: primero un .lrc con el mismo nombre al lado (en Android 13+ solo se
     * puede leer si el sistema lo deja, así que es un extra), después la etiqueta incrustada.
     */
    fun loadLyrics(t: Track, online: Boolean = false): Lyrics? =
        localLyrics(t) ?: if (online) onlineLyrics(t) else null

    /**
     * LRCLIB, con caché en disco: lo encontrado se guarda para siempre y «no está» se recuerda
     * 7 días (sin eso, cada vez que se abre la letra de una canción rara se preguntaría otra vez).
     */
    private fun onlineLyrics(t: Track): Lyrics? {
        val dur = (t.durationMs / 1000).toInt()
        if (t.artist.isBlank() || t.title.isBlank() || dur <= 0) return null
        val dir = File(ctx.filesDir, "letras").apply { mkdirs() }
        val key = java.security.MessageDigest.getInstance("SHA-1")
            .digest("${t.artist}|${t.title}|$dur".lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, "$key.lrc")
        if (f.exists() && (f.length() > 0 || System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L)) {
            return parseLyrics(runCatching { f.readText() }.getOrDefault(""), "LRCLIB")
        }
        // Sin red no se guarda nada: se volverá a probar la próxima vez.
        val r = fetchLrclib(t.artist, t.title, t.album, dur, ::httpGet)
        if (r != null) runCatching { f.writeText(r) }
        return r?.let { parseLyrics(it, "LRCLIB") }
    }

    private fun localLyrics(t: Track): Lyrics? {
        val audio = File(t.path)
        val lrc = File(audio.parentFile, audio.nameWithoutExtension + ".lrc")
        runCatching { if (lrc.canRead()) parseLyrics(lrc.readText(), "archivo .lrc")?.let { return it } }
        return runCatching {
            if (t.path.endsWith(".opus", true)) {
                val c = audio.inputStream().buffered().use { readOpusTags(it) } ?: return null
                return parseLyrics((c["LYRICS"] ?: c["UNSYNCEDLYRICS"])?.firstOrNull().orEmpty(), "etiqueta del archivo")
            }
            val tag = AudioFileIO.read(audio).tag ?: return null
            parseLyrics(tag.getFirst(FieldKey.LYRICS).orEmpty(), "etiqueta del archivo")
        }.getOrNull()
    }

    // ----- caché en disco -----
    // «2»: desde Surco 2 la lectura profunda guarda también ReplayGain. Con otro nombre, la
    // caché de la versión anterior no vale y todo se vuelve a leer una vez.
    private val cacheFile get() = File(ctx.filesDir, "library2.json")

    fun loadCache(): List<Track> = runCatching {
        File(ctx.filesDir, "library.json").takeIf { it.exists() }?.delete()
        val arr = JSONArray(Disk.read(cacheFile) ?: return emptyList())
        List(arr.length()) { Track.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveCache(list: List<Track>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        Disk.write(cacheFile, arr.toString())
    }

    companion object {
        private val ID3_NUM = Regex("""^\(?(\d{1,3})\)?$""")

        /** Sin nulos ni espacios de sobra. */
        fun cleanText(s: String) = s.replace("\u0000", "").trim()

        fun clean(s: String) = cleanText(s).let { if (it.equals("<unknown>", true)) "" else it }

        /** Convierte géneros ID3v1 numéricos ("(17)", "17") en su nombre. */
        fun cleanGenre(raw: String): String = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { g ->
                ID3_NUM.matchEntire(g)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { runCatching { GenreTypes.getInstanceOf().getValueForId(it) }.getOrNull() } ?: g
            }.distinct().joinToString("; ")

        fun formatFromMime(mime: String, path: String): String {
            val ext = path.substringAfterLast('.', "").uppercase()
            return when {
                ext.isNotEmpty() && ext.length <= 4 -> ext
                else -> mime.substringAfter('/').uppercase()
            }
        }

        private fun shortFormat(f: String): String {
            val u = f.uppercase()
            return when {
                "FLAC" in u -> "FLAC"; "MPEG" in u || "MP3" in u -> "MP3"; "AAC" in u -> "AAC"
                "ALAC" in u -> "ALAC"; "VORBIS" in u || "OGG" in u -> "OGG"; "OPUS" in u -> "OPUS"
                "WAV" in u -> "WAV"; "AIFF" in u -> "AIFF"; "WMA" in u || "ASF" in u -> "WMA"
                "DSF" in u || "DSD" in u -> "DSD"; "MP4" in u || "M4A" in u -> "AAC"
                else -> f.take(12)
            }
        }
    }
}
