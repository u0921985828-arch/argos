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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayCircleOutline
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.Screen
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.SmartList
import com.eddie.surco.data.Track
import com.eddie.surco.data.UserStore
import com.eddie.surco.player.RG_HEADROOM_DB

private fun SmartList.icon(): ImageVector = when (this) {
    SmartList.FAVORITES -> Icons.Default.Favorite
    SmartList.MOST_PLAYED -> Icons.Default.Star
    SmartList.RECENT -> Icons.Default.History
    SmartList.ADDED -> Icons.Default.AddCircle
    SmartList.NEVER -> Icons.Default.PlayCircleOutline
}

// ======================= LISTAS =======================
@Composable
fun PlaylistsScreen(vm: LibraryViewModel, tracks: List<Track>) {
    val user by UserStore.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // Cabecera de pestaña, como Inicio, Biblioteca y Etiquetas: con TopBar parecía una subpantalla.
        TabHeader("Listas", if (user.playlists.isEmpty()) "Automáticas y tuyas" else "${user.playlists.size} propias") {
            IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Lista nueva", tint = Paper) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionLabel("Automáticas") }
            items(SmartList.entries.toList()) { sl ->
                val n = remember(tracks, user, sl) { sl.tracks(tracks, user).size }
                ListRow(sl.icon(), sl.label, songs(n), dim = n == 0) { vm.push(Screen.Smart(sl)) }
            }
            item { SectionLabel("Mis listas") }
            if (user.playlists.isEmpty()) item {
                Text("Todavía no hay listas. Crea una con + o mantén pulsada una canción y elige «A una lista».",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(user.playlists, key = { it.id }) { pl ->
                ListRow(Icons.AutoMirrored.Filled.QueueMusic, pl.name, songs(pl.trackIds.size)) { vm.push(Screen.PlaylistView(pl.id)) }
            }
        }
    }
    if (creating) NameDialog("Lista nueva", confirm = "Crear", onDismiss = { creating = false }) { name ->
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

