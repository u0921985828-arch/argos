package com.eddie.surco.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.AudioManager

/**
 * Los efectos de sonido del móvil (Xiaomi Sound, Dolby Atmos, Wavelet…): se les anuncia la sesión
 * de audio de Surco para que la procesen y la reconozcan como música, y se abre su panel desde
 * Ajustes. Van DESPUÉS de Surco, en el mezclador del sistema: se suman a su ecualizador y no
 * suben los bits del archivo.
 */
object SystemEffects {
    /** Sin sesión (el 0 de Android; el C.AUDIO_SESSION_ID_UNSET de Media3 es API inestable). */
    private const val NONE = AudioManager.AUDIO_SESSION_ID_GENERATE

    /** La sesión anunciada (la del reproductor de Surco). */
    @Volatile var session: Int = NONE
        private set

    fun open(ctx: Context, id: Int) {
        if (id == session || id == NONE) return
        close(ctx)
        ctx.sendBroadcast(intent(ctx, AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, id))
        session = id
    }

    fun close(ctx: Context) {
        val id = session.takeIf { it != NONE } ?: return
        ctx.sendBroadcast(intent(ctx, AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, id))
        session = NONE
    }

    private fun intent(ctx: Context, action: String, id: Int) = Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

    /** El panel de efectos del fabricante, o null si el móvil no trae ninguno. */
    fun panel(ctx: Context): Intent? {
        val i = intent(ctx, AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, session)
        return i.takeIf { it.resolveActivity(ctx.packageManager) != null }
    }

    /** Abre el panel; false si no se pudo. */
    fun openPanel(ctx: Context): Boolean {
        val i = panel(ctx) ?: return false
        // Hay fabricantes que lo anuncian y luego no dejan abrirlo.
        return runCatching { ctx.startActivity(i) }.isSuccess
    }
}
