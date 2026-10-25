package com.eddie.surco.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.eddie.surco.SongSheetState
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.UserStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.Screen
import com.eddie.surco.data.Group
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.availableFields
import com.eddie.surco.data.buckets
import com.eddie.surco.data.groupTracks
import com.eddie.surco.data.SEARCHABLE_EXTRA
import com.eddie.surco.data.haystack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ======================= ETIQUETAS =======================
/** La seña de Surco: la biblioteca organizada por cualquiera de sus etiquetas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsScreen(vm: LibraryViewModel, tracks: List<Track>) {
    val fields = rememberOffMain(tracks, initial = emptyList()) { availableFields(tracks) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text("Etiquetas", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                Text("Tu música ordenada por lo que dicen sus archivos: personas, obra, tiempo y calidad.", color = Muted, fontSize = 13.sp)
            }
        }
        fields.groupBy { it.first.group }.forEach { (section, list) ->
            item(key = "sec-$section") { SectionLabel(section) }
            item(key = "grid-$section") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    list.forEach { (field, groups) -> FieldCard(field, groups, Modifier.weight(1f)) { vm.push(Screen.Groups(field)) } }
                    if (list.size % 2 == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (tracks.isEmpty()) item {
            Text("Cuando haya canciones en el móvil, aquí salen todas sus etiquetas.", color = Muted, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun FieldCard(field: TagField, groups: List<Group>, modifier: Modifier, onClick: () -> Unit) {
    val top = remember(groups) { groups.sortedByDescending { it.tracks.size }.take(3).joinToString(" · ") { it.bucket.label } }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(field.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${groups.size}", color = Copper, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(top, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
    }
}

@Composable
fun SectionLabel(s: String) = Text(
    s.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
)

/** Campos en los que se busca: solo texto (no «24 bits», «No», «-6.48»…). */
fun searchHay(t: Track): String = haystack(
    listOf(t.title, t.artist, t.album, t.albumArtist, t.genre, t.composer, t.folder) +
        (if (t.year > 0) listOf(t.year.toString()) else emptyList()) + SEARCHABLE_EXTRA.map { t.x(it) },
)

/** Calcula fuera del hilo principal (agrupar 10.000 canciones no debe congelar la interfaz). */
@Composable
fun <T> rememberOffMain(vararg keys: Any?, initial: T, calc: () -> T): T {
    var v by remember { mutableStateOf(initial) }
    LaunchedEffect(*keys) { v = withContext(Dispatchers.Default) { calc() } }
    return v
}

// ======================= GRUPOS =======================
@Composable
fun GroupsScreen(vm: LibraryViewModel, field: TagField, tracks: List<Track>) {
    val all = rememberOffMain(tracks, field, initial = emptyList()) { groupTracks(tracks, field) }
    var byCount by rememberSaveable(field) { mutableStateOf(false) }
    val numeric = remember(all) { all.any { it.bucket.order != 0L && it.bucket.order != Long.MAX_VALUE } }
    val sorted = remember(all, byCount) {
        when {
            byCount -> all.sortedByDescending { it.tracks.size }
            numeric -> all.sortedBy { it.bucket.order }
            else -> all.sortedWith(compareBy({ it.bucket.order }, { it.bucket.label.lowercase() }))
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(field.label, "${all.size} grupos", onBack = { vm.pop() })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byCount, onClick = { byCount = false }, label = { Text(if (numeric) "Por orden" else "A – Z") })
            FilterChip(selected = byCount, onClick = { byCount = true }, label = { Text("Más canciones") })
        }
        val showArt = field in setOf(TagField.ALBUM, TagField.ARTIST, TagField.ALBUM_ARTIST, TagField.FOLDER)
        val max = remember(all) { all.maxOfOrNull { it.tracks.size } ?: 1 }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(sorted, key = { it.bucket.key }) { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.push(Screen.Songs(field, g.bucket.key, g.bucket.label)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showArt) { Art(g.tracks.first(), 48.dp, corner = if (field == TagField.ALBUM) 6.dp else 24.dp, modifier = Modifier.size(48.dp)); Spacer(Modifier.width(14.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(g.bucket.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = if (field == TagField.ALBUM) albumArtistOf(g) + " · " else ""
                        Text("$sub${songs(g.tracks.size)} · ${fmtTotal(g.totalMs)}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!showArt) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Panel2)) {
                                Box(Modifier.fillMaxWidth(g.tracks.size / max.toFloat()).height(3.dp).background(Copper.copy(alpha = 0.7f)))
                            }
                        }
                    }
                    IconButton(onClick = { vm.player.play(g.tracks.sortedForAlbum(), 0) }) { Icon(Icons.Default.PlayArrow, "Reproducir", tint = Muted) }
                }
            }
        }
    }
}

private fun albumArtistOf(g: Group): String {
    val names = g.tracks.map { it.albumArtist.ifBlank { it.artist } }.distinct()
    return if (names.size == 1) names[0].ifBlank { "Desconocido" } else "Varios artistas"
}

private fun List<Track>.sortedForAlbum() = sortedWith(compareBy({ it.album.lowercase() }, { it.discNo }, { it.trackNo }, { it.title.lowercase() }))

// ======================= CANCIONES =======================
enum class SongSort(val label: String) {
    CUSTOM("Orden de la lista"), TITLE("Título"), ARTIST("Artista"), ALBUM("Álbum y pista"), YEAR("Año"),
    DURATION("Duración"), ADDED("Recientes"), PLAYS("Más escuchadas"),
}

@Composable
fun SongsScreen(vm: LibraryViewModel, s: Screen.Songs, tracks: List<Track>) {
    val list = remember(tracks, s) {
        if (s.field == null || s.key == null) tracks
        else tracks.filter { t -> s.field.buckets(t).any { it.key == s.key } }
    }
    SongListScreen(vm, s.title, s.field?.label ?: "Biblioteca", list, if (s.field == TagField.ALBUM) SongSort.ALBUM else SongSort.TITLE, custom = false)
}

@Composable
fun SmartScreen(vm: LibraryViewModel, sl: SmartList, tracks: List<Track>) {
    val user by UserStore.state.collectAsState()
    val list = remember(tracks, user, sl) { sl.tracks(tracks, user) }
    SongListScreen(vm, sl.label, "Lista automática", list, if (sl == SmartList.FAVORITES || sl == SmartList.NEVER) SongSort.TITLE else SongSort.CUSTOM, custom = true)
}

@Composable
fun PlaylistScreen(vm: LibraryViewModel, id: Long, byId: Map<Long, Track>) {
    val user by UserStore.state.collectAsState()
    val pl = user.playlists.firstOrNull { it.id == id }
    if (pl == null) { LaunchedEffect(Unit) { vm.pop() }; return }
    // Las que ya no están en el móvil no salen, pero se recuerda su posición real en la lista.
    val entries = remember(pl, byId) { pl.trackIds.withIndex().mapNotNull { (i, id) -> byId[id]?.let { i to it } } }
    val list = remember(entries) { entries.map { it.second } }
    val sourceIndex = remember(entries) { entries.map { it.first } }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    SongListScreen(vm, pl.name, "Lista · ${songs(list.size)}", list, SongSort.CUSTOM, custom = true, playlistId = id,
        sourceIndex = sourceIndex,
        menu = { close ->
            DropdownMenuItem(text = { Text("Cambiar el nombre") }, onClick = { close(); renaming = true })
            DropdownMenuItem(text = { Text("Borrar la lista") }, onClick = { close(); deleting = true })
        })
    if (renaming) NameDialog("Nombre de la lista", initial = pl.name, onDismiss = { renaming = false }) { n -> UserStore.renamePlaylist(id, n); renaming = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, containerColor = Panel,
        title = { Text("¿Borrar «${pl.name}»?") },
        text = { Text("Las canciones no se tocan; solo desaparece la lista.") },
        confirmButton = { TextButton(onClick = { deleting = false; UserStore.deletePlaylist(id); vm.pop() }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
    )
}

/** Lista de canciones con orden, reproducir, aleatorio, a la cola y a una lista. */
@Composable
fun SongListScreen(
    vm: LibraryViewModel, title: String, subtitle: String, list: List<Track>, initialSort: SongSort,
    custom: Boolean, playlistId: Long? = null, sourceIndex: List<Int>? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var sort by rememberSaveable(title, subtitle) { mutableStateOf(initialSort) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val user by UserStore.state.collectAsState()
    val sorted: List<IndexedValue<Track>> = remember(list, sort, user.plays) {
        val l = list.withIndex().toList()
        when (sort) {
            SongSort.CUSTOM -> l
            SongSort.TITLE -> l.sortedBy { it.value.title.lowercase() }
            SongSort.ARTIST -> l.sortedWith(compareBy({ it.value.artist.lowercase() }, { it.value.album.lowercase() }, { it.value.trackNo }))
            SongSort.ALBUM -> l.sortedWith(compareBy({ it.value.album.lowercase() }, { it.value.discNo }, { it.value.trackNo }, { it.value.title.lowercase() }))
            SongSort.YEAR -> l.sortedWith(compareBy({ if (it.value.year == 0) 99999 else it.value.year }, { it.value.album.lowercase() }, { it.value.trackNo }))
            SongSort.DURATION -> l.sortedByDescending { it.value.durationMs }
            SongSort.ADDED -> l.sortedByDescending { it.value.dateAdded }
            SongSort.PLAYS -> l.sortedByDescending { user.plays[it.value.id] ?: 0 }
        }
    }
    val tracksSorted = remember(sorted) { sorted.map { it.value } }
    val currentId = vm.player.currentId
    Column(Modifier.fillMaxSize()) {
        TopBar(title, subtitle, onBack = { vm.pop() }) {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SongSort.entries.filter { custom || it != SongSort.CUSTOM }.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.label, color = if (o == sort) Copper else Paper) },
                            onClick = { sort = o; sortMenu = false },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Todo a la cola") }, onClick = { moreMenu = false; vm.player.enqueue(tracksSorted) }, enabled = list.isNotEmpty())
                    DropdownMenuItem(text = { Text("Todo a una lista") }, onClick = { moreMenu = false; vm.addTo = tracksSorted }, enabled = list.isNotEmpty())
                    menu?.invoke { moreMenu = false }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(tracksSorted.firstOrNull(), 96.dp, corner = 10.dp, modifier = Modifier.size(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${songs(list.size)} · ${fmtTotal(list.sumOf { it.durationMs })}", color = Muted, fontSize = 13.sp)
                        val artists = remember(list) { list.map { it.artist }.filter { it.isNotBlank() }.distinct() }
                        if (artists.isNotEmpty())
                            Text(artists.take(4).joinToString(", ") + if (artists.size > 4) " y ${artists.size - 4} más" else "",
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.player.play(tracksSorted, 0) }, enabled = list.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Reproducir")
                            }
                            OutlinedButton(onClick = { vm.player.play(tracksSorted, 0, shuffled = true) }, enabled = list.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Copper)) {
                                Icon(Icons.Default.Shuffle, "Aleatorio", Modifier.size(18.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = Panel2)
                if (list.isEmpty()) Text(
                    if (playlistId != null) "Lista vacía. Mantén pulsada una canción y elige «A una lista»." else "Nada por aquí todavía.",
                    color = Muted, modifier = Modifier.padding(24.dp),
                )
            }
            itemsIndexed(sorted, key = { _, iv -> "${iv.index}-${iv.value.id}" }) { i, iv ->
                val t = iv.value
                SongRow(t, if (sort == SongSort.ALBUM && t.trackNo > 0) t.trackNo else null, t.id == currentId,
                    onClick = { vm.player.play(tracksSorted, i) },
                    onLong = { vm.sheet = SongSheetState(t, playlistId, if (playlistId != null) sourceIndex?.getOrNull(iv.index) ?: iv.index else -1) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(t: Track, number: Int?, playing: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong, onLongClickLabel = "Más opciones")
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text("$number", color = if (playing) Copper else Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.width(30.dp))
        } else {
            Art(t, 46.dp, modifier = Modifier.size(46.dp)); Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (playing) Copper else Paper, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
            Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Desconocido" },
                color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(fmtTime(t.durationMs), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        // El menú (a continuación, a la cola, a una lista…) también a la vista, no solo con pulsación larga.
        IconButton(onClick = onLong) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Muted) }
    }
}

@Composable
fun TopBar(title: String, subtitle: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
        else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        actions()
    }
}
