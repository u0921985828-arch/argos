package com.eddie.surco.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.SongSheetState
import com.eddie.surco.audio.AudioOutput
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.Lyrics
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.buckets
import com.eddie.surco.data.describe
import com.eddie.surco.player.SleepTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun rememberPosition(vm: LibraryViewModel, periodMs: Long = 400): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm.player.currentId, vm.player.isPlaying) {
        while (true) {
            pos = vm.player.positionMs; dur = vm.player.durationMs
            delay(periodMs)
        }
    }
    return pos to dur
}

/** «FLAC · 24 bits · 96 kHz · 2.304 kbps» */
fun qualityLine(t: Track): String = listOfNotNull(
    t.format.takeIf { it.isNotBlank() },
    t.x("bits").takeIf { it.isNotBlank() },
    t.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(Locale("es"), it / 1000f).replace(",0 ", " ") },
    t.bitrate.takeIf { it > 0 }?.let { "%,d kbps".format(Locale("es"), it) },
).joinToString(" · ")

/** Cómo sale el audio: «Salida: 24 bits → coma flotante · 96 kHz · sin pérdida». */
@Composable
fun outputLine(): String? {
    val info by AudioOutput.info.collectAsState()
    val prefs by SurcoSettings.state.collectAsState()
    val i = info ?: return null
    val khz = "%.1f kHz".format(Locale("es"), i.sampleRate / 1000f).replace(",0 ", " ")
    return when {
        i.float && i.source.startsWith("coma") -> "Salida: coma flotante 32 bits · $khz · sin pérdida"
        i.float -> "Salida: ${i.source} → coma flotante · $khz · sin pérdida"
        prefs.dsp.neutral -> "Salida: ${i.source} · $khz · bit a bit"
        else -> "Salida: ${i.source} · $khz · efectos con dither"
    }
}

// ======================= MINI REPRODUCTOR =======================
@Composable
fun MiniPlayer(vm: LibraryViewModel, track: Track) {
    val (pos, dur) = rememberPosition(vm)
    val fav = UserStore.state.collectAsState().value.favorites.contains(track.id)
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Panel2)) {
            Box(Modifier.fillMaxWidth(if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f).height(2.dp).background(Copper))
        }
        Row(
            Modifier.fillMaxWidth().clickable { vm.nowPlayingOpen = true }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(track, 44.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(track.artist.ifBlank { "Desconocido" }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { UserStore.toggleFavorite(track.id) }) {
                Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorita", tint = if (fav) Copper else Muted)
            }
            IconButton(onClick = { vm.player.toggle() }) {
                Icon(if (vm.player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir o pausar")
            }
            IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
        }
    }
}

// ======================= PANTALLA DE REPRODUCCIÓN =======================
@Composable
fun NowPlaying(vm: LibraryViewModel, track: Track) {
    val (pos, dur) = rememberPosition(vm, 250)
    var dragging by remember { mutableStateOf(false) }
    var dragVal by remember { mutableFloatStateOf(0f) }
    var showLyrics by remember { mutableStateOf(false) }
    val p = vm.player
    val ctx = LocalContext.current
    val prefs by SurcoSettings.state.collectAsState()
    val user by UserStore.state.collectAsState()
    val fav = track.id in user.favorites
    val sleepEnd by SleepTimer.endAt.collectAsState()
    val sleepTrack by SleepTimer.endOfTrack.collectAsState()

    val tint by produceState<Color?>(null, track.artKey) { value = artColor(ctx, track) }
    val top by animateColorAsState(tint?.copy(alpha = 0.55f) ?: Panel, tween(600), label = "fondo")

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, Ink, Ink)))
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.nowPlayingOpen = false }) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", Modifier.size(30.dp)) }
            Text("SONANDO", color = Paper.copy(alpha = 0.7f), fontSize = 12.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { vm.sheet = SongSheetState(track) }) { Icon(Icons.Default.Sell, "Todas las etiquetas") }
        }
        Spacer(Modifier.height(12.dp))
        val artMod = Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f)
        if (showLyrics) {
            LyricsPanel(vm, track, pos, artMod.clip(RoundedCornerShape(14.dp)).background(Panel.copy(alpha = 0.6f)))
        } else {
            Art(
                track, 340.dp, corner = 14.dp,
                modifier = artMod.pointerInput(track.id) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { if (acc < -120) p.next() else if (acc > 120) p.prev(); acc = 0f },
                        onDragCancel = { acc = 0f },
                    ) { ch, d -> ch.consume(); acc += d }
                },
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — "),
                    color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { vm.goToArtist(track) },
                )
            }
            IconButton(onClick = { UserStore.toggleFavorite(track.id) }) {
                Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Quitar de favoritas" else "Favorita",
                    tint = if (fav) Copper else Muted, modifier = Modifier.size(28.dp))
            }
        }
        val q = qualityLine(track)
        if (q.isNotEmpty()) {
            Text(q, color = Teal, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        outputLine()?.let { Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(10.dp))
        Slider(
            value = if (dragging) dragVal else if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragVal = it },
            onValueChangeFinished = { p.seek((dragVal * dur).toLong()); dragging = false },
            colors = SliderDefaults.colors(thumbColor = Copper, activeTrackColor = Copper, inactiveTrackColor = Panel2),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(if (dragging) (dragVal * dur).toLong() else pos), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("−" + fmtTime((dur - pos).coerceAtLeast(0)), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
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
        Spacer(Modifier.height(10.dp))
        // Herramientas
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ToolButton(Icons.Default.FormatQuote, "Letra", showLyrics) { showLyrics = !showLyrics }
            ToolButton(Icons.Default.QueueMusic, "Cola", false) { vm.queueOpen = true }
            ToolButton(Icons.Default.Equalizer, if (prefs.dsp.eqOn && prefs.dsp.eqActive) prefs.preset else "EQ", prefs.dsp.eqOn && prefs.dsp.eqActive) {
                vm.nowPlayingOpen = false; vm.push(com.eddie.surco.Screen.Equalizer)
            }
            val sleepLabel = when {
                sleepTrack -> "Al final"
                sleepEnd != null -> sleepRemaining(sleepEnd!!)
                else -> "Dormir"
            }
            ToolButton(Icons.Default.Timer, sleepLabel, sleepEnd != null || sleepTrack) { vm.sleepOpen = true }
            ToolButton(Icons.Default.Speed, "%.2f×".format(Locale("es"), prefs.speed).replace(",00", "").replace("0×", "×"), prefs.speed != 1f) { vm.speedOpen = true }
        }
        Spacer(Modifier.height(18.dp))
        TagChips(vm, track)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun sleepRemaining(end: Long): String {
    val now by produceState(System.currentTimeMillis(), end) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val s = ((end - now) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (active) Copper else Paper.copy(alpha = 0.8f))
        Text(label, fontSize = 11.sp, color = if (active) Copper else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp))
    }
}

// ======================= LETRA =======================
@Composable
fun LyricsPanel(vm: LibraryViewModel, track: Track, pos: Long, modifier: Modifier) {
    val lyrics by produceState<Lyrics?>(null, track.id) { value = withContext(Dispatchers.IO) { vm.loadLyrics(track) } }
    var loaded by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) { delay(1500); loaded = true }
    Box(modifier, contentAlignment = Alignment.Center) {
        val l = lyrics
        if (l == null) {
            Text(
                if (loaded) "Esta canción no tiene letra.\n\nSurco lee la etiqueta LYRICS del archivo (también en formato LRC sincronizado) y, si el sistema lo permite, un .lrc con el mismo nombre."
                else "Buscando la letra…",
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
            )
        } else if (l.synced) {
            val idx = l.indexAt(pos + 250)
            val state = rememberLazyListState()
            LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                itemsIndexed(l.lines) { i, line ->
                    Text(
                        line.text.ifBlank { "♪" },
                        color = if (i == idx) Paper else if (i < idx) Muted.copy(alpha = 0.6f) else Muted,
                        fontSize = if (i == idx) 20.sp else 17.sp,
                        fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().clickable { vm.player.seek(line.timeMs) }.padding(vertical = 6.dp),
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                l.lines.forEach { Text(it.text, color = Paper, fontSize = 16.sp, lineHeight = 24.sp) }
            }
        }
    }
}

// ======================= COLA =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: LibraryViewModel, byId: Map<Long, Track>) {
    val p = vm.player
    val q = p.queue
    var saving by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { vm.queueOpen = false }, containerColor = Ink,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cola", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${songs(q.size)} · ${fmtTotal(q.sumOf { byId[it.trackId]?.durationMs ?: 0L })}", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = { saving = true }, enabled = q.isNotEmpty()) { Text("Guardar") }
            TextButton(onClick = { p.clearUpcoming() }, enabled = q.size > 1) { Text("Vaciar") }
        }
        if (p.shuffle) {
            Text("En aleatorio la cola sale en el orden en que va a sonar. Para moverlas, quita el aleatorio.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        val state = rememberLazyListState()
        LaunchedEffect(Unit) {
            val at = q.indexOfFirst { it.index == p.currentIndex }
            if (at > 1) state.scrollToItem(at - 1)
        }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragDy by remember { mutableFloatStateOf(0f) }
        val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(bottom = 16.dp)) {
            itemsIndexed(q, key = { _, e -> e.index }) { pos, e ->
                val t = byId[e.trackId]
                val dragging = dragFrom == pos
                val current = e.index == p.currentIndex
                Row(
                    Modifier.fillMaxWidth().height(60.dp).zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragDy else 0f }
                        .background(if (dragging) Panel2 else if (current) Panel else Color.Transparent)
                        .clickable { p.jumpTo(e.index) }
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!p.shuffle) {
                        Icon(
                            Icons.Default.DragHandle, "Mover", tint = Muted,
                            modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(e.index) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragFrom = pos; dragDy = 0f },
                                    onDragEnd = {
                                        val from = dragFrom
                                        val list = p.queue
                                        if (from != null && list.isNotEmpty()) {
                                            val to = (from + (dragDy / rowPx).roundToInt()).coerceIn(0, list.lastIndex)
                                            p.move(list[from].index, list[to].index)
                                        }
                                        dragFrom = null; dragDy = 0f
                                    },
                                    onDragCancel = { dragFrom = null; dragDy = 0f },
                                ) { ch, dy -> ch.consume(); dragDy += dy }
                            },
                        )
                    } else Spacer(Modifier.width(12.dp))
                    if (t != null) Art(t, 40.dp, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: "—", color = if (current) Copper else Paper, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                        Text(t?.artist.orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!current) IconButton(onClick = { p.removeAt(e.index) }) { Icon(Icons.Default.Close, "Quitar de la cola", tint = Muted) }
                }
            }
        }
    }
    if (saving) NameDialog("Guardar la cola como lista", onDismiss = { saving = false }) { name ->
        UserStore.createPlaylist(name, p.queue.map { it.trackId })
        saving = false
    }
}

// ======================= TEMPORIZADOR Y VELOCIDAD =======================
@Composable
fun SleepDialog(vm: LibraryViewModel) {
    val end by SleepTimer.endAt.collectAsState()
    val atEnd by SleepTimer.endOfTrack.collectAsState()
    AlertDialog(
        onDismissRequest = { vm.sleepOpen = false }, containerColor = Panel,
        title = { Text("Temporizador") },
        text = {
            Column {
                if (end != null || atEnd) {
                    Text(if (atEnd) "Se pausa al acabar esta canción." else "Se pausa en ${sleepRemaining(end!!)} (baja el volumen los últimos 10 s).",
                        color = Copper, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                    OptionRow("$m minutos") { SleepTimer.start(m); vm.sleepOpen = false }
                }
                OptionRow("Al acabar la canción") { SleepTimer.atEndOfTrack(); vm.sleepOpen = false }
            }
        },
        confirmButton = { TextButton(onClick = { vm.sleepOpen = false }) { Text("Cerrar") } },
        dismissButton = {
            if (end != null || atEnd) TextButton(onClick = { SleepTimer.cancel(); vm.sleepOpen = false }) { Text("Quitar") }
        },
    )
}

@Composable
fun SpeedDialog(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    AlertDialog(
        onDismissRequest = { vm.speedOpen = false }, containerColor = Panel,
        title = { Text("Velocidad") },
        text = {
            Column {
                LabeledSlider("Velocidad", prefs.speed, 0.5f..2f, "%.2f×".format(Locale("es"), prefs.speed), steps = 29,
                    onChange = { v -> SurcoSettings.update { it.copy(speed = (v * 20).roundToInt() / 20f) } },
                    onReset = { SurcoSettings.update { it.copy(speed = 1f) } })
                SwitchRow("Mantener el tono", "Si no, sube y baja como un disco al cambiar las revoluciones.", prefs.keepPitch) { k ->
                    SurcoSettings.update { it.copy(keepPitch = k) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.speedOpen = false }) { Text("Hecho") } },
    )
}

// ======================= LISTAS =======================
@Composable
fun AddToPlaylistDialog(vm: LibraryViewModel, tracks: List<Track>) {
    val user by UserStore.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.addTo = null }, containerColor = Panel,
        title = { Text(if (tracks.size == 1) "Añadir a una lista" else "Añadir ${songs(tracks.size)} a una lista") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                OptionRow("+ Lista nueva…") { naming = true }
                user.playlists.forEach { pl ->
                    OptionRow(pl.name, songs(pl.trackIds.size)) { UserStore.addToPlaylist(pl.id, tracks.map { it.id }); vm.addTo = null }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.addTo = null }) { Text("Cancelar") } },
    )
    if (naming) NameDialog("Lista nueva", confirm = "Crear", onDismiss = { naming = false }) { name ->
        UserStore.createPlaylist(name, tracks.map { it.id })
        naming = false; vm.addTo = null
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

// ======================= MENÚ DE UNA CANCIÓN =======================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongSheet(vm: LibraryViewModel, s: SongSheetState) {
    val track = s.track
    val user by UserStore.state.collectAsState()
    val fav = track.id in user.favorites
    fun close() { vm.sheet = null }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = Ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(track, 64.dp, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val q = qualityLine(track)
                    if (q.isNotEmpty()) Text(q, color = Teal, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(Icons.Default.SkipNext, "A continuación") { vm.player.playNext(listOf(track)); close() }
                ActionChip(Icons.Default.QueueMusic, "A la cola") { vm.player.enqueue(listOf(track)); close() }
                ActionChip(Icons.Default.PlaylistAdd, "A una lista") { vm.addTo = listOf(track); close() }
                ActionChip(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Quitar favorita" else "Favorita") {
                    UserStore.toggleFavorite(track.id)
                }
                if (track.album.isNotBlank()) ActionChip(Icons.Default.Album, "Ir al álbum") { close(); vm.goToAlbum(track) }
                if (track.artist.isNotBlank()) ActionChip(Icons.Default.Person, "Ir al artista") { close(); vm.goToArtist(track) }
                if (s.playlistId != null && s.playlistIndex >= 0) {
                    ActionChip(Icons.Default.Delete, "Quitar de la lista") { UserStore.removeFromPlaylist(s.playlistId, s.playlistIndex); close() }
                }
            }
            val plays = user.plays[track.id] ?: 0
            if (plays > 0) Text("Escuchada ${if (plays == 1) "1 vez" else "$plays veces"}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            SectionLabel("Organizar por")
            TagChips(vm, track)
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
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Copper, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
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
