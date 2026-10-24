package com.eddie.surco.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.buckets
import com.eddie.surco.data.describe
import kotlinx.coroutines.delay

@Composable
private fun rememberPosition(vm: LibraryViewModel): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm.player.currentId, vm.player.isPlaying) {
        while (true) {
            pos = vm.player.positionMs; dur = vm.player.durationMs
            delay(400)
        }
    }
    return pos to dur
}

// ======================= MINI REPRODUCTOR =======================
@Composable
fun MiniPlayer(vm: LibraryViewModel, track: Track) {
    val (pos, dur) = rememberPosition(vm)
    Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Panel2)) {
            Box(Modifier.fillMaxWidth(if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f).height(2.dp).background(Copper))
        }
        Row(
            Modifier.fillMaxWidth().clickable { vm.nowPlayingOpen = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(track, 44.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(track.artist.ifBlank { "Desconocido" }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.player.toggle() }) {
                Icon(if (vm.player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir o pausar")
            }
            IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
        }
    }
}

// ======================= PANTALLA DE REPRODUCCIÓN =======================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlaying(vm: LibraryViewModel, track: Track) {
    val (pos, dur) = rememberPosition(vm)
    var dragging by remember { mutableStateOf(false) }
    var dragVal by remember { mutableFloatStateOf(0f) }
    val p = vm.player

    Column(
        Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.nowPlayingOpen = false }) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", Modifier.size(30.dp)) }
            Text("Sonando", color = Muted, fontSize = 13.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { vm.sheetTrack = track }) { Icon(Icons.Default.Sell, "Todas las etiquetas") }
        }
        Spacer(Modifier.height(16.dp))
        Art(track, 340.dp, corner = 14.dp, modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(24.dp))
        Text(track.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(
            listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — "),
            color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Slider(
            value = if (dragging) dragVal else if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragVal = it },
            onValueChangeFinished = { p.seek((dragVal * dur).toLong()); dragging = false },
            colors = SliderDefaults.colors(thumbColor = Copper, activeTrackColor = Copper, inactiveTrackColor = Panel2),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(if (dragging) (dragVal * dur).toLong() else pos), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(dur), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { p.toggleShuffle() }) { Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (p.shuffle) Copper else Muted) }
            IconButton(onClick = { p.prev() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Anterior", Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = { p.toggle() }, modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Copper, contentColor = Ink),
            ) { Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir o pausar", Modifier.size(40.dp)) }
            IconButton(onClick = { p.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Siguiente", Modifier.size(36.dp)) }
            IconButton(onClick = { p.cycleRepeat() }) {
                Icon(
                    if (p.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repetir",
                    tint = if (p.repeat == Player.REPEAT_MODE_OFF) Muted else Copper,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        TagChips(vm, track)
        Spacer(Modifier.height(24.dp))
    }
}

/** Etiquetas pulsables: cada una abre las canciones que la comparten. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(vm: LibraryViewModel, track: Track) {
    val chips = remember(track) {
        TagField.entries.flatMap { f -> f.buckets(track).filter { it.label != "Desconocido" && it.label != "Sin año" }.map { f to it } }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (f, b) ->
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Panel).clickable { vm.openTag(f, b.key, b.label) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(f.label + " ", color = Muted, fontSize = 12.sp)
                Text(b.label, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ======================= FICHA DE ETIQUETAS =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSheet(vm: LibraryViewModel, track: Track) {
    ModalBottomSheet(onDismissRequest = { vm.sheetTrack = null }, containerColor = Ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(track, 64.dp, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SectionLabel("Organizar por")
            Box(Modifier.padding(horizontal = 0.dp)) { TagChips(vm, track) }
            SectionLabel("Más datos")
            describe(track).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.width(120.dp))
                    Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Copper))
        Spacer(Modifier.height(24.dp))
        Text("Surco necesita acceso a tu música", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Lee las etiquetas de tus archivos de audio para organizarlos. Nada sale del móvil.", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(onClick = onAsk) { Text("Dar permiso") }
    }
}
