package com.eddie.surco.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.Screen
import com.eddie.surco.audio.ACCENTS
import com.eddie.surco.audio.ACCENT_FROM_ART
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.listenStats
import com.eddie.surco.player.RG_HEADROOM_DB
import java.util.Locale

/** Las páginas de Ajustes: lo que no está a la vista en la app vive aquí, ordenado. */
enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    SOUND("Sonido", "Ecualizador, ReplayGain y calidad de salida", Icons.Default.Equalizer),
    PLAYBACK("Reproducción", "Fundidos, velocidad, temporizador y silencios", Icons.Default.PlayCircle),
    LIBRARY("Biblioteca", "Carpetas, letras de internet y audios cortos", Icons.Default.LibraryMusic),
    LOOK("Interfaz", "Color, Inicio, detalles técnicos y pantalla", Icons.Default.Palette),
    STATS("Tus escuchas", "Lo que más suena, en cifras", Icons.Default.BarChart),
    ABOUT("Acerca de Surco", "Privacidad y versión", Icons.Default.Info),
}

const val SURCO_VERSION = "Surco 2.5"

@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Ajustes", "Todo lo que no está a la vista", onBack = { vm.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp)) {
            SettingsPage.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.push(Screen.Settings(p)) }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Panel), contentAlignment = Alignment.Center) {
                        Icon(p.icon, null, tint = Copper)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(p.subtitle, color = Muted, fontSize = 13.sp, lineHeight = 16.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
                }
            }
        }
    }
}

@Composable
fun SettingsPageScreen(vm: LibraryViewModel, page: SettingsPage) {
    Column(Modifier.fillMaxSize()) {
        TopBar(page.title, page.subtitle, onBack = { vm.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            when (page) {
                SettingsPage.SOUND -> SoundPage(vm)
                SettingsPage.PLAYBACK -> PlaybackPage(vm)
                SettingsPage.LIBRARY -> LibraryPage(vm)
                SettingsPage.LOOK -> LookPage()
                SettingsPage.STATS -> StatsPage(vm)
                SettingsPage.ABOUT -> AboutPage()
            }
        }
    }
}

/** Título de un ajuste con su explicación en una frase. */
@Composable
private fun SettingTitle(title: String, help: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)) {
        Text(title, fontSize = 15.sp)
        if (help != null) Text(help, color = Muted, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = selected == v, onClick = { onSelect(v) },
                label = { Text(label, maxLines = 1, softWrap = false) },
                colors = chipColors(), modifier = Modifier.height(ChipHeight),
            )
        }
    }
}

// ---------- Sonido ----------
@Composable
private fun SoundPage(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    OptionRow(
        "Ecualizador y efectos",
        if (prefs.dsp.eqOn) "${prefs.preset} · tono, balance, crossfeed, limitador" else "Apagado",
    ) { vm.push(Screen.Equalizer) }
    val all by vm.tracks.collectAsState()
    val withRg = remember(all) { all.count { it.x("rg_track").isNotBlank() } }
    SettingTitle(
        "Normalizar volumen (ReplayGain)",
        if (withRg == 0) "Ninguna canción trae la etiqueta REPLAYGAIN (se añade con foobar2000 o MusicBrainz Picard)."
        else "$withRg de ${all.size} canciones la traen; con ella todo suena ${RG_HEADROOM_DB.toInt()} dB más bajo para no saturar.",
    )
    ChipChoice(ReplayGainMode.entries.map { it to it.label }, prefs.replayGain) { m -> SurcoSettings.update { it.copy(replayGain = m) } }
    SettingTitle(
        "Calidad de salida",
        "Sin pérdida (FLAC, ALAC, WAV) en coma flotante, sin recortar a 16 bits; con pérdida (MP3, AAC…) en 16 bits, bit a bit en neutro.",
    )
    outputLine()?.let { Text(it, color = Teal, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
}

// ---------- Reproducción ----------
@Composable
private fun PlaybackPage(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    SettingTitle("Fundido entre canciones", "Baja al final de una y sube al empezar la siguiente.")
    ChipChoice(listOf(0 to "No", 2 to "2 s", 4 to "4 s", 6 to "6 s", 8 to "8 s", 12 to "12 s"), prefs.crossfadeSec) { s ->
        SurcoSettings.update { it.copy(crossfadeSec = s) }
    }
    if (prefs.crossfadeSec > 0) {
        SwitchRow("Respetar los discos sin pausas", "Dentro del mismo disco no se funde: los directos y los discos conceptuales siguen de un tirón.",
            prefs.crossfadeKeepAlbums) { k -> SurcoSettings.update { it.copy(crossfadeKeepAlbums = k) } }
    }
    SwitchRow("Fundido al pausar y reanudar", "Sin golpes de volumen al pulsar pausa.", prefs.fade) { f -> SurcoSettings.update { it.copy(fade = f) } }
    SwitchRow("Saltar silencios", "Recorta los silencios largos dentro de las pistas.", prefs.skipSilence) { k -> SurcoSettings.update { it.copy(skipSilence = k) } }
    OptionRow("Velocidad", "%.2f×".format(Locale("es"), prefs.speed) + if (prefs.keepPitch) " · tono mantenido" else " · tono libre") { vm.speedOpen = true }
    OptionRow("Temporizador de apagado", "Pausa a los minutos que elijas o al acabar la canción") { vm.sleepOpen = true }
}

// ---------- Biblioteca ----------
@Composable
private fun LibraryPage(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    val all by vm.tracks.collectAsState()
    SettingTitle("Ocultar audios cortos", "Notas de voz, tonos y avisos fuera de la biblioteca.")
    ChipChoice(listOf(0 to "No", 30 to "< 30 s", 60 to "< 1 min"), prefs.minDurationSec) { s -> SurcoSettings.update { it.copy(minDurationSec = s) } }
    OptionRow("Volver a leer todas las etiquetas", "Útil si has editado etiquetas con otro programa") { vm.scan(force = true); vm.pop() }
    val analysis by vm.analysis.collectAsState()
    val analyzed = remember(all) { all.count { it.x("bpm_src") == "análisis" || it.x("key_src") == "análisis" } }
    SwitchRow(
        "Analizar BPM y tonalidad",
        analysis?.let { (d, n) -> "Analizando: $d de $n canciones (unos segundos cada una, sin internet)." }
            ?: "Para las canciones que no los traen en las etiquetas. Hechas: $analyzed.",
        prefs.analyze,
    ) { on -> SurcoSettings.update { it.copy(analyze = on) }; if (on) vm.analyzeLibrary() else vm.stopAnalysis() }
    SwitchRow(
        "Letras de internet (LRCLIB)",
        "Si una canción no trae letra, se busca en lrclib.net (muchas, sincronizadas). Solo sale artista, título, álbum y duración.",
        prefs.onlineLyrics,
    ) { on -> SurcoSettings.update { it.copy(onlineLyrics = on) } }
    SectionLabel("Carpetas")
    Text("Las que se apagan no salen en la biblioteca ni en la búsqueda. No se borra nada.",
        color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
    val dirs = rememberOffMain(all, initial = emptyList<Pair<String, Int>>()) {
        all.groupingBy { it.dir }.eachCount().toList().sortedBy { it.first.lowercase() }
    }
    dirs.forEach { (dir, n) ->
        val name = dir.substringAfterLast('/').ifBlank { dir }
        SwitchRow(name, "${dir.substringBeforeLast('/', "")} · ${songs(n)}", dir !in prefs.excludedFolders) { on ->
            SurcoSettings.update { it.copy(excludedFolders = if (on) it.excludedFolders - dir else it.excludedFolders + dir) }
        }
    }
}

// ---------- Interfaz ----------
@Composable
private fun LookPage() {
    val prefs by SurcoSettings.state.collectAsState()
    SettingTitle("Color de acento", "«Del disco» lo saca de la portada que suena, siempre legible.")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ACCENTS.forEach { (name, rgb) -> Swatch(name, Color(0xFF000000.toInt() or rgb), prefs.accent == name) { SurcoSettings.update { it.copy(accent = name) } } }
    }
    ChipChoice(listOf(false to "Color fijo", true to ACCENT_FROM_ART), prefs.accent == ACCENT_FROM_ART) { art ->
        SurcoSettings.update { it.copy(accent = if (art) ACCENT_FROM_ART else if (it.accent == ACCENT_FROM_ART) "Cobre" else it.accent) }
    }
    SectionLabel("«Sonando»")
    SwitchRow("Detalles técnicos", "Formato, bits, frecuencia y cómo sale el audio, debajo del título.", prefs.techDetails) { t ->
        SurcoSettings.update { it.copy(techDetails = t) }
    }
    SwitchRow("Pantalla encendida", "Mientras «Sonando» está abierta (para la letra, por ejemplo).", prefs.keepScreenOn) { k ->
        SurcoSettings.update { it.copy(keepScreenOn = k) }
    }
    SectionLabel("Inicio")
    SwitchRow("Escuchado hace poco", null, prefs.homeRecent) { v -> SurcoSettings.update { it.copy(homeRecent = v) } }
    SwitchRow("Añadido hace poco", null, prefs.homeAdded) { v -> SurcoSettings.update { it.copy(homeAdded = v) } }
    SwitchRow("Lo que más suena", null, prefs.homeTop) { v -> SurcoSettings.update { it.copy(homeTop = v) } }
    SwitchRow("Explorar por etiquetas", null, prefs.homeExplore) { v -> SurcoSettings.update { it.copy(homeExplore = v) } }
}

@Composable
private fun Swatch(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(color)
                .then(if (selected) Modifier.border(3.dp, Paper, CircleShape) else Modifier)
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = name; this.selected = selected },
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Default.Check, null, tint = Ink) }
        Text(name, fontSize = 11.sp, color = if (selected) Paper else Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- Tus escuchas ----------
@Composable
private fun StatsPage(vm: LibraryViewModel) {
    val user by UserStore.state.collectAsState()
    val all by vm.tracks.collectAsState()
    val byId = remember(all) { all.associateBy { it.id } }
    val s = remember(user.plays, byId) { listenStats(user.plays) { id -> byId[id]?.let { it.artist to it.durationMs } } }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Big("${s.plays}", "escuchas", Modifier.weight(1f))
        Big(if (s.minutes >= 120) "${s.minutes / 60} h" else "${s.minutes} min", "de música", Modifier.weight(1f))
        Big("${s.songs}", "canciones", Modifier.weight(1f))
    }
    if (s.plays == 0) {
        Text("Todavía no hay escuchas: una canción cuenta al pasar de la mitad (o de 4 minutos).",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        return
    }
    SectionLabel("Artistas")
    s.topArtists.forEachIndexed { i, (a, n) -> RankRow(i + 1, a, "$n") { all.firstOrNull { it.artist == a }?.let { vm.goToArtist(it) } } }
    SectionLabel("Canciones")
    s.topSongs.forEachIndexed { i, (id, n) ->
        val t = byId[id] ?: return@forEachIndexed
        RankRow(i + 1, t.title, "$n") { vm.player.play(listOf(t), 0) }
    }
}

@Composable
private fun Big(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Panel).padding(vertical = 14.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Copper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(label, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RankRow(rank: Int, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, color = Copper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

// ---------- Acerca de ----------
@Composable
private fun AboutPage() {
    val online = SurcoSettings.state.collectAsState().value.onlineLyrics
    SettingTitle(
        "Privacidad",
        (if (online) "Surco solo usa internet para buscar letras en lrclib.net (artista, título, álbum y duración). "
        else "Surco no usa internet. ") + "Las escuchas, favoritas, listas y ajustes se guardan solo en este móvil.",
    )
    SettingTitle("Versión", SURCO_VERSION)
    SettingTitle("Formatos", "MP3, AAC, FLAC, ALAC, Opus, Vorbis y WAV (también en coma flotante). Letras de la etiqueta LYRICS o de un .lrc al lado.")
}
