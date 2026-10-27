package com.eddie.surco.audio

import android.os.Build
import android.content.Context
import androidx.annotation.StringRes
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.data.Disk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ReplayGain: apagado, por pista o por álbum. Se guarda por [name]; [labelRes] es lo que se enseña. */
enum class ReplayGainMode(@StringRes val labelRes: Int) {
    OFF(R.string.settings_rg_off), TRACK(R.string.settings_rg_track), ALBUM(R.string.settings_rg_album)
}

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
    /** La preamplificación baja sola lo que suban bandas y tono. */
    val autoPreamp: Boolean = true,
    /** Fundido entre canciones, en segundos (0 = sin fundido). */
    val crossfadeSec: Int = 0,
    /** Sin fundido dentro del mismo disco: los discos sin pausas siguen sin pausas. */
    val crossfadeKeepAlbums: Boolean = true,
    /** Color de acento (nombre de [ACCENTS] o «Del disco»). */
    val accent: String = "Cobre",
    /** Formato, bits y salida en «Sonando». Apagado: la pantalla queda limpia. */
    val techDetails: Boolean = false,
    /** Pantalla encendida mientras «Sonando» está abierta. */
    val keepScreenOn: Boolean = false,
    /** DAC USB en Android 14+: mezclador bit-perfect (o sin remuestrear) a la frecuencia de cada canción. */
    val usbBitPerfect: Boolean = false,
    /** Secciones de Inicio. */
    val homeRecent: Boolean = true,
    val homeAdded: Boolean = true,
    val homeTop: Boolean = true,
    val homeExplore: Boolean = true,
    /** Portadas por fila en Biblioteca → Álbumes (2–5, se cambia pellizcando la cuadrícula). */
    val albumColumns: Int = 2,
    /** Si una canción no trae letra, buscarla en LRCLIB (sale artista, título, álbum y duración). */
    val onlineLyrics: Boolean = false,
    /** Analizar BPM y tonalidad de las canciones que no los traen en las etiquetas. */
    val analyze: Boolean = true,
    /** Carpetas (ruta completa) que no entran en la biblioteca. */
    val excludedFolders: Set<String> = emptySet(),
)

/** Colores de acento fijos (0xRRGGBB), todos con contraste ≥ 4,5:1 sobre el fondo. «Del disco» sale de la portada. */
val ACCENTS = linkedMapOf(
    "Cobre" to 0xE08A4B, "Azul" to 0x5FA8E8, "Verde" to 0x5DBB7E, "Rosa" to 0xE8789F, "Violeta" to 0xA98BEB, "Ámbar" to 0xE2B13C,
)
const val ACCENT_FROM_ART = "Del disco"
/** Portadas por fila que admite la cuadrícula de álbumes. */
val ALBUM_COLUMNS = 2..5
/** Material You (Android 12+): el color del fondo de pantalla, el mismo que usa el sistema. */
const val ACCENT_SYSTEM = "Sistema"

/**
 * Nombre visible de un acento. Los nombres de [ACCENTS] y [ACCENT_FROM_ART] son identificadores
 * guardados en ajustes.json: no cambian con el idioma; lo que se enseña sale de los recursos.
 */
@StringRes
fun accentLabelRes(id: String): Int? = when (id) {
    "Cobre" -> R.string.settings_accent_copper
    "Azul" -> R.string.settings_accent_blue
    "Verde" -> R.string.settings_accent_green
    "Rosa" -> R.string.settings_accent_pink
    "Violeta" -> R.string.settings_accent_violet
    "Ámbar" -> R.string.settings_accent_amber
    ACCENT_FROM_ART -> R.string.settings_accent_art
    ACCENT_SYSTEM -> R.string.settings_accent_system
    else -> null
}

/**
 * Nombre visible de un preajuste del ecualizador. Los de fábrica ([BUILTIN_PRESETS]) y
 * «Personalizado» se guardan con su nombre en español (identificador); los del usuario se
 * enseñan tal cual.
 */
@StringRes
fun presetLabelRes(name: String): Int? = when (name) {
    "Plano" -> R.string.settings_preset_flat
    "Graves" -> R.string.settings_preset_bass
    "Graves y agudos" -> R.string.settings_preset_bass_treble
    "Agudos" -> R.string.settings_preset_treble
    "Voz" -> R.string.settings_preset_vocal
    "Rock" -> R.string.settings_preset_rock
    "Pop" -> R.string.settings_preset_pop
    "Jazz" -> R.string.settings_preset_jazz
    "Clásica" -> R.string.settings_preset_classical
    "Electrónica" -> R.string.settings_preset_electronic
    "Acústica" -> R.string.settings_preset_acoustic
    "Nocturno" -> R.string.settings_preset_night
    "Personalizado" -> R.string.settings_preset_custom
    else -> null
}

/** [presetLabelRes] fuera de Compose; un preajuste del usuario devuelve su propio nombre. */
fun presetLabel(name: String): String = presetLabelRes(name)?.let { Res.str(it) } ?: name

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
        Disk.read(f)?.let { text ->
            runCatching { set(fromJson(JSONObject(text))) }.onFailure { Disk.quarantine(f) }
        }
        scope.launch(Disk.writer) { _state.drop(1).debounce(400).collect { p -> Disk.write(f, toJson(p).toString()) } }
    }

    private fun set(p: Prefs) {
        // Con la preamplificación automática, la curva nunca pasa de 0 dB: el limitador no actúa.
        val q = if (p.autoPreamp) p.copy(dsp = p.dsp.copy(preampDb = autoPreampDb(p.dsp))) else p
        dsp = q.dsp
        _state.value = q
    }

    @Synchronized
    fun update(f: (Prefs) -> Prefs) = set(f(_state.value))

    fun updateDsp(f: (DspParams) -> DspParams) = update { it.copy(dsp = f(it.dsp)) }

    /** Preajustes de fábrica y propios, en orden. */
    fun allPresets(p: Prefs = state.value): List<Pair<String, List<Float>>> = BUILTIN_PRESETS + p.customPresets.toList()

    // ----- Copia de seguridad (data/Backup.kt): el mismo JSON que ajustes.json -----
    fun exportJson(): String = toJson(_state.value).toString()

    /** Lee sin aplicar (falla si no es JSON): se valida todo antes de tocar nada. */
    fun parseJson(text: String): Prefs = fromJson(JSONObject(text))

    /** Sustituye todos los ajustes y los guarda ya, sin esperar al guardado diferido. */
    fun restore(p: Prefs) {
        update { p }
        val f = file ?: return
        runBlocking(Disk.writer) { Disk.write(f, toJson(_state.value).toString()) }
    }

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
        put("minDuration", p.minDurationSec); put("autoPreamp", p.autoPreamp)
        put("crossfade", p.crossfadeSec); put("crossfadeKeepAlbums", p.crossfadeKeepAlbums)
        put("accent", p.accent); put("techDetails", p.techDetails); put("keepScreenOn", p.keepScreenOn); put("usbBitPerfect", p.usbBitPerfect)
        put("homeRecent", p.homeRecent); put("homeAdded", p.homeAdded); put("homeTop", p.homeTop); put("homeExplore", p.homeExplore)
        put("albumColumns", p.albumColumns)
        put("excluded", JSONArray(p.excludedFolders.toList())); put("onlineLyrics", p.onlineLyrics); put("analyze", p.analyze)
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
            autoPreamp = o.optBoolean("autoPreamp", true),
            crossfadeSec = o.optInt("crossfade", 0).coerceIn(0, 12),
            crossfadeKeepAlbums = o.optBoolean("crossfadeKeepAlbums", true),
            accent = o.optString("accent", def.accent).takeIf { it in ACCENTS || it == ACCENT_FROM_ART || (it == ACCENT_SYSTEM && Build.VERSION.SDK_INT >= 31) } ?: def.accent,
            techDetails = o.optBoolean("techDetails", false),
            keepScreenOn = o.optBoolean("keepScreenOn", false),
            usbBitPerfect = o.optBoolean("usbBitPerfect", false),
            homeRecent = o.optBoolean("homeRecent", true), homeAdded = o.optBoolean("homeAdded", true),
            homeTop = o.optBoolean("homeTop", true), homeExplore = o.optBoolean("homeExplore", true),
            albumColumns = o.optInt("albumColumns", 2).coerceIn(ALBUM_COLUMNS),
            onlineLyrics = o.optBoolean("onlineLyrics", false),
            analyze = o.optBoolean("analyze", true),
            excludedFolders = o.optJSONArray("excluded")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }.toSet() } ?: emptySet(),
        )
    }
}
