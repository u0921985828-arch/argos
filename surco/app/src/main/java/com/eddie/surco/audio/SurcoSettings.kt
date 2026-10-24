package com.eddie.surco.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ReplayGain: apagado, por pista o por álbum. */
enum class ReplayGainMode(val label: String) { OFF("Apagado"), TRACK("Por pista"), ALBUM("Por álbum") }

/** Todo lo que se ajusta en Surco. Inmutable: se cambia con [SurcoSettings.update]. */
data class Prefs(
    val dsp: DspParams = DspParams(),
    val preset: String = "Plano",
    val customPresets: Map<String, List<Float>> = emptyMap(),
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val skipSilence: Boolean = false,
    val speed: Float = 1f,
    /** Al cambiar la velocidad, mantener el tono (si no, sube y baja como un disco). */
    val keepPitch: Boolean = true,
    val fade: Boolean = true,
    /** Canciones más cortas que esto no salen en la biblioteca (notas de voz, avisos…). */
    val minDurationSec: Int = 0,
)

/**
 * Ajustes compartidos entre la interfaz y el servicio (mismo proceso). El procesador de
 * audio lee [dsp] desde su hilo; se publica como referencia inmutable y volátil.
 */
object SurcoSettings {
    private val _state = MutableStateFlow(Prefs())
    val state: StateFlow<Prefs> = _state

    @Volatile
    var dsp: DspParams = DspParams()
        private set

    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "ajustes.json")
        file = f
        runCatching { set(fromJson(JSONObject(f.readText()))) }
        scope.launch { _state.drop(1).debounce(400).collect { p -> runCatching { f.writeText(toJson(p).toString()) } } }
    }

    private fun set(p: Prefs) {
        dsp = p.dsp
        _state.value = p
    }

    @Synchronized
    fun update(f: (Prefs) -> Prefs) = set(f(_state.value))

    fun updateDsp(f: (DspParams) -> DspParams) = update { it.copy(dsp = f(it.dsp)) }

    /** Preajustes de fábrica y propios, en orden. */
    fun allPresets(p: Prefs = state.value): List<Pair<String, List<Float>>> = BUILTIN_PRESETS + p.customPresets.toList()

    // ----- JSON -----
    private fun toJson(p: Prefs) = JSONObject().apply {
        val d = p.dsp
        put("eqOn", d.eqOn); put("bands", JSONArray(d.bands.map { it.toDouble() })); put("preamp", d.preampDb.toDouble())
        put("bass", d.bassDb.toDouble()); put("treble", d.trebleDb.toDouble()); put("balance", d.balance.toDouble())
        put("mono", d.mono); put("crossfeed", d.crossfeed); put("limiter", d.limiter)
        put("preset", p.preset)
        put("custom", JSONObject().apply { p.customPresets.forEach { (k, v) -> put(k, JSONArray(v.map { it.toDouble() })) } })
        put("replayGain", p.replayGain.name); put("skipSilence", p.skipSilence)
        put("speed", p.speed.toDouble()); put("keepPitch", p.keepPitch); put("fade", p.fade)
        put("minDuration", p.minDurationSec)
    }

    private fun floats(a: JSONArray?): List<Float>? = a?.let { arr -> List(arr.length()) { arr.optDouble(it, 0.0).toFloat() } }

    private fun fromJson(o: JSONObject): Prefs {
        val def = Prefs()
        val bands = floats(o.optJSONArray("bands"))?.takeIf { it.size == EQ_FREQS.size } ?: def.dsp.bands
        val custom = LinkedHashMap<String, List<Float>>()
        o.optJSONObject("custom")?.let { c -> c.keys().forEach { k -> floats(c.optJSONArray(k))?.takeIf { it.size == EQ_FREQS.size }?.let { custom[k] = it } } }
        return Prefs(
            dsp = DspParams(
                eqOn = o.optBoolean("eqOn", true), bands = bands,
                preampDb = o.optDouble("preamp", 0.0).toFloat(), bassDb = o.optDouble("bass", 0.0).toFloat(),
                trebleDb = o.optDouble("treble", 0.0).toFloat(), balance = o.optDouble("balance", 0.0).toFloat(),
                mono = o.optBoolean("mono"), crossfeed = o.optInt("crossfeed").coerceIn(0, CROSSFEED_LEVELS.lastIndex),
                limiter = o.optBoolean("limiter", true),
            ),
            preset = o.optString("preset", def.preset),
            customPresets = custom,
            replayGain = runCatching { ReplayGainMode.valueOf(o.optString("replayGain")) }.getOrDefault(ReplayGainMode.OFF),
            skipSilence = o.optBoolean("skipSilence"),
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 3f),
            keepPitch = o.optBoolean("keepPitch", true),
            fade = o.optBoolean("fade", true),
            minDurationSec = o.optInt("minDuration", 0),
        )
    }
}
