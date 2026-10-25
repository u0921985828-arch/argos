package com.eddie.surco

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.ui.GroupsScreen
import com.eddie.surco.ui.HomeScreen
import com.eddie.surco.ui.Ink
import com.eddie.surco.ui.MiniPlayer
import com.eddie.surco.ui.NowPlaying
import com.eddie.surco.ui.PermissionScreen
import com.eddie.surco.ui.SongsScreen
import com.eddie.surco.ui.SurcoTheme
import com.eddie.surco.ui.AddToPlaylistDialog
import com.eddie.surco.ui.BottomNav
import com.eddie.surco.ui.LibraryScreen
import com.eddie.surco.ui.SearchScreen
import com.eddie.surco.ui.TagsScreen
import com.eddie.surco.ui.EqualizerScreen
import com.eddie.surco.ui.PlaylistScreen
import com.eddie.surco.ui.PlaylistsScreen
import com.eddie.surco.ui.QueueSheet
import com.eddie.surco.ui.SettingsScreen
import com.eddie.surco.ui.SleepDialog
import com.eddie.surco.ui.SmartScreen
import com.eddie.surco.ui.SongSheet
import com.eddie.surco.ui.SpeedDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SurcoTheme { Root() } }
    }
}

private val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun Root(vm: LibraryViewModel = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[audioPerm] == true || granted }
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms) }
    LaunchedEffect(granted) { if (granted) vm.scan() }

    if (!granted) PermissionScreen { launcher.launch(perms) } else App(vm)
}

@Composable
private fun App(vm: LibraryViewModel) {
    val all by vm.tracks.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val prefs by SurcoSettings.state.collectAsState()
    val tracks = remember(all, prefs.minDurationSec) {
        if (prefs.minDurationSec <= 0) all else all.filter { it.durationMs <= 0 || it.durationMs >= prefs.minDurationSec * 1000L }
    }
    val byId = remember(all) { all.associateBy { it.id } }
    val current = vm.player.currentId?.let { byId[it] }

    // Al abrir sin nada sonando, deja preparada la cola de la última vez.
    LaunchedEffect(vm.player.controller, byId.isNotEmpty()) {
        if (byId.isNotEmpty()) vm.player.restoreIfEmpty(byId)
    }

    BackHandler(enabled = vm.nowPlayingOpen || vm.stack.size > 1 || vm.tab != Tab.HOME) {
        if (vm.nowPlayingOpen) vm.nowPlayingOpen = false else vm.pop()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                Column {
                    current?.let { MiniPlayer(vm, it) }
                    BottomNav(vm)
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (val s = vm.stack.last()) {
                    Screen.Home -> HomeScreen(vm, tracks, byId, progress)
                    Screen.Library -> LibraryScreen(vm, tracks)
                    Screen.Tags -> TagsScreen(vm, tracks)
                    Screen.Search -> SearchScreen(vm, tracks)
                    is Screen.Groups -> GroupsScreen(vm, s.field, tracks)
                    is Screen.Songs -> SongsScreen(vm, s, tracks)
                    is Screen.Smart -> SmartScreen(vm, s.list, tracks)
                    is Screen.PlaylistView -> PlaylistScreen(vm, s.id, byId)
                    Screen.Playlists -> PlaylistsScreen(vm, tracks)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Equalizer -> EqualizerScreen(vm)
                }
            }
        }
        AnimatedVisibility(
            visible = vm.nowPlayingOpen && current != null,
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            current?.let { NowPlaying(vm, it) }
        }
        vm.sheet?.let { SongSheet(vm, it) }
        if (vm.queueOpen) QueueSheet(vm, byId)
        if (vm.sleepOpen) SleepDialog(vm)
        if (vm.speedOpen) SpeedDialog(vm)
        vm.addTo?.let { AddToPlaylistDialog(vm, it) }
    }
}
