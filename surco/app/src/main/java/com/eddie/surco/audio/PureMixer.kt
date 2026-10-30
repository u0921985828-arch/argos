package com.eddie.surco.audio

/**
 * Qué mezclador pedirle a Android 14+ para un DAC USB (AudioManager.setPreferredMixerAttributes).
 * Sin Android: se prueba en la JVM.
 *
 * Primero, uno bit-perfect a la frecuencia del archivo, con sus canales y la misma codificación
 * que entrega Surco: la señal llega al DAC tal cual, sin mezclar, sin volumen del sistema y sin
 * remuestrear. Si no lo hay, uno normal a la frecuencia del archivo (y con al menos su
 * resolución): se mezcla, pero sin remuestrear. Si tampoco, nada: lo de siempre.
 */
data class MixerOption(val sampleRate: Int, val encoding: Int, val channels: Int, val bitPerfect: Boolean)

/** Las de android.media.AudioFormat, con su valor. */
object PcmEncoding {
    const val PCM_16BIT = 2
    const val PCM_8BIT = 3
    const val PCM_FLOAT = 4
    const val PCM_24BIT_PACKED = 21
    const val PCM_32BIT = 22

    /** Bits útiles de una codificación (coma flotante: 24 de mantisa). */
    fun bits(e: Int) = when (e) { PCM_8BIT -> 8; PCM_16BIT -> 16; PCM_24BIT_PACKED, PCM_FLOAT -> 24; PCM_32BIT -> 32; else -> 0 }
}

/**
 * A qué salida se ata la música mientras haya unos cascos: [ranks] de cada salida conectada (4
 * USB, 3 cable, 2 Bluetooth, 1 altavoz, 0 otra) y [routed] la que eligió Android. Con cascos, la
 * elegida si es de cascos y, si Android dice altavoz (pasa con algunos cascos USB-C y al cambiar
 * el mezclador del DAC), los cascos de más rango. Sin cascos, -1: lo que decida el sistema.
 */
fun headsetToPin(ranks: List<Int>, routed: Int): Int {
    if (routed in ranks.indices && ranks[routed] >= 2) return routed
    val best = ranks.indices.maxByOrNull { ranks[it] } ?: return -1
    return if (ranks[best] >= 2) best else -1
}

data class MixerChoice(val option: MixerOption, val bitPerfect: Boolean)

/**
 * [encoding]: la que entrega Surco (16 bits o coma flotante). [channels]: los del archivo.
 */
fun pickMixer(options: List<MixerOption>, sampleRate: Int, encoding: Int, channels: Int): MixerChoice? {
    if (sampleRate <= 0) return null
    val same = options.filter { it.sampleRate == sampleRate && it.channels == channels }
    same.firstOrNull { it.bitPerfect && it.encoding == encoding }?.let { return MixerChoice(it, true) }
    return same.filter { !it.bitPerfect && PcmEncoding.bits(it.encoding) >= PcmEncoding.bits(encoding) }
        .minByOrNull { PcmEncoding.bits(it.encoding) }
        ?.let { MixerChoice(it, false) }
}
