package com.eddie.surco.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Refresh
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

// ======================= INICIO =======================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: LibraryViewModel, tracks: List<Track>, progress: Pair<Int, Int>?) {
    val fields = remember(tracks) { availableFields(tracks) }
    val results = remember(tracks, vm.query) { search(tracks, vm.query) }
    val currentId = vm.player.currentId

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Surco", fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "${songs(tracks.size)} · ${fmtTotal(tracks.sumOf { it.durationMs })}",
                        color = Muted, fontSize = 13.sp,
                    )
                }
                IconButton(onClick = { vm.scan(force = true) }) { Icon(Icons.Default.Refresh, "Volver a leer etiquetas", tint = Muted) }
            }
        }
        item {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Buscar en cualquier etiqueta") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, "Borrar") } },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel, unfocusedBorderColor = Panel),
            )
        }
        if (progress != null) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("Leyendo etiquetas · ${progress.first} de ${progress.second}", color = Teal, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.first / progress.second.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Teal, trackColor = Panel2,
                )
            }
        }

        if (vm.query.isNotBlank()) {
            item { SectionLabel("${songs(results.size)} encontradas") }
            itemsIndexed(results, key = { _, t -> t.id }) { i, t ->
                SongRow(t, null, t.id == currentId, onClick = { vm.player.play(results, i) }, onLong = { vm.sheetTrack = t })
            }
            return@LazyColumn
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Panel).clickable { vm.push(Screen.Songs(null, null, "Todas las canciones")) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Copper), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, null, tint = Ink)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Todas las canciones", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(songs(tracks.size), color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = { vm.player.play(tracks, 0, shuffled = true) }) { Icon(Icons.Default.Shuffle, "Aleatorio", tint = Copper) }
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
        if (tracks.isEmpty() && progress == null) item {
            Text(
                "No se han encontrado canciones en el dispositivo. Copia música a la memoria y pulsa ⟳.",
                color = Muted, modifier = Modifier.padding(24.dp),
            )
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

private fun search(tracks: List<Track>, q: String): List<Track> {
    val s = q.trim().lowercase()
    if (s.isEmpty()) return emptyList()
    return tracks.filter { t ->
        sequenceOf(t.title, t.artist, t.album, t.albumArtist, t.genre, t.composer, t.year.toString(), t.folder)
            .plus(t.extra.values).any { it.lowercase().contains(s) }
    }
}

// ======================= GRUPOS =======================
@Composable
fun GroupsScreen(vm: LibraryViewModel, field: TagField, tracks: List<Track>) {
    val all = remember(tracks, field) { groupTracks(tracks, field) }
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
enum class SongSort(val label: String) { TITLE("Título"), ARTIST("Artista"), ALBUM("Álbum y pista"), YEAR("Año"), DURATION("Duración"), ADDED("Recientes") }

@Composable
fun SongsScreen(vm: LibraryViewModel, s: Screen.Songs, tracks: List<Track>) {
    val list = remember(tracks, s) {
        if (s.field == null || s.key == null) tracks
        else tracks.filter { t -> s.field.buckets(t).any { it.key == s.key } }
    }
    var sort by rememberSaveable(s) { mutableStateOf(if (s.field == TagField.ALBUM) SongSort.ALBUM else SongSort.TITLE) }
    var menu by remember { mutableStateOf(false) }
    val sorted = remember(list, sort) {
        when (sort) {
            SongSort.TITLE -> list.sortedBy { it.title.lowercase() }
            SongSort.ARTIST -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
            SongSort.ALBUM -> list.sortedForAlbum()
            SongSort.YEAR -> list.sortedWith(compareBy({ if (it.year == 0) 99999 else it.year }, { it.album.lowercase() }, { it.trackNo }))
            SongSort.DURATION -> list.sortedByDescending { it.durationMs }
            SongSort.ADDED -> list.sortedByDescending { it.dateAdded }
        }
    }
    val currentId = vm.player.currentId
    Column(Modifier.fillMaxSize()) {
        TopBar(s.title, s.field?.label ?: "Biblioteca", onBack = { vm.pop() }) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SongSort.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.label, color = if (o == sort) Copper else Paper) },
                            onClick = { sort = o; menu = false },
                        )
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(sorted.firstOrNull(), 96.dp, corner = 10.dp, modifier = Modifier.size(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${songs(list.size)} · ${fmtTotal(list.sumOf { it.durationMs })}", color = Muted, fontSize = 13.sp)
                        val artists = remember(list) { list.map { it.artist }.filter { it.isNotBlank() }.distinct() }
                        if (s.field != TagField.ARTIST && artists.isNotEmpty())
                            Text(artists.take(4).joinToString(", ") + if (artists.size > 4) " y ${artists.size - 4} más" else "",
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.player.play(sorted, 0) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Reproducir")
                            }
                            OutlinedButton(onClick = { vm.player.play(sorted, 0, shuffled = true) }, contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Copper)) {
                                Icon(Icons.Default.Shuffle, "Aleatorio", Modifier.size(18.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = Panel2)
            }
            itemsIndexed(sorted, key = { _, t -> t.id }) { i, t ->
                SongRow(t, if (sort == SongSort.ALBUM && t.trackNo > 0) t.trackNo else null, t.id == currentId,
                    onClick = { vm.player.play(sorted, i) }, onLong = { vm.sheetTrack = t })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(t: Track, number: Int?, playing: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 8.dp),
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
    }
}

@Composable
fun TopBar(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        actions()
    }
}
