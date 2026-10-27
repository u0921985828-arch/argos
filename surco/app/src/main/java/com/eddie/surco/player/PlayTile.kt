package com.eddie.surco.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.eddie.surco.MainActivity
import com.eddie.surco.R

/**
 * Mosaico de ajustes rápidos: reproducir o pausar sin abrir Surco, con lo que suena de título.
 * Encendido mientras suena. Sin nada en la cola, abre la app.
 */
class PlayTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null || c.mediaItemCount == 0) {
                c?.release()
                openApp()
                return@addListener
            }
            if (c.isPlaying) c.pause() else c.play()
            val playing = !c.isPlaying
            c.release()
            update(playing)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    /** Lo que publica el servicio para el widget: canción y si suena. */
    private fun update(playing: Boolean? = null) {
        val t = qsTile ?: return
        val s = SurcoWidget.last
        val on = playing ?: (s?.playing == true)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = s?.title ?: getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = s?.artist
        t.contentDescription = getString(if (on) R.string.w_pause else R.string.w_play)
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat_surco)
        t.updateTile()
    }
}
