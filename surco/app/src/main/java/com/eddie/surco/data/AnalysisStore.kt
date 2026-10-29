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
    private val map = HashMap<String, Entry>()
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

    @Synchronized
    fun put(t: Track, r: AudioAnalyzer.Result?) { refresh(); map[key(t)] = Entry(r?.bpm, r?.key?.code, r?.lufs, loudnessDone = true) }

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

        private fun read(f: File): Map<String, Entry> {
            val text = Disk.read(f) ?: return emptyMap()
            return runCatching {
                val o = JSONObject(text)
                val m = HashMap<String, Entry>()
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    m[k] = Entry(double(v, "b"), v.optString("k").ifBlank { null }, double(v, "l"), v.optBoolean("ld"))
                }
                m
            }.getOrElse { Disk.quarantine(f); emptyMap() }
        }

        private fun write(f: File, m: Map<String, Entry>) {
            val o = JSONObject()
            m.forEach { (k, v) ->
                o.put(k, JSONObject().apply {
                    v.bpm?.let { put("b", it.toDouble()) }; v.key?.let { put("k", it) }
                    v.lufs?.let { put("l", it.toDouble()) }; if (v.loudnessDone) put("ld", true)
                })
            }
            Disk.write(f, o.toString())
        }

        /** Copia de seguridad: todo lo analizado, «ruta|fecha» → (bpm, tonalidad). La sonoridad se vuelve a medir. */
        fun exportAll(ctx: Context): Map<String, Pair<Float?, String?>> =
            synchronized(LOCK) { read(File(ctx.filesDir, NAME)).mapValues { it.value.bpm to it.value.key } }

        /** Restaurar una copia: sustituye todo lo analizado. */
        fun replaceAll(ctx: Context, entries: Map<String, Pair<Float?, String?>>) {
            synchronized(LOCK) {
                write(File(ctx.filesDir, NAME), entries.mapValues { Entry(it.value.first, it.value.second, null, false) })
                generation += 1
            }
        }
    }
}

/** Lo analizado de un archivo; [loudnessDone]: ya se intentó medir la sonoridad (puede no haber salido). */
private data class Entry(val bpm: Float?, val key: String?, val lufs: Float?, val loudnessDone: Boolean)

/** ¿Le falta BPM, tonalidad o ReplayGain en las etiquetas? Solo esas se analizan. */
fun Track.needsAnalysis() = x("bpm").isBlank() || x("key").isBlank() || x("rg_track").isBlank()
