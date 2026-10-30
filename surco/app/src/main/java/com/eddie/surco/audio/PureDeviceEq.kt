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

/** Lo del ecualizador por salida en los ajustes: lo guardado de cada una, de cuál es lo de ahora y lo de ahora. */
data class OutputState(val profiles: Map<String, SoundProfile>, val key: String?, val sound: SoundProfile)

/**
 * La salida pasa a ser [to]. Con un ecualizador por salida, se cambia como dice [switchOutput]; sin
 * él, solo se apunta cuál es (la curva es la misma para todas).
 */
fun arrive(s: OutputState, to: String, perOutput: Boolean): OutputState =
    if (!perOutput || s.key == to) s.copy(key = to)
    else switchOutput(s.profiles, s.sound, s.key, to).let { (profiles, next) -> OutputState(profiles, to, next) }

/**
 * Se enciende el ecualizador por salida: si la de ahora ya tenía curva, vuelve la suya (lo tocado
 * con el interruptor apagado era de todas, no de esta); si no, lo de ahora pasa a ser suyo.
 */
fun enablePerOutput(s: OutputState): OutputState {
    val k = s.key ?: return s
    return s.profiles[k]?.let { s.copy(sound = it) } ?: s.copy(profiles = s.profiles + (k to s.sound))
}

/** Si cambia lo que se oye (dos curvas apagadas o planas suenan igual aunque sus bandas no lo sean). */
fun audibleChange(a: DspParams, b: DspParams): Boolean = a != b && !(a.neutral && b.neutral)

/** Al borrar el preajuste [name], lo guardado con ese nombre pasa a «personalizado» (las bandas siguen). */
fun forgetPreset(profiles: Map<String, SoundProfile>, name: String, custom: String): Map<String, SoundProfile> =
    profiles.mapValues { (_, v) -> if (v.preset == name) v.copy(preset = custom) else v }
