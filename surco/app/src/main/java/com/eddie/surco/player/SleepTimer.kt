package com.eddie.surco.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Temporizador de apagado. Lo pone la interfaz y lo cumple el servicio (mismo proceso). */
object SleepTimer {
    /** Hora (ms del reloj del sistema) a la que se pausa, o null. */
    private val _endAt = MutableStateFlow<Long?>(null)
    val endAt: StateFlow<Long?> = _endAt

    /** Pausar al terminar la canción que suena. */
    private val _endOfTrack = MutableStateFlow(false)
    val endOfTrack: StateFlow<Boolean> = _endOfTrack

    fun start(minutes: Int) { _endOfTrack.value = false; _endAt.value = System.currentTimeMillis() + minutes * 60_000L }
    fun atEndOfTrack() { _endAt.value = null; _endOfTrack.value = true }
    fun cancel() { _endAt.value = null; _endOfTrack.value = false }
    val active: Boolean get() = _endAt.value != null || _endOfTrack.value
}
