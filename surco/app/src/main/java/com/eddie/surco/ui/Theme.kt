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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
fun SurcoTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

// ---------- Portadas ----------
private object NoArt
private val artCache = object : LruCache<Long, Any>(300) {}

private suspend fun loadArt(ctx: android.content.Context, t: Track, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = t.artKey * 10 + if (px > 300) 1 else 0
    artCache.get(key)?.let { return@withContext it as? ImageBitmap }
    val bmp: Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(t.uri, Size(px, px), null)
        else MediaMetadataRetriever().run {
            try { setDataSource(ctx, t.uri); embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { release() }
        }
    }.getOrNull()
    val img = bmp?.asImageBitmap()
    artCache.put(key, img ?: NoArt)
    img
}

@Composable
fun Art(track: Track?, size: Dp, corner: Dp = 6.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = (size.value * 2.5f).toInt().coerceIn(96, 900)
    val img by produceState<ImageBitmap?>(null, track?.artKey, px) {
        value = track?.let { loadArt(ctx, it, px) }
    }
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
