package com.eddie.surco.data

import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * Cada etiqueta por la que se puede organizar la biblioteca. [label] y [group] son lo que se ve,
 * en el idioma de la app ([Texts]); el identificador que se guarda es [name].
 */
enum class TagField(private val labelKey: String, private val labelEs: String, private val groupKey: String) {
    ARTIST("data_field_artist", "Artista", G_PEOPLE),
    ALBUM_ARTIST("data_field_album_artist", "Artista del álbum", G_PEOPLE),
    COMPOSER("data_field_composer", "Compositor", G_PEOPLE),
    LYRICIST("data_field_lyricist", "Letrista", G_PEOPLE),
    PRODUCER("data_field_producer", "Productor", G_PEOPLE),
    REMIXER("data_field_remixer", "Remezclador", G_PEOPLE),
    CONDUCTOR("data_field_conductor", "Director", G_PEOPLE),
    ALBUM("data_field_album", "Álbum", G_WORK),
    GENRE("data_field_genre", "Género", G_WORK),
    MOOD("data_field_mood", "Estado de ánimo", G_WORK),
    GROUPING("data_field_grouping", "Agrupación", G_WORK),
    LABEL("data_field_label", "Sello", G_WORK),
    MEDIA("data_field_media", "Soporte original", G_WORK),
    LANGUAGE("data_field_language", "Idioma", G_WORK),
    COUNTRY("data_field_country", "País", G_WORK),
    YEAR("data_field_year", "Año", G_TIME),
    DECADE("data_field_decade", "Década", G_TIME),
    ORIGINAL_YEAR("data_field_original_year", "Año original", G_TIME),
    DURATION("data_field_duration", "Duración", G_TIME),
    BPM("data_field_bpm", "BPM", G_RHYTHM),
    KEY("data_field_key", "Tonalidad", G_RHYTHM),
    ADDED("data_field_added", "Fecha de añadido", G_TIME),
    FORMAT("data_field_format", "Formato", G_FILE),
    QUALITY("data_field_quality", "Calidad", G_FILE),
    BITRATE("data_field_bitrate", "Bitrate", G_FILE),
    SAMPLE_RATE("data_field_sample_rate", "Frecuencia de muestreo", G_FILE),
    BIT_DEPTH("data_field_bit_depth", "Profundidad de bits", G_FILE),
    CHANNELS("data_field_channels", "Canales", G_FILE),
    ENCODER("data_field_encoder", "Codificador", G_FILE),
    FOLDER("data_field_folder", "Carpeta", G_FILE),
    ;

    val label: String get() = Texts.t(labelKey, labelEs)
    val group: String get() = Texts.t(groupKey, groupEs(groupKey))
}

private const val G_PEOPLE = "data_group_people"
private const val G_WORK = "data_group_work"
private const val G_TIME = "data_group_time"
private const val G_RHYTHM = "data_group_rhythm"
private const val G_FILE = "data_group_file"

private fun groupEs(key: String): String = when (key) {
    G_PEOPLE -> "Personas"
    G_WORK -> "Obra"
    G_TIME -> "Tiempo"
    G_RHYTHM -> "Ritmo y tono"
    else -> "Archivo"
}

/** key = identificador único del grupo, order = posición en listas numéricas. */
data class Bucket(val key: String, val label: String, val order: Long = 0)

data class Group(val bucket: Bucket, val tracks: List<Track>) {
    val totalMs: Long get() = tracks.sumOf { it.durationMs }
}

private val ARTIST_SEP = Regex("""\s*(;|\u0000| / |\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+)\s*""", RegexOption.IGNORE_CASE)
private val GENRE_SEP = Regex("""\s*[;/,|]\s*""")
private const val UNKNOWN_KEY = "\u0000"
private const val NO_YEAR = 99_999L

/**
 * ¿Es un cubo de relleno («Desconocido», «Sin año»), no un valor de verdad de la etiqueta? Por su
 * clave, no por el texto: el texto cambia con el idioma.
 */
val Bucket.isPlaceholder: Boolean get() = key == UNKNOWN_KEY || key == "#$NO_YEAR"

private fun unknownLabel() = Texts.t("data_unknown", "Desconocido")
private fun noYearLabel() = Texts.t("data_no_year", "Sin año")

private fun plain(v: String, unknown: Boolean = false): List<Bucket> {
    val s = v.trim()
    return when {
        s.isNotEmpty() -> listOf(Bucket(s.lowercase(), s))
        unknown -> listOf(Bucket(UNKNOWN_KEY, unknownLabel(), Long.MAX_VALUE))
        else -> emptyList()
    }
}

private fun multi(v: String, sep: Regex, unknown: Boolean = false): List<Bucket> {
    val parts = v.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return plain("", unknown)
    return parts.distinctBy { it.lowercase() }.map { Bucket(it.lowercase(), it) }
}

private fun num(label: String, order: Long) = listOf(Bucket("#$order", label, order))

/** Nombres de los meses en el idioma de la app (se guardan los del último idioma pedido). */
@Volatile private var months: Pair<Locale, Array<String>>? = null

private fun monthNames(): Array<String> {
    val loc = Texts.locale()
    months?.let { (l, m) -> if (l == loc) return m }
    return DateFormatSymbols(loc).months.also { months = loc to it }
}

/** «44,1» o «44.1» (según el idioma), sin «,0»: «48». */
private fun khz(hz: Int): String {
    val loc = Texts.locale()
    val sep = DecimalFormatSymbols.getInstance(loc).decimalSeparator
    return "%.1f".format(loc, hz / 1000f).removeSuffix("${sep}0")
}

fun TagField.buckets(t: Track): List<Bucket> = when (this) {
    TagField.ARTIST -> multi(t.artist, ARTIST_SEP, unknown = true)
    TagField.ALBUM_ARTIST -> plain(t.albumArtist.ifBlank { t.artist }, unknown = true)
    TagField.COMPOSER -> multi(t.composer, ARTIST_SEP)
    TagField.LYRICIST -> multi(t.x("lyricist"), ARTIST_SEP)
    TagField.PRODUCER -> multi(t.x("producer"), ARTIST_SEP)
    TagField.REMIXER -> multi(t.x("remixer"), ARTIST_SEP)
    TagField.CONDUCTOR -> plain(t.x("conductor"))
    TagField.ALBUM -> if (t.album.isBlank()) plain("", true)
        else listOf(Bucket("${t.album.lowercase()}|${t.albumArtist.lowercase()}", t.album))
    TagField.GENRE -> multi(t.genre, GENRE_SEP, unknown = true)
    TagField.MOOD -> multi(t.x("mood"), GENRE_SEP)
    TagField.GROUPING -> plain(t.x("grouping"))
    TagField.LABEL -> plain(t.x("label"))
    TagField.MEDIA -> plain(t.x("media"))
    TagField.LANGUAGE -> plain(t.x("language"))
    TagField.COUNTRY -> plain(t.x("country"))
    TagField.YEAR -> if (t.year > 0) num("${t.year}", t.year.toLong()) else num(noYearLabel(), NO_YEAR)
    TagField.DECADE -> if (t.year > 0) (t.year / 10 * 10).let { num("$it – ${it + 9}", it.toLong()) } else num(noYearLabel(), NO_YEAR)
    TagField.ORIGINAL_YEAR -> t.x("original_year").toIntOrNull()?.let { num("$it", it.toLong()) } ?: emptyList()
    TagField.DURATION -> durationBucket(t.durationMs)
    TagField.BPM -> t.x("bpm").replace(',', '.').toFloatOrNull()?.toInt()?.takeIf { it > 0 }
        ?.let { val b = it / 10 * 10; num("$b – ${b + 9} BPM", b.toLong()) } ?: emptyList()
    // Tonalidad normalizada («Am», «A minor» y «8A» son la misma) y en el orden de la rueda Camelot.
    TagField.KEY -> parseKey(t.x("key"))?.let { num(it.label, it.order.toLong()) } ?: plain(t.x("key"))
    TagField.ADDED -> if (t.dateAdded > 0) {
        val c = Calendar.getInstance().apply { timeInMillis = t.dateAdded * 1000 }
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH)
        num("${monthNames()[m].replaceFirstChar { it.uppercase() }} $y", -(y * 100L + m)) // más recientes primero
    } else emptyList()
    TagField.FORMAT -> plain(t.format.uppercase())
    TagField.QUALITY -> {
        val lossless = t.x("lossless") == "Sí" || t.format.uppercase() in setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "DSD", "DSF", "WV")
        when {
            lossless && (t.sampleRate > 48_000 || (t.x("bits").take(2).toIntOrNull() ?: 0) > 16) -> num(Texts.t("data_quality_hires", "Alta resolución"), 0)
            lossless -> num(Texts.t("data_quality_lossless", "Sin pérdida"), 1)
            t.bitrate >= 256 -> num(Texts.t("data_quality_lossy_high", "Con pérdida · alta"), 2)
            t.bitrate in 1..255 -> num(Texts.t("data_quality_lossy_low", "Con pérdida · media/baja"), 3)
            else -> emptyList()
        }
    }
    TagField.BITRATE -> bitrateBucket(t.bitrate)
    TagField.SAMPLE_RATE -> if (t.sampleRate > 0) num("${khz(t.sampleRate)} kHz", t.sampleRate.toLong()) else emptyList()
    TagField.BIT_DEPTH -> t.x("bits").take(2).trim().toIntOrNull()?.let { num("$it bits", it.toLong()) } ?: emptyList()
    TagField.CHANNELS -> channelBucket(t.x("channels"))
    TagField.ENCODER -> plain(t.x("encoder"))
    TagField.FOLDER -> plain(t.folder)
}

private fun durationBucket(ms: Long): List<Bucket> {
    if (ms <= 0) return emptyList()
    val min = ms / 60_000.0
    val edges = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 7.0, 10.0, 20.0)
    val i = edges.indexOfFirst { min < it }
    val label = when (i) {
        0 -> Texts.t("data_duration_under_1", "Menos de 1 min")
        -1 -> Texts.t("data_duration_over_20", "Más de 20 min")
        else -> "${fmtEdge(edges[i - 1])} – ${fmtEdge(edges[i])} min"
    }
    return num(label, if (i == -1) 99 else i.toLong())
}

private fun fmtEdge(d: Double) = d.toInt().toString()

private fun bitrateBucket(k: Int): List<Bucket> {
    if (k <= 0) return emptyList()
    val (label, o) = when {
        k < 128 -> Texts.t("data_bitrate_under_128", "Menos de 128 kbps") to 0
        k < 192 -> "128 – 191 kbps" to 1
        k < 256 -> "192 – 255 kbps" to 2
        k < 320 -> "256 – 319 kbps" to 3
        k <= 330 -> "320 kbps" to 4
        k < 1000 -> "330 – 999 kbps" to 5
        else -> Texts.t("data_bitrate_1000_plus", "1000 kbps o más") to 6
    }
    return num(label, o.toLong())
}

private fun channelBucket(raw: String): List<Bucket> {
    val r = raw.lowercase()
    val n = r.filter { it.isDigit() }.toIntOrNull()
    return when {
        r.isBlank() -> emptyList()
        "stereo" in r || "joint" in r || n == 2 -> num(Texts.t("data_stereo", "Estéreo"), 2)
        "mono" in r || n == 1 -> num(Texts.t("data_mono", "Mono"), 1)
        n != null -> num(Texts.n("data_channels_n", n, "%d canal", "%d canales"), n.toLong())
        else -> plain(raw)
    }
}

fun groupTracks(tracks: List<Track>, field: TagField): List<Group> {
    val map = LinkedHashMap<String, Pair<Bucket, MutableList<Track>>>()
    for (t in tracks) for (b in field.buckets(t)) {
        map.getOrPut(b.key) { b to ArrayList() }.second += t
    }
    return map.values.map { Group(it.first, it.second) }
}

/** Campos con al menos un valor en la biblioteca, con su número de grupos. */
fun availableFields(tracks: List<Track>): List<Pair<TagField, List<Group>>> =
    TagField.entries.mapNotNull { f ->
        val g = groupTracks(tracks, f)
        val real = g.filterNot { it.bucket.isPlaceholder }
        if (real.isEmpty()) null else f to g
    }

/** Etiquetas descriptivas de una canción para la ficha (campo, valor). */
fun describe(t: Track): List<Pair<String, String>> = buildList {
    fun add(k: String, v: String) { if (v.isNotBlank() && v != "0") add(k to v) }
    fun of(n: Int, total: String) = if (total.isNotBlank()) Texts.t("data_n_of_total", "%1\$s de %2\$s").format(n.toString(), total) else n.toString()
    add(Texts.t("data_info_title", "Título"), t.title); add(Texts.t("data_info_subtitle", "Subtítulo"), t.x("subtitle"))
    add(Texts.t("data_info_track", "Pista"), if (t.trackNo > 0) of(t.trackNo, t.x("track_total")) else "")
    add(Texts.t("data_info_disc", "Disco"), if (t.discNo > 0) of(t.discNo, t.x("disc_total")) else "")
    add(Texts.t("data_info_comment", "Comentario"), t.x("comment")); add("ISRC", t.x("isrc")); add(Texts.t("data_info_catalog", "Nº de catálogo"), t.x("catalog"))
    // «Sí» es lo que se guarda en la caché al leer las etiquetas; se enseña en el idioma de la app.
    add(Texts.t("data_info_rating", "Valoración"), t.x("rating"))
    add(Texts.t("data_info_lyrics", "Letra incluida"), t.x("lyrics").let { if (it == "Sí") Texts.t("data_yes", "Sí") else it })
    add(Texts.t("data_info_codec", "Códec"), t.x("codec"))
    add(Texts.t("data_info_size", "Tamaño"), "%.1f MB".format(Texts.locale(), t.size / 1_048_576f).takeIf { t.size > 0 } ?: "")
    t.x("rg_track").takeIf { it.isNotBlank() }?.let { add(Texts.t("data_info_rg_track", "ReplayGain pista"), "$it dB") }
    t.x("rg_album").takeIf { it.isNotBlank() }?.let { add(Texts.t("data_info_rg_album", "ReplayGain álbum"), "$it dB") }
    add(Texts.t("data_info_file", "Archivo"), t.path)
}
