package com.eddie.surco.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Resultados del análisis de BPM y tonalidad, por archivo (ruta y fecha de modificación: si el
 * archivo cambia, se vuelve a analizar). Aparte de la caché de la biblioteca: «Volver a leer
 * todas las etiquetas» no tira horas de análisis.
 */
class AnalysisStore(ctx: Context) {
    private val file = File(ctx.filesDir, NAME)
    private val map = HashMap<String, Pair<Float?, String?>>()
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

    fun has(t: Track): Boolean { refresh(); return key(t) in map }

    @Synchronized
    fun put(t: Track, r: AudioAnalyzer.Result?) { refresh(); map[key(t)] = r?.bpm to r?.key?.code }

    /** La canción con lo analizado allí donde la etiqueta no dice nada (la etiqueta manda). */
    fun apply(t: Track): Track {
        refresh()
        val (b, k) = map[key(t)] ?: return t
        if ((b == null || t.x("bpm").isNotBlank()) && (k == null || t.x("key").isNotBlank())) return t
        val e = t.extra.toMutableMap()
        if (b != null && t.x("bpm").isBlank()) { e["bpm"] = bpmText(b).replace(',', '.'); e["bpm_src"] = "análisis" }
        if (k != null && t.x("key").isBlank()) { e["key"] = k; e["key_src"] = "análisis" }
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

        private fun read(f: File): Map<String, Pair<Float?, String?>> {
            val text = Disk.read(f) ?: return emptyMap()
            return runCatching {
                val o = JSONObject(text)
                val m = HashMap<String, Pair<Float?, String?>>()
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    m[k] = v.optDouble("b", Double.NaN).takeUnless { it.isNaN() }?.toFloat() to v.optString("k").ifBlank { null }
                }
                m
            }.getOrElse { Disk.quarantine(f); emptyMap() }
        }

        private fun write(f: File, m: Map<String, Pair<Float?, String?>>) {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, JSONObject().apply { v.first?.let { put("b", it.toDouble()) }; v.second?.let { put("k", it) } }) }
            Disk.write(f, o.toString())
        }

        /** Copia de seguridad: todo lo analizado, «ruta|fecha» → (bpm, tonalidad). */
        fun exportAll(ctx: Context): Map<String, Pair<Float?, String?>> = synchronized(LOCK) { read(File(ctx.filesDir, NAME)) }

        /** Restaurar una copia: sustituye todo lo analizado. */
        fun replaceAll(ctx: Context, entries: Map<String, Pair<Float?, String?>>) {
            synchronized(LOCK) {
                write(File(ctx.filesDir, NAME), entries)
                generation += 1
            }
        }
    }
}

/** ¿Le falta BPM o tonalidad en las etiquetas? Solo esas se analizan. */
fun Track.needsAnalysis() = x("bpm").isBlank() || x("key").isBlank()
