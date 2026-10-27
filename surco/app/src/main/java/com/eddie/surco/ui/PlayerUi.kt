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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.filled.GraphicEq
import com.eddie.surco.data.parseKey
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.Player
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.SongSheetState
import com.eddie.surco.audio.AudioOutput
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.Lyrics
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.buckets
import com.eddie.surco.data.describe
import com.eddie.surco.data.isPlaceholder
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
    t.x("bpm").takeIf { it.isNotBlank() }?.let { "${it.replace('.', ',').removeSuffix(",0")} BPM" },
    parseKey(t.x("key"))?.code,
).joinToString(" · ")

/**
 * Cómo sale el audio de Surco hacia el sistema, sin adornos: «Salida: 24 bits (FLAC) → coma
 * flotante · 96 kHz · sin recortar a 16 bits». Si algo cambia la señal (efectos, ReplayGain,
 * velocidad), lo dice. El mezclador de Android puede remuestrear después: eso ya no es de Surco.
 */
@Composable
fun outputLine(track: Track? = null): String? {
    val info by AudioOutput.info.collectAsState()
    val prefs by SurcoSettings.state.collectAsState()
    val i = info ?: return null
    // En el sin cortes la salida se prepara con la siguiente antes de que suene: si no casa con
    // la que suena, mejor no decir nada que decir lo de otra.
    if (track != null && track.sampleRate > 0 && track.sampleRate != i.sampleRate) return null
    val khz = "%.1f kHz".format(Locale("es"), i.sampleRate / 1000f).replace(",0 ", " ")
    val bits = track?.x("bits")?.takeIf { it.isNotBlank() }
    val fileBits = bits?.take(2)?.trim()?.toIntOrNull() ?: 0
    // Si el decodificador del móvil no da float, lo dice en vez de presumir de 24 bits.
    if (!i.float && fileBits > 16) return stringResource(R.string.player_output_decoder16, i.source, khz)
    val src = if (track != null && bits != null && track.format.isNotBlank()) "$bits (${track.format})" else i.source
    val changes = listOfNotNull(
        if (!prefs.dsp.neutral) stringResource(if (i.float) R.string.player_output_effects else R.string.player_output_effects_dither) else null,
        if (prefs.replayGain != com.eddie.surco.audio.ReplayGainMode.OFF) "ReplayGain" else null,
        if (prefs.speed != 1f) "%.2f×".format(Locale("es"), prefs.speed) else null,
    )
    val tail = when {
        changes.isNotEmpty() -> changes.joinToString(", ")
        i.float -> stringResource(R.string.player_output_untruncated)
        else -> stringResource(R.string.player_output_bitperfect)
    }
    return stringResource(if (i.float) R.string.player_output_float else R.string.player_output_plain, src, khz, tail)
}

// ======================= MINI REPRODUCTOR =======================
@Composable
fun MiniPlayer(vm: LibraryViewModel, track: Track) {
    val (pos, dur) = rememberPosition(vm)
    val fav = UserStore.state.collectAsState().value.favorites.contains(track.id)
    val favState = stringResource(if (fav) R.string.player_fav_state_on else R.string.player_fav_state_off)
    val unknown = stringResource(R.string.player_unknown_artist)
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Panel2)) {
            Box(Modifier.fillMaxWidth(if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f).height(2.dp).background(Copper))
        }
        Row(
            Modifier.fillMaxWidth().clickable { vm.nowPlayingOpen = true }
                // Deslizar a los lados cambia de canción, como en la portada de «Sonando».
                .pointerInput(track.id) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { if (acc < -100) vm.player.next() else if (acc > 100) vm.player.prev(); acc = 0f },
                        onDragCancel = { acc = 0f },
                    ) { ch, d -> ch.consume(); acc += d }
                }
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(track, 44.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(track.artist.ifBlank { unknown }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { toggleFavorite(track) }, Modifier.semantics { stateDescription = favState }) {
                Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.player_favorite), tint = if (fav) Copper else Muted)
            }
            IconButton(onClick = { vm.player.toggle() }) {
                Icon(if (vm.player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.player_play_pause))
            }
            IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, stringResource(R.string.player_next)) }
        }
    }
}

// ======================= PANTALLA DE REPRODUCCIÓN =======================
@Composable
fun NowPlaying(vm: LibraryViewModel, track: Track) {
    val (pos, dur) = rememberPosition(vm, 250)
    var dragging by remember { mutableStateOf(false) }
    var dragVal by remember { mutableFloatStateOf(0f) }
    // Saveable: al girar el móvil la actividad se recrea y la letra abierta seguía cerrándose.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val p = vm.player
    val ctx = LocalContext.current
    val prefs by SurcoSettings.state.collectAsState()
    val user by UserStore.state.collectAsState()
    val fav = track.id in user.favorites
    val sleepEnd by SleepTimer.endAt.collectAsState()
    val sleepTrack by SleepTimer.endOfTrack.collectAsState()

    // Sin produceState: lint (K2) no ve la asignación a value y lo da por error. Se queda el color
    // anterior hasta tener el nuevo, para que el fondo cambie con la animación y no pase por el gris.
    // Empieza con el de la caché: al girar, el fondo no pasa por el gris.
    var tint by remember { mutableStateOf(cachedArtColor(track)) }
    LaunchedEffect(track.artKey) { tint = artColor(ctx, track) }
    // Fondo liso y opaco: un toque del color de la portada sobre el fondo de la app. El degradado
    // de antes empezaba transparente y dejaba ver Inicio por debajo.
    val bg by animateColorAsState(tint?.let { lerp(Ink, it, 0.18f) } ?: Ink, tween(600), label = "fondo")

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.nowPlayingOpen = false }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.player_close), Modifier.size(30.dp)) }
            Text(stringResource(R.string.player_now_playing_header), color = Paper.copy(alpha = 0.7f), fontSize = 12.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { vm.sheet = SongSheetState(track, fromPlayer = true) }) { Icon(Icons.Default.MoreVert, stringResource(R.string.player_more)) }
        }
    }
    // La portada (o la letra) a [size]: lo decide el hueco que deja la pantalla, no un tamaño fijo.
    val artBlock: @Composable (Dp) -> Unit = { size ->
        val artMod = Modifier.size(size)
        if (showLyrics) {
            LyricsPanel(vm, track, pos, artMod.clip(RoundedCornerShape(14.dp)).background(Panel.copy(alpha = 0.6f)))
        } else {
            Art(
                track, size, corner = 14.dp,
                // Deslizar a los lados cambia de canción. Solo horizontal: el vertical es de la pantalla,
                // que con letra grande se desplaza (y se cierra con la flecha de arriba o «Atrás»).
                modifier = artMod.pointerInput(track.id) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { if (acc < -120) p.next() else if (acc > 120) p.prev(); acc = 0f },
                        onDragCancel = { acc = 0f },
                    ) { ch, d -> ch.consume(); acc += d }
                },
            )
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — "),
                        color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { vm.goToArtist(track) },
                    )
                }
                IconButton(onClick = { toggleFavorite(track) }) {
                    Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(if (fav) R.string.player_unfavorite else R.string.player_favorite),
                        tint = if (fav) Copper else Muted, modifier = Modifier.size(28.dp))
                }
            }
            // Formato, bits y salida solo si se piden (Ajustes → Interfaz): «Sonando» queda limpia.
            if (prefs.techDetails) {
                val q = qualityLine(track)
                if (q.isNotEmpty()) {
                    Text(q, color = Teal, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                outputLine(track)?.let { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth()) }
            }
            Spacer(Modifier.height(10.dp))
            SurcoSlider(
                value = if (dragging) dragVal else if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f,
                onChange = { dragging = true; dragVal = it },
                onFinished = { p.seek((dragVal * dur).toLong()); dragging = false },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(if (dragging) (dragVal * dur).toLong() else pos), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("−" + fmtTime((dur - pos).coerceAtLeast(0)), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(6.dp))
            val shuffleState = stringResource(if (p.shuffle) R.string.player_state_on else R.string.player_state_off)
            val repeatState = stringResource(
                when (p.repeat) { Player.REPEAT_MODE_ONE -> R.string.player_repeat_one; Player.REPEAT_MODE_ALL -> R.string.player_repeat_all; else -> R.string.player_state_off },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { p.toggleShuffle() }, Modifier.semantics { stateDescription = shuffleState }) {
                    Icon(Icons.Default.Shuffle, stringResource(R.string.player_shuffle), tint = if (p.shuffle) Copper else Muted)
                }
                IconButton(onClick = { p.prev() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(36.dp)) }
                FilledIconButton(
                    onClick = { p.toggle() }, modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Copper, contentColor = Ink),
                ) { Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.player_play_pause), Modifier.size(40.dp)) }
                IconButton(onClick = { p.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, stringResource(R.string.player_next), Modifier.size(36.dp)) }
                IconButton(onClick = { p.cycleRepeat() }, Modifier.semantics {
                    stateDescription = repeatState
                }) {
                    Icon(
                        if (p.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, stringResource(R.string.player_repeat),
                        tint = if (p.repeat == Player.REPEAT_MODE_OFF) Muted else Copper,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Solo lo de cada día: letra y cola. Temporizador, velocidad, ecualizador y etiquetas, en «⋮».
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Default.FormatQuote, stringResource(R.string.player_lyrics), showLyrics) { showLyrics = !showLyrics }
                ToolButton(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_queue), false) { vm.queueOpen = true }
            }
            // Lo que está activo y cambia cómo suena, a la vista y a un toque de quitarlo.
            val stopsAtEnd = stringResource(R.string.player_sleep_pill_end)
            val active = buildList {
                if (sleepTrack) add(Icons.Default.Timer to stopsAtEnd)
                else sleepEnd?.let { add(Icons.Default.Timer to stringResource(R.string.player_sleep_pill_in, sleepRemaining(it))) }
                if (prefs.speed != 1f) add(Icons.Default.Speed to "%.2f×".format(Locale("es"), prefs.speed).replace(",00", ""))
            }
            if (active.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    active.forEach { (icon, label) ->
                        StatusPill(icon, label) { if (icon == Icons.Default.Timer) vm.sleepOpen = true else vm.speedOpen = true }
                    }
                }
            }
        }
    }

    // pointerInput vacío: el fondo se queda los toques de los huecos (en horizontal hay varios); si
    // no, llegaban a Inicio, que está debajo, y podían poner otra canción sin que se viera. Y un
    // nodo de accesibilidad que tapa la pantalla entera: sin él, TalkBack leía (y el volcado de
    // uiautomator traía) lo de Inicio que asomaba por esos huecos.
    val pane = stringResource(R.string.player_now_playing)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(bg).pointerInput(Unit) {}.semantics { paneTitle = pane }
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        val w = maxWidth
        val h = maxHeight
        if (w > h && w >= 560.dp) {
            // En horizontal o en tableta: portada a la izquierda y mandos a la derecha.
            val art = minOf(h - 80.dp, w / 2 - 48.dp, 560.dp).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                header()
                Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { artBlock(art) }
                    Spacer(Modifier.width(32.dp))
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState())) { controls() }
                    }
                }
            }
        } else {
            // En vertical: la portada se queda con lo que dejan la barra y los mandos (que crecen con
            // la letra del sistema), y lo que sobra se reparte arriba y abajo. Si no cabe, se desplaza.
            val chrome = 96.dp + 340.dp * LocalDensity.current.fontScale
            val art = minOf(w - 48.dp, h - chrome, 560.dp).coerceAtLeast(160.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = h).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                header()
                artBlock(art)
                Column(Modifier.widthIn(max = 560.dp).padding(bottom = 16.dp)) { controls() }
            }
        }
    }
}

@Composable
private fun sleepRemaining(end: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
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
    var lyrics by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    // «Buscando» hasta que la búsqueda acaba de verdad (con LRCLIB puede tardar unos segundos).
    var loaded by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) { lyrics = withContext(Dispatchers.IO) { vm.loadLyrics(track) }; loaded = true }
    val online = SurcoSettings.state.collectAsState().value.onlineLyrics
    Box(modifier, contentAlignment = Alignment.Center) {
        val l = lyrics
        if (l == null) {
            Text(
                if (loaded) stringResource(if (online) R.string.player_lyrics_none_online else R.string.player_lyrics_none_offline)
                else stringResource(R.string.player_lyrics_searching),
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

/** Favorita con aviso y «Deshacer»: el corazón cambia, pero un toque sin querer se nota poco. */
private fun toggleFavorite(track: Track) {
    val was = UserStore.isFavorite(track.id)
    UserStore.toggleFavorite(track.id)
    Notice.show(Res.str(if (was) R.string.player_fav_removed else R.string.player_fav_added), Res.str(R.string.player_undo)) { UserStore.setFavorite(track.id, was) }
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
    ) { Box { Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.player_queue), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${songs(q.size)} · ${fmtTotal(q.sumOf { byId[it.trackId]?.durationMs ?: 0L })}", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = { saving = true }, enabled = q.isNotEmpty()) { Text(stringResource(R.string.player_queue_save)) }
            TextButton(onClick = { p.clearUpcoming() }, enabled = q.size > 1) { Text(stringResource(R.string.player_queue_clear)) }
        }
        if (p.shuffle) {
            Text(stringResource(R.string.player_queue_shuffle_note),
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
                val moveUp = stringResource(R.string.player_queue_move_up)
                val moveDown = stringResource(R.string.player_queue_move_down)
                Row(
                    Modifier.fillMaxWidth().height(60.dp).zIndex(if (dragging) 1f else 0f)
                        .semantics {
                            // Con TalkBack no se puede arrastrar: subir y bajar como acciones.
                            if (!p.shuffle) customActions = listOfNotNull(
                                if (pos > 0) CustomAccessibilityAction(moveUp) { p.move(e.index, q[pos - 1].index); true } else null,
                                if (pos < q.lastIndex) CustomAccessibilityAction(moveDown) { p.move(e.index, q[pos + 1].index); true } else null,
                            )
                        }
                        .graphicsLayer { translationY = if (dragging) dragDy else 0f }
                        .background(if (dragging) Panel2 else if (current) Panel else Color.Transparent)
                        .clickable { p.jumpTo(e.index) }
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!p.shuffle) {
                        Icon(
                            Icons.Default.DragHandle, stringResource(R.string.player_queue_drag), tint = Muted,
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
                    if (!current) IconButton(onClick = {
                        p.removeAt(e.index)
                        // En aleatorio no volvería a su sitio del orden: se avisa sin «Deshacer».
                        if (p.shuffle || t == null) Notice.show(Res.str(R.string.player_queue_removed))
                        else Notice.show(Res.str(R.string.player_queue_removed), Res.str(R.string.player_undo)) { p.insertAt(e.index, t) }
                    }) { Icon(Icons.Default.Close, stringResource(R.string.player_queue_remove), tint = Muted) }
                }
            }
        }
    }
    // Dentro de la hoja: si no, «Deshacer» quedaría debajo de ella, sin poder tocarse.
    NoticeHost()
    } }
    if (saving) NameDialog(stringResource(R.string.player_queue_save_title), onDismiss = { saving = false }) { name ->
        val id = UserStore.createPlaylist(name, p.queue.map { it.trackId })
        saving = false
        Notice.show(Res.str(R.string.player_queue_saved, name), Res.str(R.string.player_view)) { vm.queueOpen = false; vm.openPlaylist(id) }
    }
}

// ======================= TEMPORIZADOR Y VELOCIDAD =======================
@Composable
fun SleepDialog(vm: LibraryViewModel) {
    val end by SleepTimer.endAt.collectAsState()
    val atEnd by SleepTimer.endOfTrack.collectAsState()
    val on = end != null || atEnd
    SurcoSheet(
        stringResource(R.string.player_sleep_title), onDismiss = { vm.sleepOpen = false },
        subtitle = when {
            atEnd -> stringResource(R.string.player_sleep_sub_track)
            end != null -> stringResource(R.string.player_sleep_sub_in, sleepRemaining(end!!))
            else -> stringResource(R.string.player_sleep_sub_off)
        },
        // Con uno en marcha, el botón lo quita; si no, solo hay que elegir.
        dismiss = if (on) stringResource(R.string.player_sleep_cancel) else null,
        onDismissButton = { SleepTimer.cancel(); vm.sleepOpen = false; Notice.show(Res.str(R.string.player_sleep_cancelled)) },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                OptionRow(plural(R.plurals.player_minutes, m)) { SleepTimer.start(m); vm.sleepOpen = false; Notice.show(Res.plural(R.plurals.player_sleep_in_minutes, m)) }
            }
            OptionRow(stringResource(R.string.player_sleep_end_of_track)) { SleepTimer.atEndOfTrack(); vm.sleepOpen = false; Notice.show(Res.str(R.string.player_sleep_at_end_notice)) }
        }
    }
}

@Composable
fun SpeedDialog(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    SurcoSheet(stringResource(R.string.player_speed), onDismiss = { vm.speedOpen = false }, dismiss = null, confirm = stringResource(R.string.player_done), onConfirm = { vm.speedOpen = false }) {
        LabeledSlider(stringResource(R.string.player_speed), prefs.speed, 0.5f..2f, "%.2f×".format(Locale("es"), prefs.speed), steps = 29,
            onChange = { v -> SurcoSettings.update { it.copy(speed = (v * 20).roundToInt() / 20f) } },
            onReset = { SurcoSettings.update { it.copy(speed = 1f) } })
        SwitchRow(stringResource(R.string.player_keep_pitch), stringResource(R.string.player_keep_pitch_sub), prefs.keepPitch) { k ->
            SurcoSettings.update { it.copy(keepPitch = k) }
        }
    }
}

// ======================= LISTAS =======================
@Composable
fun AddToPlaylistDialog(vm: LibraryViewModel, tracks: List<Track>) {
    val user by UserStore.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    SurcoSheet(if (tracks.size == 1) stringResource(R.string.player_add_to_playlist) else stringResource(R.string.player_add_songs_to_playlist, songs(tracks.size)), onDismiss = { vm.addTo = null },
        subtitle = if (tracks.size == 1) tracks[0].title else null) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            OptionRow(stringResource(R.string.player_new_playlist_option)) { naming = true }
            user.playlists.forEach { pl ->
                OptionRow(pl.name, songs(pl.trackIds.size)) {
                    UserStore.addToPlaylist(pl.id, tracks.map { it.id }); vm.addTo = null
                    Notice.show(Res.str(R.string.player_added_to, pl.name), Res.str(R.string.player_view)) { vm.openPlaylist(pl.id) }
                }
            }
        }
    }
    if (naming) NameDialog(stringResource(R.string.player_new_playlist), confirm = stringResource(R.string.player_create), onDismiss = { naming = false }) { name ->
        val id = UserStore.createPlaylist(name, tracks.map { it.id })
        naming = false; vm.addTo = null
        Notice.show(Res.str(R.string.player_playlist_created, name), Res.str(R.string.player_view)) { vm.openPlaylist(id) }
    }
}

/** Etiquetas pulsables: cada una abre las canciones que la comparten. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(vm: LibraryViewModel, track: Track, exceptGroup: String? = null) {
    val chips = remember(track, exceptGroup) {
        TagField.entries.filter { it.group != exceptGroup }
            .flatMap { f -> f.buckets(track).filter { !it.isPlaceholder }.map { f to it } }
    }
    // 40 dp de alto y 8 dp de hueco: así los objetivos de toque (48 dp) de dos filas no se pisan.
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (f, b) ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Panel).clickable { vm.openTag(f, b.key, b.label) }
                    .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
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
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = Ink) { Box {
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
            // Desde «Sonando», primero lo de la escucha (a la vista sin desplegar la hoja).
            if (s.fromPlayer) {
                Spacer(Modifier.height(14.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(Icons.Default.Equalizer, stringResource(R.string.player_equalizer)) { close(); vm.nowPlayingOpen = false; vm.push(com.eddie.surco.Screen.Equalizer) }
                    ActionChip(Icons.Default.Timer, stringResource(R.string.player_sleep_title)) { close(); vm.sleepOpen = true }
                    ActionChip(Icons.Default.Speed, stringResource(R.string.player_speed)) { close(); vm.speedOpen = true }
                }
                SectionLabel(stringResource(R.string.player_this_song))
            } else Spacer(Modifier.height(14.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(Icons.Default.SkipNext, stringResource(R.string.player_play_next)) {
                    vm.player.playNext(listOf(track)); close()
                    Notice.show(Res.str(R.string.player_play_next_notice), Res.str(R.string.player_view_queue)) { vm.queueOpen = true }
                }
                ActionChip(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_add_to_queue)) {
                    vm.player.enqueue(listOf(track)); close()
                    Notice.show(Res.str(R.string.player_added_to_queue), Res.str(R.string.player_view_queue)) { vm.queueOpen = true }
                }
                ActionChip(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.player_to_playlist)) { vm.addTo = listOf(track); close() }
                ActionChip(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(if (fav) R.string.player_unfavorite_short else R.string.player_favorite)) {
                    toggleFavorite(track)
                }
                if (track.album.isNotBlank()) ActionChip(Icons.Default.Album, stringResource(R.string.player_go_album)) { close(); vm.goToAlbum(track) }
                if (track.artist.isNotBlank()) ActionChip(Icons.Default.Person, stringResource(R.string.player_go_artist)) { close(); vm.goToArtist(track) }
                // Con BPM o tonalidad (de la etiqueta o del análisis): lo que suena bien detrás.
                if (track.x("bpm").isNotBlank() || track.x("key").isNotBlank()) {
                    ActionChip(Icons.Default.GraphicEq, stringResource(R.string.player_for_mixing)) { close(); vm.nowPlayingOpen = false; vm.push(com.eddie.surco.Screen.Compatible(track.id)) }
                }
                if (s.playlistId != null && s.playlistIndex >= 0) {
                    ActionChip(Icons.Default.Delete, stringResource(R.string.player_remove_from_playlist)) {
                        val pid: Long = s.playlistId; val at = s.playlistIndex
                        UserStore.removeFromPlaylist(pid, at); close()
                        Notice.show(Res.str(R.string.player_removed_from_playlist), Res.str(R.string.player_undo)) { UserStore.insertIntoPlaylist(pid, at, track.id) }
                    }
                }
            }
            val plays = user.plays[track.id] ?: 0
            if (plays > 0) Text(plural(R.plurals.player_played_times, plays), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            SectionLabel(stringResource(R.string.player_organize_by))
            TagChips(vm, track)
            SectionLabel(stringResource(R.string.player_more_info))
            describe(track).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.width(120.dp))
                    Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        NoticeHost()
    } }
}

/** Estado activo (temporizador, velocidad) bajo los mandos de «Sonando»: un toque lo abre. */
@Composable
private fun StatusPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).clickable(onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Copper, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = Copper)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Copper, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
fun PermissionScreen(blocked: Boolean, onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Copper), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.LibraryMusic, null, tint = Ink, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.player_perm_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (blocked) R.string.player_perm_blocked else R.string.player_perm_ask),
            color = Muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(onClick = onAsk) { Text(stringResource(if (blocked) R.string.player_perm_open_settings else R.string.player_perm_grant)) }
    }
}
