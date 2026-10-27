package com.eddie.surco.data

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.reference.GenreTypes
import org.jaudiotagger.tag.wav.WavTag
import java.io.File

/**
 * Lo que se edita de una canción, en el orden de la pantalla. [multi]: la etiqueta puede tener
 * varios valores (dos artistas, dos géneros); se ven unidos con «; », como en la biblioteca, y se
 * escriben otra vez como valores separados.
 */
enum class TagKey(val fieldKey: FieldKey, val kind: Kind = Kind.TEXT, val multi: Boolean = false) {
    TITLE(FieldKey.TITLE),
    ARTIST(FieldKey.ARTIST, multi = true),
    ALBUM(FieldKey.ALBUM),
    ALBUM_ARTIST(FieldKey.ALBUM_ARTIST),
    GENRE(FieldKey.GENRE, multi = true),
    YEAR(FieldKey.YEAR, Kind.YEAR),
    TRACK(FieldKey.TRACK, Kind.COUNT),
    DISC(FieldKey.DISC_NO, Kind.COUNT),
    COMPOSER(FieldKey.COMPOSER, multi = true),
    BPM(FieldKey.BPM, Kind.BPM),
    KEY(FieldKey.KEY),
    MOOD(FieldKey.MOOD, multi = true),
    COMMENT(FieldKey.COMMENT, Kind.LONG),
    ;

    enum class Kind { TEXT, LONG, YEAR, COUNT, BPM }

    val numeric: Boolean get() = kind == Kind.YEAR || kind == Kind.COUNT || kind == Kind.BPM
}

/**
 * Formatos que jaudiotagger 3.0.1 sabe escribir. Opus no: no lo abre (la biblioteca lo lee con
 * `readOpusTags`, a mano), así que no se ofrece guardar. Un .ogg que por dentro es Opus tampoco se
 * abre: lo descubre la lectura (`readTagValues` da null).
 */
private val WRITABLE = setOf("mp3", "flac", "ogg", "oga", "m4a", "m4b", "mp4", "wav", "wave", "aif", "aiff", "aifc", "wma", "dsf")

fun isWritableFormat(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in WRITABLE

private val YEAR_RE = Regex("""\d{4}""")
private val COUNT_RE = Regex("""\d{1,3}""")
private val BPM_RE = Regex("""\d{1,3}([.,]\d{1,2})?""")

/** Vacío vale siempre (borra la etiqueta). Año de 4 cifras; pista y disco de 1 a 999; BPM de 1 a 999 con hasta 2 decimales. */
fun isValidValue(key: TagKey, raw: String): Boolean {
    val v = raw.trim()
    if (v.isEmpty()) return true
    return when (key.kind) {
        TagKey.Kind.TEXT, TagKey.Kind.LONG -> true
        TagKey.Kind.YEAR -> YEAR_RE.matches(v) && v.toInt() >= 1000
        TagKey.Kind.COUNT -> COUNT_RE.matches(v) && v.toInt() >= 1
        TagKey.Kind.BPM -> BPM_RE.matches(v) && v.replace(',', '.').toDouble() > 0.0
    }
}

/** Lo que se escribe: sin espacios de sobra, BPM con punto («120,5» → «120.5») y números sin ceros delante. */
fun normalizeValue(key: TagKey, raw: String): String {
    val v = raw.trim()
    if (v.isEmpty() || !isValidValue(key, v)) return v
    return when (key.kind) {
        TagKey.Kind.COUNT -> v.toInt().toString()
        TagKey.Kind.BPM -> v.replace(',', '.').trimStart('0').let { if (it.isEmpty() || it.startsWith('.')) "0$it" else it }
        else -> v
    }
}

/**
 * Campos que cambiaron respecto a lo leído del archivo, con el valor a escribir. Solo se toca lo
 * cambiado: un año «2001-05-03» o un género «(17)» que nadie ha tocado se quedan como estaban.
 */
fun changedValues(before: Map<TagKey, String>, after: Map<TagKey, String>): Map<TagKey, String> =
    TagKey.entries.mapNotNull { k ->
        val a = after[k] ?: return@mapNotNull null
        if (a.trim() == before[k].orEmpty().trim()) null else k to normalizeValue(k, a)
    }.toMap()

/** Campos con un valor que no se puede guardar; solo cuentan los cambiados (lo leído vale como venía). */
fun invalidKeys(before: Map<TagKey, String>, after: Map<TagKey, String>): Set<TagKey> =
    changedValues(before, after).keys.filter { !isValidValue(it, after[it].orEmpty()) }.toSet()

/** Varios valores: «Ana; Luis» → [Ana, Luis]. */
fun splitValues(v: String): List<String> = v.split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private val ID3_GENRE = Regex("""^\(?(\d{1,3})\)?$""")

/** Lo que dice cada campo en el archivo, como se ve en la pantalla. Sin etiqueta, todo vacío. */
fun readTagValues(tag: Tag?): Map<TagKey, String> = TagKey.entries.associateWith { k ->
    if (tag == null) return@associateWith ""
    val values = runCatching {
        if (k.multi) tag.getAll(k.fieldKey).orEmpty() else listOf(tag.getFirst(k.fieldKey).orEmpty())
    }.getOrDefault(emptyList())
    // Los WAV traen el terminador nulo dentro del texto; ID3v1 guarda el género como número.
    val clean = values.map { it.replace("\u0000", "").trim() }.filter { it.isNotEmpty() }.map { v ->
        if (k != TagKey.GENRE) v
        else ID3_GENRE.matchEntire(v)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { runCatching { GenreTypes.getInstanceOf().getValueForId(it) }.getOrNull() } ?: v
    }.distinct()
    clean.joinToString("; ")
}

/** Las etiquetas de un archivo, o null si jaudiotagger no lo abre (Opus, archivo roto o ilegible). */
fun readTagValues(file: File): Map<TagKey, String>? {
    if (!isWritableFormat(file.path) || !file.canRead()) return null
    return runCatching { readTagValues(AudioFileIO.read(file).tag) }.getOrNull()
}

/**
 * Pone [changes] en la etiqueta del archivo abierto (sin guardar: eso es `commit`). Un MP3 que
 * solo tiene ID3v1 recibe un ID3v2: en v1 no caben ni el artista del álbum ni 30 letras de título.
 */
fun applyTagEdits(af: AudioFile, changes: Map<TagKey, String>) {
    when (af) {
        is MP3File -> setValues(af.iD3v2Tag ?: af.createDefaultTag().also { af.setTag(it) }, changes)
        else -> when (val tag = af.tagOrCreateAndSetDefault) {
            is WavTag -> applyWav(tag, changes)
            else -> setValues(tag, changes)
        }
    }
}

/**
 * WAV: el bloque INFO no tiene disco, BPM, tonalidad ni estado de ánimo. Todo va a un ID3 dentro
 * del WAV (jaudiotagger lo lee antes que INFO y guarda los dos); si no lo había, empieza con lo
 * que decía INFO, para que no se pierda nada al leerlo después. INFO recibe lo que admite.
 */
private fun applyWav(tag: WavTag, changes: Map<TagKey, String>) {
    if (!tag.isExistingId3Tag || tag.iD3Tag == null) {
        val before = readTagValues(tag).filterValues { it.isNotEmpty() }
        tag.setID3Tag(ID3v23Tag())
        tag.isExistingId3Tag = true
        setValues(tag.iD3Tag, before, lenient = true)
    }
    setValues(tag.iD3Tag, changes)
    tag.infoTag?.let { setValues(it, changes, lenient = true) }
}

/** [lenient]: lo que el formato no admite se salta en vez de fallar. */
private fun setValues(tag: Tag, changes: Map<TagKey, String>, lenient: Boolean = false) {
    for ((k, v) in changes) {
        if (v.isBlank()) { runCatching { tag.deleteField(k.fieldKey) }; continue }
        val parts = if (k.multi) splitValues(v) else listOf(v.trim())
        try {
            tag.setField(k.fieldKey, parts.first())
        } catch (e: Exception) {
            if (lenient) continue else throw e
        }
        if (parts.size < 2) continue
        // Si el formato no admite varios valores en ese campo (WMA se queda con el último), van
        // juntos en uno, como se leen.
        val kept = runCatching {
            parts.drop(1).forEach { tag.addField(k.fieldKey, it) }
            tag.getAll(k.fieldKey).map { it.trim() }
        }.getOrNull()
        if (kept != parts) tag.setField(k.fieldKey, parts.joinToString("; "))
    }
}

/** Abre [file], aplica [changes] y lo guarda: todo en el mismo archivo (una copia, en la app). */
fun writeTagValues(file: File, changes: Map<TagKey, String>) {
    val af = AudioFileIO.read(file)
    applyTagEdits(af, changes)
    af.commit()
}
