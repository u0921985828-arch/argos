package com.eddie.surco.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.R
import com.eddie.surco.Screen
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.player.RG_HEADROOM_DB

private fun SmartList.icon(): ImageVector = when (this) {
    SmartList.FAVORITES -> Icons.Rounded.Favorite
    SmartList.MOST_PLAYED -> Icons.Rounded.Star
    SmartList.RECENT -> Icons.Rounded.History
    SmartList.ADDED -> Icons.Rounded.AddCircle
    SmartList.NEVER -> Icons.Rounded.PlayCircleOutline
}

// ======================= LISTAS =======================
@Composable
fun PlaylistsScreen(vm: LibraryViewModel, tracks: List<Track>) {
    val user by UserStore.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val importM3u = rememberM3uImport(vm)
    Column(Modifier.fillMaxSize()) {
        // Cabecera de pestaña, como Inicio, Biblioteca y Etiquetas: con TopBar parecía una subpantalla.
        TabHeader(
            stringResource(R.string.library_lists_title),
            if (user.playlists.isEmpty()) stringResource(R.string.library_lists_subtitle) else plural(R.plurals.library_own_lists, user.playlists.size),
        ) {
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.library_new_playlist), tint = Paper) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionLabel(stringResource(R.string.library_smart_section)) }
            items(SmartList.entries.toList()) { sl ->
                val n = remember(tracks, user, sl) { sl.tracks(tracks, user).size }
                ListRow(sl.icon(), sl.label, songs(n), dim = n == 0) { vm.push(Screen.Smart(sl)) }
            }
            item { SectionLabel(stringResource(R.string.library_my_lists)) }
            if (user.playlists.isEmpty()) item {
                Text(stringResource(R.string.library_lists_empty),
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(user.playlists, key = { it.id }) { pl ->
                ListRow(Icons.AutoMirrored.Rounded.QueueMusic, pl.name, songs(pl.trackIds.size)) { vm.push(Screen.PlaylistView(pl.id)) }
            }
            // Para quien llega de otro reproductor o de un PC: sus listas M3U/M3U8, casadas con esta biblioteca.
            item {
                ListRow(Icons.Rounded.FolderOpen, stringResource(R.string.m3u_import), stringResource(R.string.m3u_import_hint), onClick = importM3u)
            }
        }
    }
    if (creating) NameDialog(stringResource(R.string.library_new_playlist), confirm = stringResource(R.string.library_create), onDismiss = { creating = false }) { name ->
        val id = UserStore.createPlaylist(name)
        creating = false
        vm.push(Screen.PlaylistView(id))
    }
}

@Composable
private fun ListRow(icon: ImageVector, title: String, subtitle: String, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Copper)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (dim) Muted else Paper)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

