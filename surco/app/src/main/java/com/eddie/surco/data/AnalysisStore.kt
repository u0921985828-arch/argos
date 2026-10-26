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
    private val file = File(ctx.filesDir, "analisis.json")
    private val map = HashMap<String, Pair<Float?, String?>>()

    init {
        Disk.read(file)?.let { text ->
            runCatching {
                val o = JSONObject(text)
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    map[k] = v.optDouble("b", Double.NaN).takeUnless { it.isNaN() }?.toFloat() to v.optString("k").ifBlank { null }
                }
            }.onFailure { Disk.quarantine(file) }
        }
    }

    private fun key(t: Track) = "${t.path}|${t.dateModified}"

    fun has(t: Track) = key(t) in map

    @Synchronized
    fun put(t: Track, r: AudioAnalyzer.Result?) { map[key(t)] = r?.bpm to r?.key?.code }

    /** La canción con lo analizado allí donde la etiqueta no dice nada (la etiqueta manda). */
    fun apply(t: Track): Track {
        val (b, k) = map[key(t)] ?: return t
        if ((b == null || t.x("bpm").isNotBlank()) && (k == null || t.x("key").isNotBlank())) return t
        val e = t.extra.toMutableMap()
        if (b != null && t.x("bpm").isBlank()) { e["bpm"] = bpmText(b).replace(',', '.'); e["bpm_src"] = "análisis" }
        if (k != null && t.x("key").isBlank()) { e["key"] = k; e["key_src"] = "análisis" }
        return t.copy(extra = e)
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONObject().apply { v.first?.let { put("b", it.toDouble()) }; v.second?.let { put("k", it) } }) }
        Disk.write(file, o.toString())
    }
}

/** ¿Le falta BPM o tonalidad en las etiquetas? Solo esas se analizan. */
fun Track.needsAnalysis() = x("bpm").isBlank() || x("key").isBlank()
