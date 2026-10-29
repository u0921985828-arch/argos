package com.eddie.surco.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.eddie.surco.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Por dónde sale el sonido (altavoz, cable, Bluetooth, USB) y, con un DAC USB en Android 14+, el
 * mezclador que se le ha pedido: bit-perfect a la frecuencia del archivo si el DAC lo admite, o
 * al menos sin remuestrear ([pickMixer]). Lo enseña la línea «Salida» de «Sonando».
 */
object OutputRoute {
    enum class Kind { SPEAKER, WIRED, BLUETOOTH, USB, OTHER }

    data class Route(val kind: Kind, val name: String, val mixer: MixerChoice? = null)

    private val _state = MutableStateFlow<Route?>(null)
    val state: StateFlow<Route?> = _state.asStateFlow()

    /**
     * Los cascos (USB, cable o Bluetooth) a los que se ata la música mientras estén: el reproductor
     * se los pide a Android como salida preferida, así ni un cambio de mezclador del DAC ni el
     * segundo reproductor del cruce acaban en el altavoz. null sin cascos.
     */
    private val _pinned = MutableStateFlow<AudioDeviceInfo?>(null)
    val pinned: StateFlow<AudioDeviceInfo?> = _pinned.asStateFlow()

    /** Se han ido los últimos cascos: hay que parar antes de que nada salga por el altavoz. */
    var onHeadsetGone: (() -> Unit)? = null

    private val media: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private var am: AudioManager? = null
    private var wanted: Wanted? = null
    /** El DAC al que se le pidió un mezclador (para quitárselo después). */
    private var preferredOn: AudioDeviceInfo? = null

    /** Lo que suena: frecuencia, codificación que entrega Surco y canales. */
    data class Wanted(val enabled: Boolean, val sampleRate: Int, val encoding: Int, val channels: Int)

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            val had = _pinned.value != null
            refresh()
            // Además del aviso de Android («ruido», que Media3 ya atiende): si no quedan cascos, pausa ya.
            if (had && _pinned.value == null && removed.orEmpty().any { rank(it.type) >= 2 }) onHeadsetGone?.invoke()
        }
    }

    /** Desde el servicio de reproducción (hilo principal). */
    fun start(ctx: Context) {
        if (am != null) return
        am = ctx.applicationContext.getSystemService(AudioManager::class.java)?.also {
            it.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
        refresh()
    }

    fun stop() {
        am?.let { m ->
            m.unregisterAudioDeviceCallback(callback)
            if (Build.VERSION.SDK_INT >= 34) clearMixer(m)
        }
        am = null
        _state.value = null
        _pinned.value = null
    }

    /** Cada vez que cambia la canción o el ajuste. */
    fun want(w: Wanted) {
        if (w == wanted) return
        wanted = w
        refresh()
    }

    private fun refresh() {
        val m = am ?: return
        val routed = current(m)
        val outs = m.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val pin = outs.getOrNull(headsetToPin(outs.map { rank(it.type) }, outs.indexOfFirst { routed != null && it.id == routed.id }))
        if (pin?.id != _pinned.value?.id) {
            Log.i("Surco", "Surco salida atada a: ${pin?.let { "${kindOf(it.type)} ${it.productName}" } ?: "lo que elija el sistema"}")
            _pinned.value = pin
        }
        // Con cascos, la salida es la de los cascos aunque Android diga un instante «altavoz».
        val dev = pin ?: routed
        var mixer: MixerChoice? = null
        if (Build.VERSION.SDK_INT >= 34) mixer = applyMixer(m, dev)
        _state.value = dev?.let { Route(kindOf(it.type), it.productName?.toString().orEmpty(), mixer) } ?: Route(Kind.SPEAKER, "")
    }

    /**
     * Por dónde sale ahora la música. En Android 13+ lo dice el sistema; antes, se toma el
     * dispositivo con más prioridad de los conectados (USB, cable, Bluetooth, altavoz).
     */
    private fun current(m: AudioManager): AudioDeviceInfo? {
        val outs = m.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (Build.VERSION.SDK_INT >= 33) {
            val routed = m.getAudioDevicesForAttributes(media).firstOrNull()
            if (routed != null) outs.firstOrNull { it.type == routed.type && (routed.address.isEmpty() || it.address == routed.address) }?.let { return it }
        }
        return outs.maxByOrNull { rank(it.type) }?.takeIf { rank(it.type) > 0 }
    }

    private fun rank(type: Int) = when (kindOf(type)) {
        Kind.USB -> 4; Kind.WIRED -> 3; Kind.BLUETOOTH -> 2; Kind.SPEAKER -> 1; Kind.OTHER -> 0
    }

    private fun kindOf(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> Kind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
        else -> Kind.OTHER
    }

    /** El mezclador para lo que suena en el DAC USB, o ninguno (y se quita el que hubiera). */
    @RequiresApi(34)
    private fun applyMixer(m: AudioManager, dev: AudioDeviceInfo?): MixerChoice? {
        val w = wanted
        if (w == null || !w.enabled || dev == null || kindOf(dev.type) != Kind.USB) { clearMixer(m); return null }
        val supported = runCatching { m.getSupportedMixerAttributes(dev) }.getOrDefault(emptyList())
        val options = supported.map { a ->
            MixerOption(a.format.sampleRate, a.format.encoding, a.format.channelCount, a.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
        }
        val choice = pickMixer(options, w.sampleRate, w.encoding, w.channels)
        if (choice == null) { clearMixer(m); return null }
        val attrs = supported[options.indexOf(choice.option)]
        val ok = runCatching { m.setPreferredMixerAttributes(media, dev, attrs) }.getOrDefault(false)
        Log.i("Surco", "Surco DAC USB: ${choice.option} bitPerfect=${choice.bitPerfect} aceptado=$ok")
        if (!ok) return null
        preferredOn = dev
        return choice
    }

    @RequiresApi(34)
    private fun clearMixer(m: AudioManager) {
        preferredOn?.let { d -> runCatching { m.clearPreferredMixerAttributes(media, d) } }
        preferredOn = null
    }

    /** La codificación que entrega Surco para un archivo: coma flotante para FLAC/ALAC y lo de más de 16 bits. */
    fun encodingFor(format: String, bits: Int): Int =
        if (bits > 16 || format.equals("FLAC", true) || format.equals("ALAC", true)) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
}

/** Clave de la salida para su ecualizador ([outputKey]). */
val OutputRoute.Route.key: String get() = outputKey(kind.name, name)

/**
 * Si se sabe qué aparato es: un Bluetooth o USB sin nombre todavía (llega a veces un instante
 * después) no se toma por otro, ni todos los sin nombre por el mismo.
 */
val OutputRoute.Route.identified: Boolean
    get() = kind == OutputRoute.Kind.SPEAKER || kind == OutputRoute.Kind.WIRED || name.isNotBlank()

/** Nombre visible de una salida («Auriculares de cable», «Bluetooth · WH-1000XM5»…). */
fun routeName(ctx: Context, r: OutputRoute.Route): String = when (r.kind) {
    OutputRoute.Kind.SPEAKER -> ctx.getString(R.string.player_route_speaker)
    OutputRoute.Kind.WIRED -> ctx.getString(R.string.player_route_wired)
    OutputRoute.Kind.BLUETOOTH -> ctx.getString(R.string.player_route_bt, r.name)
    OutputRoute.Kind.USB -> ctx.getString(R.string.player_route_usb, r.name)
    OutputRoute.Kind.OTHER -> r.name
}
