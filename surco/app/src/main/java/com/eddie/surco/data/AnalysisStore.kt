package com.eddie.surco.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Resultados del análisis de BPM, tonalidad y sonoridad, por archivo (ruta y fecha de modificación: si el
 * archivo cambia, se vuelve a analizar). Aparte de la caché de la biblioteca: «Volver a leer
 * todas las etiquetas» no tira horas de análisis.
 */
class AnalysisStore(ctx: Context) {
    private val file = File(ctx.filesDir, NAME)
    private val map = HashMap<String, Analyzed>()
    /** La [generation] de lo que hay en [map]: si se ha restaurado una copia, se relee. */
    private var loaded = -1

    init { refresh() }

    @Synchronized
    private fun refresh() {
        val g = generation
        if (loaded == g) return
        map.clear()
        map.putAll(read(file))
        loaded = g
    }

    private fun key(t: Track) = analysisKey(t.path, t.dateModified)

    /** Ya analizada: lo de antes de medir la sonoridad cuenta solo si la etiqueta ya trae ReplayGain. */
    fun has(t: Track): Boolean { refresh(); val e = map[key(t)] ?: return false; return e.loudnessDone || t.x("rg_track").isNotBlank() }

    /** Lo que ya se sabía de este archivo (BPM y tonalidad de antes) no se pierde si ahora solo se midió la sonoridad. */
    @Synchronized
    fun put(t: Track, r: AudioAnalyzer.Result?) {
        refresh()
        val old = map[key(t)]
        map[key(t)] = Analyzed(r?.bpm ?: old?.bpm, r?.key?.code ?: old?.key, r?.lufs ?: old?.lufs, loudnessDone = true)
    }

    /** ¿Hay que calcular BPM y tonalidad, o basta con la sonoridad? (Las etiquetas o un análisis anterior ya los dan.) */
    fun needsTempoKey(t: Track): Boolean {
        refresh()
        return key(t) !in map && (t.x("bpm").isBlank() || t.x("key").isBlank())
    }

    /** [apply] a toda la biblioteca y, en cada disco sin etiqueta de álbum, su ganancia de álbum medida. */
    fun applyAll(list: List<Track>): List<Track> = withAlbumGains(list.map { apply(it) })

    /** La canción con lo analizado allí donde la etiqueta no dice nada (la etiqueta manda). */
    fun apply(t: Track): Track {
        refresh()
        val (b, k, l) = map[key(t)] ?: return t
        if ((b == null || t.x("bpm").isNotBlank()) && (k == null || t.x("key").isNotBlank()) && (l == null || t.x("rg_track").isNotBlank())) return t
        val e = t.extra.toMutableMap()
        if (b != null && t.x("bpm").isBlank()) { e["bpm"] = bpmText(b).replace(',', '.'); e["bpm_src"] = "análisis" }
        if (k != null && t.x("key").isBlank()) { e["key"] = k; e["key_src"] = "análisis" }
        // La ganancia medida con «.» siempre: así la leen MediaItems y la línea de salida.
        if (l != null && t.x("rg_track").isBlank()) { e["rg_track"] = "%.2f".format(java.util.Locale.ROOT, replayGainFromLufs(l.toDouble())); e["rg_src"] = "análisis" }
        return t.copy(extra = e)
    }

    @Synchronized
    fun save() {
        synchronized(LOCK) {
            // Una copia restaurada mientras tanto manda: no se guarda encima lo de antes.
            refresh()
            write(file, map)
        }
    }

    companion object {
        private const val NAME = "analisis.json"
        private val LOCK = Any()
        /** Sube al restaurar una copia: cada instancia viva relee el archivo antes de usarlo. */
        @Volatile private var generation = 0

        private fun double(v: JSONObject, k: String) = v.optDouble(k, Double.NaN).takeUnless { it.isNaN() }?.toFloat()

        private fun read(f: File): Map<String, Analyzed> {
            val text = Disk.read(f) ?: return emptyMap()
            return runCatching {
                val o = JSONObject(text)
                val m = HashMap<String, Analyzed>()
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    m[k] = Analyzed(double(v, "b"), v.optString("k").ifBlank { null }, double(v, "l"), v.optBoolean("ld"))
                }
                m
            }.getOrElse { Disk.quarantine(f); emptyMap() }
        }

        private fun write(f: File, m: Map<String, Analyzed>) {
            val o = JSONObject()
            m.forEach { (k, v) ->
                o.put(k, JSONObject().apply {
                    v.bpm?.let { put("b", it.toDouble()) }; v.key?.let { put("k", it) }
                    v.lufs?.let { put("l", it.toDouble()) }; if (v.loudnessDone) put("ld", true)
                })
            }
            Disk.write(f, o.toString())
        }

        /** Copia de seguridad: todo lo analizado, «ruta|fecha» → lo analizado. */
        fun exportAll(ctx: Context): Map<String, Analyzed> = synchronized(LOCK) { read(File(ctx.filesDir, NAME)) }

        /** Restaurar una copia: sustituye todo lo analizado. */
        fun replaceAll(ctx: Context, entries: Map<String, Analyzed>) {
            synchronized(LOCK) {
                write(File(ctx.filesDir, NAME), entries)
                generation += 1
            }
        }
    }
}

/**
 * ReplayGain de álbum medido: en cada disco (por su id de MediaStore o, si no, por nombre y artista
 * del disco) en el que ninguna canción trae la etiqueta de álbum y todas tienen ganancia de pista
 * (de la etiqueta o medida), la de todo el disco. Así «por álbum» conserva lo suave y lo fuerte de
 * un disco en vez de igualar cada pista.
 */
fun withAlbumGains(list: List<Track>): List<Track> {
    fun tagged(t: Track) = t.x("rg_album").isNotBlank() && t.x("rg_album_src") != "análisis"
    val groups = list.filter { it.album.isNotBlank() }
        .groupBy { if (it.albumId > 0) "id:${it.albumId}" else "n:${it.album.lowercase()}|${it.albumArtist.lowercase()}" }
    val gain = HashMap<Long, String>()
    for (g in groups.values) {
        if (g.any(::tagged)) continue
        val tracks = g.mapNotNull { it.x("rg_track").toFloatOrNull() }
        if (tracks.size != g.size) continue
        val a = "%.2f".format(java.util.Locale.ROOT, albumReplayGain(tracks))
        for (t in g) if (t.x("rg_album") != a) gain[t.id] = a
    }
    if (gain.isEmpty()) return list
    return list.map { t -> gain[t.id]?.let { a -> t.copy(extra = t.extra + mapOf("rg_album" to a, "rg_album_src" to "análisis")) } ?: t }
}

/** ¿Le falta BPM, tonalidad o ReplayGain en las etiquetas? Solo esas se analizan. */
fun Track.needsAnalysis() = x("bpm").isBlank() || x("key").isBlank() || x("rg_track").isBlank()
