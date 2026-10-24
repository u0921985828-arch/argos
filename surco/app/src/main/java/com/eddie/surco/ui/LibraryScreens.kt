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
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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

private fun SmartList.icon(): ImageVector = when (this) {
    SmartList.FAVORITES -> Icons.Default.Favorite
    SmartList.MOST_PLAYED -> Icons.Default.Star
    SmartList.RECENT -> Icons.Default.History
    SmartList.ADDED -> Icons.Default.NewReleases
    SmartList.NEVER -> Icons.Default.VisibilityOff
}

// ======================= LISTAS =======================
@Composable
fun PlaylistsScreen(vm: LibraryViewModel, tracks: List<Track>) {
    val user by UserStore.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Listas", "${user.playlists.size} propias", onBack = { vm.pop() }) {
            IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Lista nueva") }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionLabel("Automáticas") }
            items(SmartList.entries.toList()) { sl ->
                val n = remember(tracks, user, sl) { sl.tracks(tracks, user).size }
                ListRow(sl.icon(), sl.label, songs(n)) { vm.push(Screen.Smart(sl)) }
            }
            item { SectionLabel("Mis listas") }
            if (user.playlists.isEmpty()) item {
                Text("Todavía no hay listas. Crea una con + o mantén pulsada una canción y elige «A una lista».",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(user.playlists, key = { it.id }) { pl ->
                ListRow(Icons.Default.QueueMusic, pl.name, songs(pl.trackIds.size)) { vm.push(Screen.PlaylistView(pl.id)) }
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
private fun ListRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Copper)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

// ======================= AJUSTES =======================
@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Ajustes", "Sonido, reproducción y biblioteca", onBack = { vm.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionLabel("Sonido")
            OptionRow(
                "Ecualizador y efectos",
                if (prefs.dsp.eqOn) "${prefs.preset} · tono, balance, crossfeed, limitador" else "Apagado",
            ) { vm.push(Screen.Equalizer) }
            Text("Normalizar volumen (ReplayGain)", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text("Iguala el volumen entre canciones con las etiquetas REPLAYGAIN del archivo. «Por álbum» respeta las diferencias dentro de un disco.",
                color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayGainMode.entries.forEach { m ->
                    FilterChip(
                        selected = prefs.replayGain == m, onClick = { SurcoSettings.update { it.copy(replayGain = m) } },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Copper, selectedLabelColor = Ink),
                    )
                }
            }

            SectionLabel("Reproducción")
            SwitchRow("Fundido al pausar y reanudar", "Sin golpes de volumen al pulsar pausa.", prefs.fade) { f -> SurcoSettings.update { it.copy(fade = f) } }
            SwitchRow("Saltar silencios", "Recorta los silencios largos dentro de las pistas.", prefs.skipSilence) { k -> SurcoSettings.update { it.copy(skipSilence = k) } }
            OptionRow("Velocidad", "%.2f×".format(prefs.speed) + if (prefs.keepPitch) " · tono mantenido" else " · tono libre") { vm.speedOpen = true }
            OptionRow("Temporizador de apagado", "Pausa a los minutos que elijas o al acabar la canción") { vm.sleepOpen = true }

            SectionLabel("Biblioteca")
            Text("Ocultar audios cortos", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text("Notas de voz, tonos y avisos fuera de la biblioteca.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "No", 30 to "< 30 s", 60 to "< 1 min").forEach { (sec, label) ->
                    FilterChip(
                        selected = prefs.minDurationSec == sec, onClick = { SurcoSettings.update { it.copy(minDurationSec = sec) } },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Copper, selectedLabelColor = Ink),
                    )
                }
            }
            OptionRow("Volver a leer todas las etiquetas", "Útil si has editado etiquetas con otro programa") { vm.scan(force = true); vm.pop() }

            SectionLabel("Privacidad")
            Text("Surco no usa internet. Las escuchas, favoritas, listas y ajustes se guardan solo en este móvil.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            TextButton(onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Surco 2.0", color = Muted) }
        }
    }
}
