package com.eddie.surco.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import com.eddie.surco.data.letterIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.AlbumSort
import com.eddie.surco.LibraryTab
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.Screen
import com.eddie.surco.SongSheetState
import com.eddie.surco.Tab
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.Group
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.TagField
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.buckets
import com.eddie.surco.data.groupTracks
import com.eddie.surco.data.fold
import com.eddie.surco.data.matchesFolded
import java.util.Calendar
import kotlinx.coroutines.delay

// ======================= BARRA INFERIOR =======================
private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Default.Home
    Tab.LIBRARY -> Icons.Default.LibraryMusic
    Tab.TAGS -> Icons.Default.Sell
    Tab.LISTS -> Icons.AutoMirrored.Filled.QueueMusic
}

@Composable
fun BottomNav(vm: LibraryViewModel) {
    NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = vm.tab == t,
                onClick = { vm.selectTab(t) },
                icon = { Icon(t.icon(), null) },
                label = { Text(t.label, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Ink, indicatorColor = Copper, selectedTextColor = Copper,
                    unselectedIconColor = Muted, unselectedTextColor = Muted,
                ),
            )
        }
    }
}

/** Cabecera de una pestaña: título grande y acciones a la derecha. */
@Composable
fun TabHeader(title: String, subtitle: String?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Paper)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        actions()
    }
}

/** Álbumes (agrupados como en la pestaña Etiquetas) por clave. */
@Composable
private fun rememberAlbums(tracks: List<Track>): Map<String, Group> =
    rememberOffMain(tracks, initial = emptyMap()) { groupTracks(tracks, TagField.ALBUM).associateBy { it.bucket.key } }

private fun albumKey(t: Track) = TagField.ALBUM.buckets(t).firstOrNull()?.key

/** «Otra Banda, Banda Surco y 2 más»: un nombre real antes que «Varios artistas». */
fun albumArtist(g: Group): String {
    val names = g.tracks.map { it.albumArtist.ifBlank { it.artist } }.filter { it.isNotBlank() }.distinct()
    return when {
        names.isEmpty() -> "Desconocido"
        names.size <= 2 -> names.joinToString(", ")
        else -> names.take(2).joinToString(", ") + " y ${names.size - 2} más"
    }
}

fun sortAlbums(list: List<Group>, sort: AlbumSort): List<Group> = when (sort) {
    AlbumSort.TITLE -> list.sortedBy { fold(it.bucket.label) }
    AlbumSort.ARTIST -> list.sortedWith(compareBy({ fold(albumArtist(it)) }, { it.tracks.maxOf { t -> t.year } }, { fold(it.bucket.label) }))
    AlbumSort.YEAR -> list.sortedWith(compareByDescending<Group> { it.tracks.maxOf { t -> t.year } }.thenBy { fold(it.bucket.label) })
    AlbumSort.ADDED -> list.sortedByDescending { it.tracks.maxOf { t -> t.dateAdded } }
}

private fun List<Track>.inAlbumOrder() = sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title.lowercase() }))

@Composable
private fun AlbumCard(vm: LibraryViewModel, g: Group, size: Dp = 148.dp, modifier: Modifier = Modifier) {
    Column(modifier.width(size).clip(RoundedCornerShape(12.dp)).clickable { vm.openTag(TagField.ALBUM, g.bucket.key, g.bucket.label) }) {
        Box {
            Art(g.tracks.first(), size, corner = 12.dp, modifier = Modifier.size(size))
            FilledIconButton(
                onClick = { vm.player.play(g.tracks.inAlbumOrder(), 0) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Copper, contentColor = Ink),
            ) { Icon(Icons.Default.PlayArrow, "Reproducir ${g.bucket.label}") }
        }
        Text(g.bucket.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        Text(albumArtist(g), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 6..13 -> "Buenos días"
    in 14..20 -> "Buenas tardes"
    else -> "Buenas noches"
}

// ======================= INICIO =======================
@Composable
fun HomeScreen(vm: LibraryViewModel, tracks: List<Track>, byId: Map<Long, Track>, progress: Pair<Int, Int>?) {
    val user by UserStore.state.collectAsState()
    val prefs by SurcoSettings.state.collectAsState()
    val albums = rememberAlbums(tracks)
    val recent = rememberOffMain(tracks, user.lastPlayed, albums, initial = emptyList()) {
        tracks.filter { it.id in user.lastPlayed }.sortedByDescending { user.lastPlayed[it.id] ?: 0 }
            .mapNotNull { albumKey(it) }.distinct().mapNotNull { albums[it] }.take(12)
    }
    val added = rememberOffMain(tracks, albums, initial = emptyList()) {
        tracks.sortedByDescending { it.dateAdded }.mapNotNull { albumKey(it) }.distinct().mapNotNull { albums[it] }.take(12)
    }
    val top = rememberOffMain(tracks, user.plays, initial = emptyList()) { SmartList.MOST_PLAYED.tracks(tracks, user).take(5) }
    val resume = user.queue.ids.getOrNull(user.queue.index)?.let { byId[it] }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TabHeader(greeting(), "${songs(tracks.size)} · ${fmtTotal(tracks.sumOf { it.durationMs })}") {
                IconButton(onClick = { vm.push(Screen.Settings()) }) { Icon(Icons.Default.Settings, "Ajustes", tint = Paper) }
            }
        }
        // Sin canciones, el aviso y nada más: un «Mezclar todo» que no hace nada despista.
        if (tracks.isEmpty() && progress == null) {
            item {
                Text(
                    "No hay canciones en el móvil todavía. Copia música a la memoria y vuelve a abrir Surco (o Ajustes → Volver a leer).",
                    color = Muted, modifier = Modifier.padding(24.dp),
                )
            }
            return@LazyColumn
        }
        item { SearchPill(vm) }
        // Debajo del buscador: si apareciera encima, empujaría el buscador justo cuando se va a tocar.
        if (progress != null) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Leyendo etiquetas · ${progress.first} de ${progress.second}", color = Teal, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.first / progress.second.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Teal, trackColor = Panel2,
                )
            }
        }
        // Seguir donde se dejó: lo primero, porque es lo que más se hace al abrir.
        // Solo cuando no hay minirreproductor: con una canción cargada en pausa salían los dos, iguales.
        if (resume != null && vm.player.currentId == null) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(Panel).clickable { vm.player.resume(byId) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(resume, 64.dp, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Seguir escuchando", color = Copper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(resume.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(resume.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(
                    onClick = { vm.player.resume(byId) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Copper, contentColor = Ink),
                ) { Icon(Icons.Default.PlayArrow, "Seguir") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Icons.Default.Shuffle, "Mezclar todo", Modifier.weight(1f)) { vm.player.play(tracks, 0, shuffled = true) }
                QuickAction(Icons.Default.Favorite, "Favoritas", Modifier.weight(1f)) { vm.push(Screen.Smart(SmartList.FAVORITES)) }
                QuickAction(Icons.Default.Star, "Más escuchadas", Modifier.weight(1f)) { vm.push(Screen.Smart(SmartList.MOST_PLAYED)) }
            }
        }
        // Cada sección se puede quitar en Ajustes → Interfaz → Inicio.
        if (prefs.homeRecent && recent.isNotEmpty()) {
            item { SectionLabel("Escuchado hace poco") }
            item { AlbumRow(vm, recent) }
        }
        if (prefs.homeAdded && added.isNotEmpty()) {
            item { SectionLabel("Añadido hace poco") }
            item { AlbumRow(vm, added) }
        }
        if (prefs.homeTop && top.isNotEmpty()) {
            item { SectionLabel("Lo que más suena") }
            itemsIndexed(top, key = { _, t -> "top-${t.id}" }) { i, t ->
                SongRow(t, null, t.id == vm.player.currentId, onClick = { vm.player.play(top, i) }, onLong = { vm.sheet = SongSheetState(t) })
            }
        }
        if (prefs.homeExplore) item {
            SectionLabel("Explorar por etiquetas")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 40 dp de alto (los chips de Material miden 32): es lo que se toca con el pulgar.
                listOf(TagField.GENRE, TagField.BPM, TagField.KEY, TagField.DECADE, TagField.MOOD, TagField.QUALITY, TagField.FOLDER).forEach { f ->
                    FilterChip(selected = false, onClick = { vm.push(Screen.Groups(f)) }, label = { Text(f.label) }, colors = chipColors(), modifier = Modifier.height(ChipHeight))
                }
                FilterChip(selected = false, onClick = { vm.selectTab(Tab.TAGS) }, label = { Text("Todas…") }, colors = chipColors(), modifier = Modifier.height(ChipHeight))
            }
        }
    }
}

/** Buscador siempre a la vista: un toque y se escribe (abre la búsqueda con el teclado). */
@Composable
private fun SearchPill(vm: LibraryViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(Panel).clickable { vm.push(Screen.Search) }.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Muted)
        Spacer(Modifier.width(10.dp))
        Text("Buscar en cualquier etiqueta", color = Muted, fontSize = 15.sp)
    }
}

@Composable
private fun AlbumRow(vm: LibraryViewModel, list: List<Group>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.bucket.key }) { g -> AlbumCard(vm, g) }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Copper)
        Spacer(Modifier.height(4.dp))
        // Dos líneas: con la letra grande del sistema «Más escuchadas» se cortaba.
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ======================= BIBLIOTECA =======================
@Composable
fun LibraryScreen(vm: LibraryViewModel, tracks: List<Track>) {
    val sort = vm.albumSort
    val albums = rememberOffMain(tracks, sort, initial = emptyList()) { sortAlbums(groupTracks(tracks, TagField.ALBUM), sort) }
    val artists = rememberOffMain(tracks, initial = emptyList()) { groupTracks(tracks, TagField.ARTIST).sortedBy { fold(it.bucket.label) } }
    val folders = rememberOffMain(tracks, initial = emptyList()) { groupTracks(tracks, TagField.FOLDER).sortedBy { fold(it.bucket.label) } }
    val songsSorted = rememberOffMain(tracks, initial = emptyList()) { tracks.sortedBy { fold(it.title) } }
    Column(Modifier.fillMaxSize()) {
        TabHeader("Biblioteca", "${songs(tracks.size)} · ${albums.size} álbumes · ${artists.size} artistas")
        SearchPill(vm)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTab.entries.forEach { t ->
                // Sin recuento en el chip: con letra grande «Carpetas» quedaba fuera de pantalla.
                FilterChip(
                    selected = vm.libraryTab == t, onClick = { vm.libraryTab = t },
                    label = { Text(t.label) },
                    colors = chipColors(), modifier = Modifier.height(ChipHeight),
                )
            }
        }
        when (vm.libraryTab) {
            LibraryTab.SONGS -> WithLetters(songsSorted.map { it.title }, headerItems = 1) { state ->
              LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAction(Icons.Default.PlayArrow, "Reproducir", Modifier.weight(1f)) { vm.player.play(songsSorted, 0) }
                        QuickAction(Icons.Default.Shuffle, "Aleatorio", Modifier.weight(1f)) { vm.player.play(songsSorted, 0, shuffled = true) }
                    }
                }
                itemsIndexed(songsSorted, key = { _, t -> t.id }) { i, t ->
                    SongRow(t, null, t.id == vm.player.currentId, onClick = { vm.player.play(songsSorted, i) }, onLong = { vm.sheet = SongSheetState(t) })
                }
              }
            }
            LibraryTab.ALBUMS -> LazyVerticalGrid(
                GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlbumSort.entries.forEach { o ->
                            FilterChip(selected = vm.albumSort == o, onClick = { vm.albumSort = o }, label = { Text(o.label) }, colors = chipColors(), modifier = Modifier.height(ChipHeight))
                        }
                    }
                }
                if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Sin álbumes.", color = Muted) }
                items(albums, key = { it.bucket.key }) { g -> AlbumCard(vm, g, size = 150.dp) }
            }
            LibraryTab.ARTISTS -> GroupList(vm, TagField.ARTIST, artists, round = true)
            LibraryTab.FOLDERS -> GroupList(vm, TagField.FOLDER, folders, round = false)
        }
    }
}

@Composable
private fun GroupList(vm: LibraryViewModel, field: TagField, groups: List<Group>, round: Boolean) = WithLetters(groups.map { it.bucket.label }) { state ->
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(groups, key = { it.bucket.key }) { g ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openTag(field, g.bucket.key, g.bucket.label) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(g.tracks.first(), 52.dp, corner = if (round) 26.dp else 8.dp, modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.bucket.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${songs(g.tracks.size)} · ${fmtTotal(g.totalMs)}", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = { vm.player.play(g.tracks.inAlbumOrder(), 0) }) { Icon(Icons.Default.PlayArrow, "Reproducir", tint = Muted) }
            }
        }
    }
}

/**
 * Lista con índice de letras a la derecha (desde 40 elementos: en una lista corta solo estorba).
 * Se toca o se arrastra por las letras y la lista salta a la primera de cada una.
 */
@Composable
private fun WithLetters(labels: List<String>, headerItems: Int = 0, list: @Composable (LazyListState) -> Unit) {
    val state = rememberLazyListState()
    val index = remember(labels) { letterIndex(labels) }
    val show = labels.size >= 40 && index.size > 1
    Box(Modifier.fillMaxSize()) {
        // Con índice, la lista deja libre su franja: si no, las letras tapan el «⋮» de cada fila.
        Box(Modifier.fillMaxSize().padding(end = if (show) 28.dp else 0.dp)) { list(state) }
        if (show) {
            val scope = rememberCoroutineScope()
            val letters = index.keys.toList()
            var picked by remember { mutableStateOf<Char?>(null) }
            fun pick(y: Float, h: Int) {
                val c = letters[((y / h.coerceAtLeast(1)) * letters.size).toInt().coerceIn(0, letters.lastIndex)]
                if (c != picked) { picked = c; scope.launch { state.scrollToItem(index.getValue(c) + headerItems) } }
            }
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.9f).width(28.dp)
                    .semantics { contentDescription = "Índice de letras" }
                    // Con la clave del índice entero: al releer la biblioteca, las posiciones cambian.
                    .pointerInput(index) {
                        detectTapGestures { pick(it.y, size.height); picked = null }
                    }
                    .pointerInput(index) {
                        detectVerticalDragGestures(onDragEnd = { picked = null }, onDragCancel = { picked = null }) { ch, _ -> ch.consume(); pick(ch.position.y, size.height) }
                    },
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Tamaño fijo, sin la escala de letra del sistema: 28 letras tienen que caber en la columna.
                val size = with(LocalDensity.current) { 11.dp.toSp() }
                letters.forEach { c -> Text("$c", fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, color = if (c == picked) Copper else Muted) }
            }
            // La letra en grande mientras se arrastra: se ve a dónde se va.
            picked?.let { c ->
                Box(Modifier.align(Alignment.Center).size(72.dp).clip(RoundedCornerShape(16.dp)).background(Panel2), contentAlignment = Alignment.Center) {
                    Text("$c", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Copper)
                }
            }
        }
    }
}

// ======================= BUSCAR =======================
@Composable
fun SearchScreen(vm: LibraryViewModel, tracks: List<Track>) {
    // Índice de búsqueda plegado una vez por biblioteca; el filtrado, fuera del hilo principal.
    val index = rememberOffMain(tracks, initial = emptyList<Pair<Track, String>>()) { tracks.map { it to searchHay(it) } }
    val q = vm.query
    val results = rememberOffMain(index, q, initial = emptyList()) {
        if (q.isBlank()) emptyList() else index.filter { matchesFolded(it.second, q) }.map { it.first }
    }
    val albums = rememberAlbums(tracks)
    val albumHits = remember(results, albums) { results.mapNotNull { albumKey(it) }.distinct().mapNotNull { albums[it] }.take(10) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val req = remember { FocusRequester() }
    // Al abrir la búsqueda, el teclado listo (tras un fotograma: antes el campo aún no está colocado).
    LaunchedEffect(Unit) { delay(120); runCatching { req.requestFocus() }; keyboard?.show() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                modifier = Modifier.weight(1f).focusRequester(req),
                placeholder = { Text("Buscar en cualquier etiqueta", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, "Borrar") } },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel, unfocusedBorderColor = Muted.copy(alpha = 0.6f)),
            )
        }
        if (vm.query.isBlank()) {
            Text("Título, artista, álbum, género, año, carpeta… y también estado de ánimo, sello, compositor, comentario o cualquier otra etiqueta de texto. Sin importar tildes ni mayúsculas.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (albumHits.isNotEmpty() && albumHits.size < results.size) {
                item { SectionLabel("Álbumes") }
                item { AlbumRow(vm, albumHits) }
            }
            item { SectionLabel("${songs(results.size)} encontradas") }
            itemsIndexed(results, key = { _, t -> t.id }) { i, t ->
                SongRow(t, null, t.id == vm.player.currentId,
                    onClick = {
                        keyboard?.hide(); focus.clearFocus()
                        // Se reproduce su disco desde ella, en su orden; si no tiene disco, los resultados.
                        val key = albumKey(t)
                        val album = albums[key]?.tracks?.inAlbumOrder()
                        if (key != null && album != null && album.size > 1 && t.album.isNotBlank()) vm.player.play(album, album.indexOfFirst { it.id == t.id }.coerceAtLeast(0))
                        else vm.player.play(results, i)
                    },
                    onLong = { keyboard?.hide(); focus.clearFocus(); vm.sheet = SongSheetState(t) })
            }
        }
    }
}
