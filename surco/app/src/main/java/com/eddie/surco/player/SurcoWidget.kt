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
import android.util.Log
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
import com.eddie.surco.audio.ACCENT_SYSTEM
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.LibraryScanner
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.legibleAccent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        // Un solo hilo: dos estados seguidos (reproducir y pausar, canción y su título) no se
        // pueden aplicar al revés. Un fallo del widget no tira el servicio de música.
        @kotlin.OptIn(ExperimentalCoroutinesApi::class)
        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> Log.w("SurcoWidget", "no se pudo actualizar el widget", e) })

        /** Lo último que publicó el servicio (en el mismo proceso). */
        @Volatile var last: WidgetState? = null

        /** Desde el servicio: canción o estado nuevos. */
        fun publish(ctx: Context, s: WidgetState) {
            if (s == last) return
            last = s
            val app = ctx.applicationContext
            // Se lee `last` al pintar: si llegan varios seguidos, se pinta el último.
            scope.launch { last?.let { push(app, it) } }
        }

        /** Que se repinte (al marcar una favorita desde la app, por ejemplo). */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            scope.launch { push(app, last ?: lastSaved(app)) }
        }

        // Sin widgets en el aparato (sin FEATURE_APP_WIDGETS) getInstance da null.
        private fun ids(ctx: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(ctx)?.getAppWidgetIds(ComponentName(ctx, SurcoWidget::class.java)) }.getOrNull() ?: IntArray(0)

        private var saved: Pair<Long?, WidgetState>? = null

        internal fun lastSaved(ctx: Context): WidgetState {
            UserStore.init(ctx)
            val q = UserStore.state.value.queue
            val id = q.ids.getOrNull(q.index)
            val empty = WidgetState(null, ctx.getString(R.string.app_name), ctx.getString(R.string.widget_empty), false)
            // La caché de la biblioteca solo se lee si la canción guardada cambió.
            saved?.let { (sid, st) -> if (sid == id) return st }
            val st = id?.let { i -> runCatching { LibraryScanner(ctx).loadCache().firstOrNull { it.id == i } }.getOrNull() }
                ?.let { t -> WidgetState(t.id, t.title, t.artist.ifBlank { ctx.getString(R.string.w_unknown) }, false) } ?: empty
            saved = id to st
            return st
        }

        private var art: Triple<Long?, Bitmap?, Int>? = null

        /** Arma los tres diseños y los manda a todos los widgets. */
        fun push(ctx: Context, s: WidgetState) {
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            SurcoSettings.init(ctx)
            // Portada y acento, una vez por canción (no en cada reproducir/pausar).
            val key = s.trackId?.let { it * 31 + SurcoSettings.state.value.accent.hashCode() }
            val (_, cover, accent) = art?.takeIf { it.first == key } ?: s.trackId.let { id ->
                val b = id?.let { loadArt(ctx, it) }
                Triple(key, b, accentFor(ctx, b)).also { art = it }
            }
            val fav = s.trackId != null && run { UserStore.init(ctx); s.trackId in UserStore.state.value.favorites }
            val small = views(ctx, R.layout.widget_small, s, cover, accent, fav)
            val wide = views(ctx, R.layout.widget_wide, s, cover, accent, fav)
            val big = views(ctx, R.layout.widget_big, s, cover, accent, fav)
            val m = AppWidgetManager.getInstance(ctx) ?: return
            for (id in ids) {
                val rv = if (Build.VERSION.SDK_INT >= 31) {
                    // El lanzador elige el diseño que cabe (también al redimensionar).
                    RemoteViews(mapOf(SizeF(110f, 40f) to small, SizeF(250f, 40f) to wide, SizeF(250f, 124f) to big))
                } else {
                    val o = m.getAppWidgetOptions(id)
                    val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40)
                    when { h >= 124 && w >= 250 -> big; w >= 250 -> wide; else -> small }
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
            v.setContentDescription(R.id.w_play_box, ctx.getString(if (s.playing) R.string.w_pause else R.string.w_play))
            v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
            // Sin nada que reanudar, «reproducir» abre Surco: un servicio sin cola no puede ponerse
            // en primer plano y Android lo cerraría a los pocos segundos.
            v.setOnClickPendingIntent(R.id.w_play_box, if (s.trackId == null) openApp(ctx) else playPause(ctx))
            if (layout != R.layout.widget_small) {
                v.setOnClickPendingIntent(R.id.w_prev, serviceKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                v.setOnClickPendingIntent(R.id.w_next, serviceKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            if (layout == R.layout.widget_big) {
                // Todo se fija siempre: al repintar, el lanzador reaplica sobre las vistas de antes.
                v.setImageViewResource(R.id.w_fav, if (fav) R.drawable.w_fav else R.drawable.w_fav_border)
                v.setInt(R.id.w_fav, "setColorFilter", if (fav) accent else 0xFFECE7E1.toInt())
                v.setContentDescription(R.id.w_fav, ctx.getString(if (fav) R.string.w_unfav else R.string.w_fav))
                val id = s.trackId
                v.setOnClickPendingIntent(R.id.w_fav, if (id == null) openApp(ctx) else PendingIntent.getBroadcast(
                    ctx, 40, Intent(ctx, WidgetFavorite::class.java).setAction(ACTION_FAV).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            }
            return v
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 41, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Reproducir/pausar por el receptor de Media3: con el servicio parado lo arranca y reanuda
         * la última cola (onPlaybackResumption); con él en marcha, le pasa la tecla. El receptor
         * solo deja pasar PLAY y PLAY_PAUSE (las demás las tira): por eso es PLAY_PAUSE.
         */
        @OptIn(UnstableApi::class)
        private fun playPause(ctx: Context): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(ctx, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            return PendingIntent.getBroadcast(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Anterior y siguiente, directos al servicio (como los botones de la notificación de Media3). */
        private fun serviceKey(ctx: Context, code: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(ctx, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getService(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        private fun accentFor(ctx: Context, art: Bitmap?): Int {
            val name = SurcoSettings.state.value.accent
            val rgb = ACCENTS[name] ?: if (name == ACCENT_SYSTEM && Build.VERSION.SDK_INT >= 31) {
                // Material You: el acento del sistema, como en la app.
                legibleAccent(ctx.getColor(android.R.color.system_accent1_200) and 0xFFFFFF, 0x20242C)
            } else if (name == ACCENT_FROM_ART && art != null) {
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
