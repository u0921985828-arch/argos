package com.eddie.surco.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

val Ink = Color(0xFF101216)
val Panel = Color(0xFF181B21)
val Panel2 = Color(0xFF20242C)
val Copper = Color(0xFFE08A4B)
val Teal = Color(0xFF6FB3A8)
val Paper = Color(0xFFECE7E1)
val Muted = Color(0xFF8E939C)

private val Scheme = darkColorScheme(
    primary = Copper, onPrimary = Ink, secondary = Teal, onSecondary = Ink,
    background = Ink, onBackground = Paper, surface = Ink, onSurface = Paper,
    surfaceVariant = Panel, onSurfaceVariant = Muted, surfaceContainer = Panel,
    surfaceContainerHigh = Panel2, surfaceContainerLow = Panel, outline = Color(0xFF2E333C),
)

@Composable
fun SurcoTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme) {
    // Fuera del Scaffold (la pantalla «Sonando» y la de permiso) el color de contenido por defecto
    // es NEGRO: el título salía ilegible sobre el fondo oscuro. Medido en el recorrido de diseño.
    CompositionLocalProvider(LocalContentColor provides Paper, content = content)
}

// ---------- Portadas ----------
private object NoArt
/** Por bytes, no por número: con «Sonando» cada portada grande son ~3 MB. Tope: 1/8 de la memoria. */
private val artCache = object : LruCache<Long, Any>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: Long, value: Any): Int = (value as? ImageBitmap)?.asAndroidBitmap()?.byteCount ?: 64
}

/** Tamaños de 128, 256, 512 y 1024 px: una miniatura nunca se reutiliza estirada en grande. */
private fun sizeBucket(px: Int) = when { px <= 128 -> 0; px <= 256 -> 1; px <= 512 -> 2; else -> 3 }
private fun bucketPx(b: Int) = 128 shl b

private suspend fun loadArt(ctx: android.content.Context, t: Track, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val b = sizeBucket(px)
    val key = t.artKey * 10 + b
    artCache.get(key)?.let { return@withContext it as? ImageBitmap }
    val bmp: Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(t.uri, Size(bucketPx(b), bucketPx(b)), null)
        else MediaMetadataRetriever().run {
            try { setDataSource(ctx, t.uri); embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { release() }
        }
    }.getOrNull()
    val img = bmp?.asImageBitmap()
    artCache.put(key, img ?: NoArt)
    img
}

private val colorCache = LruCache<Long, Any>(200)

/** Color dominante de la portada (para el fondo de «Sonando»), o null si no hay portada. */
suspend fun artColor(ctx: android.content.Context, t: Track): Color? = withContext(Dispatchers.Default) {
    colorCache.get(t.artKey)?.let { return@withContext it as? Color }
    val c = runCatching {
        val img = loadArt(ctx, t, 128) ?: return@runCatching null
        var bmp = img.asAndroidBitmap()
        if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val sw = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
        sw?.let { Color(it.rgb) }
    }.getOrNull()
    colorCache.put(t.artKey, c ?: NoArt)
    c
}

@Composable
fun Art(track: Track?, size: Dp, corner: Dp = 6.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = (size.value * 2.5f).toInt().coerceIn(96, 900)
    // Sin produceState: lint no ve la asignación a value y lo da por error. Como antes, la portada
    // anterior se queda hasta tener la nueva.
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track?.artKey, px) { img = track?.let { loadArt(ctx, it, px) } }
    val shape = RoundedCornerShape(corner)
    Box(modifier.then(Modifier.clip(shape)), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) {
            Image(i, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            val seed = (track?.album?.ifBlank { track.title } ?: "")
            val hue = (abs(seed.hashCode()) % 360).toFloat()
            Box(
                Modifier.matchParentSize().background(Color.hsl(hue, 0.25f, 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    seed.firstOrNull()?.uppercase() ?: "♪",
                    color = Color.hsl(hue, 0.5f, 0.72f),
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.4f).sp,
                )
            }
        }
    }
}

// ---------- Formato ----------
fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

fun fmtTotal(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

fun songs(n: Int) = if (n == 1) "1 canción" else "$n canciones"
