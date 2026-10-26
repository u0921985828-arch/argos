package com.eddie.surco.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.util.SizeF
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import androidx.palette.graphics.Palette
import com.eddie.surco.MainActivity
import com.eddie.surco.R
import com.eddie.surco.audio.ACCENTS
import com.eddie.surco.audio.ACCENT_FROM_ART
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.legibleAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Lo que enseña el widget. [trackId] null = no hay nada preparado. */
data class WidgetState(val trackId: Long?, val title: String, val artist: String, val playing: Boolean)

/**
 * Widget de la pantalla de inicio: portada, título, artista y mandos, en tres tamaños (2×1, 4×1
 * y 4×2, con favorita). Lo actualiza el servicio cuando cambia la canción o se pausa; con el
 * servicio parado enseña la última canción de la cola, y «reproducir» la reanuda.
 */
class SurcoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Con el servicio parado: la última canción de la cola (leída fuera del hilo principal).
        val pending = goAsync()
        scope.launch {
            try { push(context, last ?: lastSaved(context)) } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Antes de Android 12 el diseño se elige aquí, según el tamaño que le da el lanzador.
        val pending = goAsync()
        scope.launch { try { push(context, last ?: lastSaved(context)) } finally { pending.finish() } }
    }

    companion object {
        const val ACTION_FAV = "com.eddie.surco.widget.FAVORITA"
        const val EXTRA_ID = "id"
        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Lo último que publicó el servicio (en el mismo proceso). */
        @Volatile var last: WidgetState? = null

        /** Desde el servicio: canción o estado nuevos. */
        fun publish(ctx: Context, s: WidgetState) {
            if (s == last) return
            last = s
            val app = ctx.applicationContext
            scope.launch { push(app, s) }
        }

        /** Que se repinte (al marcar una favorita desde la app, por ejemplo). */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            scope.launch { push(app, last ?: lastSaved(app)) }
        }

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SurcoWidget::class.java))

        internal fun lastSaved(ctx: Context): WidgetState {
            UserStore.init(ctx)
            val q = UserStore.state.value.queue
            val id = q.ids.getOrNull(q.index) ?: return WidgetState(null, "Surco", "Toca para elegir música", false)
            val t = runCatching { LibraryScanner(ctx).loadCache().firstOrNull { it.id == id } }.getOrNull()
                ?: return WidgetState(null, "Surco", "Toca para elegir música", false)
            return WidgetState(t.id, t.title, t.artist.ifBlank { "Desconocido" }, false)
        }

        /** Arma los tres diseños y los manda a todos los widgets. */
        fun push(ctx: Context, s: WidgetState) {
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            SurcoSettings.init(ctx)
            val art = s.trackId?.let { loadArt(ctx, it) }
            val accent = accentFor(art)
            val fav = s.trackId != null && run { UserStore.init(ctx); s.trackId in UserStore.state.value.favorites }
            val small = views(ctx, R.layout.widget_small, s, art, accent, fav)
            val wide = views(ctx, R.layout.widget_wide, s, art, accent, fav)
            val big = views(ctx, R.layout.widget_big, s, art, accent, fav)
            val m = AppWidgetManager.getInstance(ctx)
            for (id in ids) {
                val rv = if (Build.VERSION.SDK_INT >= 31) {
                    // El lanzador elige el diseño que cabe (también al redimensionar).
                    RemoteViews(mapOf(SizeF(110f, 40f) to small, SizeF(250f, 40f) to wide, SizeF(250f, 110f) to big))
                } else {
                    val o = m.getAppWidgetOptions(id)
                    val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40)
                    when { h >= 110 && w >= 250 -> big; w >= 250 -> wide; else -> small }
                }
                m.updateAppWidget(id, rv)
            }
        }

        internal fun views(ctx: Context, layout: Int, s: WidgetState, art: Bitmap?, accent: Int, fav: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, layout)
            v.setTextViewText(R.id.w_title, s.title)
            v.setTextViewText(R.id.w_artist, s.artist)
            if (art != null) v.setImageViewBitmap(R.id.w_art, art) else v.setImageViewResource(R.id.w_art, android.R.color.transparent)
            v.setImageViewResource(R.id.w_play, if (s.playing) R.drawable.w_pause else R.drawable.w_play)
            v.setInt(R.id.w_play_bg, "setColorFilter", accent)
            v.setContentDescription(R.id.w_play_box, if (s.playing) "Pausar" else "Reproducir")
            v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_play_box, mediaKey(ctx, if (s.playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY))
            if (layout != R.layout.widget_small) {
                v.setOnClickPendingIntent(R.id.w_prev, mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                v.setOnClickPendingIntent(R.id.w_next, mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            if (layout == R.layout.widget_big) {
                v.setImageViewResource(R.id.w_fav, if (fav) R.drawable.w_fav else R.drawable.w_fav_border)
                if (fav) v.setInt(R.id.w_fav, "setColorFilter", accent)
                v.setContentDescription(R.id.w_fav, if (fav) "Quitar de favoritas" else "Favorita")
                s.trackId?.let { id ->
                    val i = Intent(ctx, WidgetFavorite::class.java).setAction(ACTION_FAV).putExtra(EXTRA_ID, id)
                    v.setOnClickPendingIntent(R.id.w_fav, PendingIntent.getBroadcast(ctx, 40, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            return v
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 41, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Las teclas multimedia van al receptor de Media3: con el servicio parado, «reproducir»
         * lo arranca y reanuda la última cola (onPlaybackResumption); con él en marcha, se la pasa.
         */
        @OptIn(UnstableApi::class)
        private fun mediaKey(ctx: Context, code: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(ctx, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun loadArt(ctx: Context, id: Long): Bitmap? = runCatching {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            else MediaMetadataRetriever().run {
                try {
                    setDataSource(ctx, uri)
                    embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { Bitmap.createScaledBitmap(it, 256, 256, true) }
                } finally { release() }
            }
        }.getOrNull()

        /** El acento de Ajustes; «Del disco», de la portada (legible sobre el fondo del widget). */
        private fun accentFor(art: Bitmap?): Int {
            val name = SurcoSettings.state.value.accent
            val rgb = ACCENTS[name] ?: if (name == ACCENT_FROM_ART && art != null) {
                val c = Palette.from(art).generate().getDominantColor(0xE08A4B)
                legibleAccent(c and 0xFFFFFF, 0x20242C)
            } else ACCENTS.getValue("Cobre")
            return 0xFF000000.toInt() or rgb
        }
    }
}

/**
 * La favorita del widget grande. Receptor aparte y sin exportar: el del widget tiene que estar
 * abierto al sistema, y así ninguna otra app puede marcar favoritas por su cuenta.
 */
class WidgetFavorite : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SurcoWidget.ACTION_FAV) return
        val id = intent.getLongExtra(SurcoWidget.EXTRA_ID, -1)
        if (id < 0) return
        UserStore.init(context)
        UserStore.toggleFavorite(id)
        val pending = goAsync()
        SurcoWidget.scope.launch {
            try { SurcoWidget.push(context, SurcoWidget.last ?: SurcoWidget.lastSaved(context)) } finally { pending.finish() }
        }
    }
}
