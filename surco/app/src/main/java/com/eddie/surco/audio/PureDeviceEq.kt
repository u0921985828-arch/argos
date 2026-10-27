package com.eddie.surco.audio

/**
 * Ecualizador para cada salida: unos cascos Bluetooth, los de cable, el altavoz del móvil o un DAC
 * USB suenan distinto y cada uno pide su curva. Surco recuerda la de cada salida y la pone sola al
 * conectarla. Sin Android: se prueba en la JVM.
 */
data class SoundProfile(val dsp: DspParams, val preset: String)

/** Clave de una salida: «speaker», «wired», «bt:Nombre», «usb:Nombre» (el nombre distingue cascos). */
fun outputKey(kind: String, name: String): String = when (kind) {
    "SPEAKER" -> "speaker"
    "WIRED" -> "wired"
    "BLUETOOTH" -> "bt:" + name.trim()
    "USB" -> "usb:" + name.trim()
    else -> "other:" + name.trim()
}

/**
 * Al pasar de la salida [from] a [to]: lo que sonaba se guarda como de [from] y se pone lo de [to]
 * si ya tenía; si es la primera vez que se conecta, sigue con lo de ahora (y desde ya es suyo).
 * Devuelve los perfiles guardados y lo que hay que poner.
 */
fun switchOutput(
    profiles: Map<String, SoundProfile>, current: SoundProfile, from: String?, to: String,
): Pair<Map<String, SoundProfile>, SoundProfile> {
    val saved = if (from != null) profiles + (from to current) else profiles
    val next = saved[to] ?: current
    return (saved + (to to next)) to next
}
