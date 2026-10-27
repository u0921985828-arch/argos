package com.eddie.surco.ui

import androidx.annotation.StringRes
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import com.eddie.surco.R
import com.eddie.surco.Screen
import com.eddie.surco.audio.ACCENTS
import com.eddie.surco.audio.ACCENT_FROM_ART
import com.eddie.surco.audio.ACCENT_SYSTEM
import com.eddie.surco.player.Scrobbler
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import com.eddie.surco.audio.ReplayGainMode
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.audio.accentLabelRes
import com.eddie.surco.audio.presetLabelRes
import com.eddie.surco.data.UserStore
import com.eddie.surco.data.listenStats
import com.eddie.surco.player.RG_HEADROOM_DB

/** Las páginas de Ajustes: lo que no está a la vista en la app vive aquí, ordenado. */
enum class SettingsPage(@StringRes val title: Int, @StringRes val subtitle: Int, val icon: ImageVector) {
    SOUND(R.string.settings_sound_title, R.string.settings_sound_sub, Icons.Default.Equalizer),
    PLAYBACK(R.string.settings_playback_title, R.string.settings_playback_sub, Icons.Default.PlayCircle),
    LIBRARY(R.string.settings_library_title, R.string.settings_library_sub, Icons.Default.LibraryMusic),
    LOOK(R.string.settings_look_title, R.string.settings_look_sub, Icons.Default.Palette),
    STATS(R.string.settings_stats_title, R.string.settings_stats_sub, Icons.Default.BarChart),
    ABOUT(R.string.settings_about_title, R.string.settings_about_sub, Icons.Default.Info),
}

const val SURCO_VERSION = "Surco 2.6"

@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle), onBack = { vm.pop() })
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
                        Text(stringResource(p.title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(p.subtitle), color = Muted, fontSize = 13.sp, lineHeight = 16.sp)
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
        TopBar(stringResource(page.title), stringResource(page.subtitle), onBack = { vm.pop() })
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
        stringResource(R.string.settings_eq),
        if (prefs.dsp.eqOn) stringResource(R.string.settings_eq_on, presetLabelRes(prefs.preset)?.let { stringResource(it) } ?: prefs.preset)
        else stringResource(R.string.settings_eq_off),
    ) { vm.push(Screen.Equalizer) }
    val all by vm.tracks.collectAsState()
    val withRg = remember(all) { all.count { it.x("rg_track").isNotBlank() } }
    SettingTitle(
        stringResource(R.string.settings_rg_title),
        if (withRg == 0) stringResource(R.string.settings_rg_none)
        else plural(R.plurals.settings_rg_some, all.size, withRg, all.size, RG_HEADROOM_DB.toInt()),
    )
    ChipChoice(ReplayGainMode.entries.map { it to stringResource(it.labelRes) }, prefs.replayGain) { m -> SurcoSettings.update { it.copy(replayGain = m) } }
    // DAC USB: en Android 14+ se le pide al sistema el mezclador a la frecuencia de cada canción.
    if (Build.VERSION.SDK_INT >= 34) {
        SwitchRow(stringResource(R.string.settings_usb_title), stringResource(R.string.settings_usb_help), prefs.usbBitPerfect) { on ->
            SurcoSettings.update { it.copy(usbBitPerfect = on) }
        }
    } else SettingTitle(stringResource(R.string.settings_usb_title), stringResource(R.string.settings_usb_old))
    SettingTitle(stringResource(R.string.settings_output_title), stringResource(R.string.settings_output_help))
    outputLine()?.let { Text(it, color = Teal, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
    routeLine()?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
}

// ---------- Reproducción ----------
@Composable
private fun PlaybackPage(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    SettingTitle(stringResource(R.string.settings_crossfade), stringResource(R.string.settings_crossfade_help))
    val off = stringResource(R.string.settings_none)
    val fades = listOf(2, 4, 6, 8, 12).map { it to stringResource(R.string.settings_seconds, it) }
    ChipChoice(listOf(0 to off) + fades, prefs.crossfadeSec) { s ->
        SurcoSettings.update { it.copy(crossfadeSec = s) }
    }
    if (prefs.crossfadeSec > 0) {
        SwitchRow(stringResource(R.string.settings_keep_albums), stringResource(R.string.settings_keep_albums_help),
            prefs.crossfadeKeepAlbums) { k -> SurcoSettings.update { it.copy(crossfadeKeepAlbums = k) } }
    }
    SwitchRow(stringResource(R.string.settings_fade), stringResource(R.string.settings_fade_help), prefs.fade) { f -> SurcoSettings.update { it.copy(fade = f) } }
    SwitchRow(stringResource(R.string.settings_skip_silence), stringResource(R.string.settings_skip_silence_help), prefs.skipSilence) { k -> SurcoSettings.update { it.copy(skipSilence = k) } }
    OptionRow(
        stringResource(R.string.settings_speed),
        stringResource(if (prefs.keepPitch) R.string.settings_speed_kept else R.string.settings_speed_free, prefs.speed),
    ) { vm.speedOpen = true }
    OptionRow(stringResource(R.string.settings_sleep), stringResource(R.string.settings_sleep_help)) { vm.sleepOpen = true }
}

// ---------- Biblioteca ----------
@Composable
private fun LibraryPage(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    val all by vm.tracks.collectAsState()
    SettingTitle(stringResource(R.string.settings_short), stringResource(R.string.settings_short_help))
    ChipChoice(
        listOf(0 to stringResource(R.string.settings_none), 30 to stringResource(R.string.settings_shorter_s, 30), 60 to stringResource(R.string.settings_shorter_min, 1)),
        prefs.minDurationSec,
    ) { s -> SurcoSettings.update { it.copy(minDurationSec = s) } }
    OptionRow(stringResource(R.string.settings_rescan), stringResource(R.string.settings_rescan_help)) { vm.scan(force = true); vm.pop() }
    val analysis by vm.analysis.collectAsState()
    val analyzed = remember(all) { all.count { it.x("bpm_src") == "análisis" || it.x("key_src") == "análisis" } }
    SwitchRow(
        stringResource(R.string.settings_analyze),
        analysis?.let { (d, n) -> plural(R.plurals.settings_analyzing, n, d, n) }
            ?: stringResource(R.string.settings_analyze_help, analyzed),
        prefs.analyze,
    ) { on -> SurcoSettings.update { it.copy(analyze = on) }; if (on) vm.analyzeLibrary() else vm.stopAnalysis() }
    SwitchRow(
        stringResource(R.string.settings_lrclib),
        stringResource(R.string.settings_lrclib_help),
        prefs.onlineLyrics,
    ) { on -> SurcoSettings.update { it.copy(onlineLyrics = on) } }
    SectionLabel(stringResource(R.string.settings_folders))
    Text(stringResource(R.string.settings_folders_help),
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
    SettingTitle(stringResource(R.string.settings_accent), stringResource(R.string.settings_accent_help))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ACCENTS.forEach { (name, rgb) ->
            val label = accentLabelRes(name)?.let { stringResource(it) } ?: name
            Swatch(label, Color(0xFF000000.toInt() or rgb), prefs.accent == name) { SurcoSettings.update { it.copy(accent = name) } }
        }
    }
    // Fijo (los de arriba), de la portada que suena o, en Android 12+, el del sistema (Material You).
    val mode = when (prefs.accent) { ACCENT_FROM_ART, ACCENT_SYSTEM -> prefs.accent; else -> "" }
    ChipChoice(
        buildList {
            add("" to stringResource(R.string.settings_accent_fixed))
            add(ACCENT_FROM_ART to stringResource(R.string.settings_accent_art))
            if (Build.VERSION.SDK_INT >= 31) add(ACCENT_SYSTEM to stringResource(R.string.settings_accent_system))
        },
        mode,
    ) { m ->
        SurcoSettings.update { it.copy(accent = m.ifEmpty { if (it.accent in ACCENTS) it.accent else "Cobre" }) }
    }
    // Idioma de Surco, aparte del del móvil (Android 13+; antes, el del sistema). Cada idioma con su
    // propio nombre, como en los ajustes del sistema.
    if (Build.VERSION.SDK_INT >= 33) {
        val ctx = LocalContext.current
        val lm = remember { ctx.getSystemService(LocaleManager::class.java) }
        var lang by remember { mutableStateOf(lm.applicationLocales.get(0)?.language.orEmpty()) }
        SectionLabel(stringResource(R.string.settings_language_section))
        ChipChoice(listOf("" to stringResource(R.string.settings_language_system), "en" to "English", "es" to "Español"), lang) { t ->
            lang = t
            // La actividad se recrea sola con el idioma nuevo.
            lm.applicationLocales = if (t.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        }
    }
    SectionLabel(stringResource(R.string.settings_now_playing_section))
    SwitchRow(stringResource(R.string.settings_tech), stringResource(R.string.settings_tech_help), prefs.techDetails) { t ->
        SurcoSettings.update { it.copy(techDetails = t) }
    }
    SwitchRow(stringResource(R.string.settings_screen_on), stringResource(R.string.settings_screen_on_help), prefs.keepScreenOn) { k ->
        SurcoSettings.update { it.copy(keepScreenOn = k) }
    }
    SectionLabel(stringResource(R.string.settings_home_section))
    SwitchRow(stringResource(R.string.settings_home_recent), null, prefs.homeRecent) { v -> SurcoSettings.update { it.copy(homeRecent = v) } }
    SwitchRow(stringResource(R.string.settings_home_added), null, prefs.homeAdded) { v -> SurcoSettings.update { it.copy(homeAdded = v) } }
    SwitchRow(stringResource(R.string.settings_home_top), null, prefs.homeTop) { v -> SurcoSettings.update { it.copy(homeTop = v) } }
    SwitchRow(stringResource(R.string.settings_home_explore), null, prefs.homeExplore) { v -> SurcoSettings.update { it.copy(homeExplore = v) } }
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
        Big("${s.plays}", plural(R.plurals.settings_stats_plays, s.plays), Modifier.weight(1f))
        Big(
            if (s.minutes >= 120) stringResource(R.string.settings_stats_hours, s.minutes / 60) else stringResource(R.string.settings_stats_minutes, s.minutes),
            stringResource(R.string.settings_stats_of_music), Modifier.weight(1f),
        )
        Big("${s.songs}", plural(R.plurals.settings_stats_songs, s.songs), Modifier.weight(1f))
    }
    if (s.plays == 0) {
        Text(stringResource(R.string.settings_stats_empty),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        ListenBrainzSettings()
        return
    }
    SectionLabel(stringResource(R.string.settings_stats_artists))
    s.topArtists.forEachIndexed { i, (a, n) -> RankRow(i + 1, a, "$n") { all.firstOrNull { it.artist == a }?.let { vm.goToArtist(it) } } }
    SectionLabel(stringResource(R.string.settings_stats_songs_section))
    s.topSongs.forEachIndexed { i, (id, n) ->
        val t = byId[id] ?: return@forEachIndexed
        RankRow(i + 1, t.title, "$n") { vm.player.play(listOf(t), 0) }
    }
    // Enviar las escuchas a ListenBrainz (opcional, apagado por defecto).
    ListenBrainzSettings()
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
        stringResource(R.string.settings_privacy),
        stringResource(if (online) R.string.settings_privacy_online else R.string.settings_privacy_offline),
    )
    // Con ListenBrainz activado sale algo más de internet: se dice aquí también.
    if (Scrobbler.state.collectAsState().value.enabled) {
        Text(stringResource(R.string.settings_privacy_lb), color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
    SettingTitle(stringResource(R.string.settings_version), SURCO_VERSION)
    SettingTitle(stringResource(R.string.settings_formats), stringResource(R.string.settings_formats_help))
}
