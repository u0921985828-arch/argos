package com.eddie.surco

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.app.SearchManager
import android.provider.MediaStore
import com.eddie.surco.data.matchesFolded
import com.eddie.surco.ui.searchHay
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.DisposableEffect
import com.eddie.surco.audio.ACCENTS
import com.eddie.surco.audio.ACCENT_FROM_ART
import com.eddie.surco.data.legibleAccent
import com.eddie.surco.ui.Accent
import com.eddie.surco.ui.artColor
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
import com.eddie.surco.ui.CompatibleScreen
import com.eddie.surco.ui.SettingsPageScreen
import com.eddie.surco.ui.SleepDialog
import com.eddie.surco.ui.SmartScreen
import com.eddie.surco.ui.SongSheet
import com.eddie.surco.ui.SpeedDialog
import com.eddie.surco.ui.Notice
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import com.eddie.surco.ui.NoticeHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo la primera vez: al recrearse (giro, cambio de tema) el intent sigue siendo el mismo.
        if (savedInstanceState == null) voiceQuery(intent)
        setContent { SurcoTheme { Root() } }
    }

    // Los avisos del servicio (una canción que no se puede abrir) salen en la franja de Surco
    // solo si la app se ve; si no, como Toast.
    override fun onStart() { super.onStart(); Notice.visible = true }
    override fun onStop() { Notice.visible = false; super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        voiceQuery(intent)
    }

    /** «Pon … en Surco» del asistente: la frase llega en el intent y la recoge App al tener la biblioteca. */
    private fun voiceQuery(i: Intent?) {
        if (i?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) PendingVoice.query.value = i.getStringExtra(SearchManager.QUERY).orEmpty()
    }
}

/** Frase pendiente del asistente de voz («» = poner lo que sea: todo en aleatorio). */
object PendingVoice { val query = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }

private val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun Root(vm: LibraryViewModel = viewModel()) {
    val ctx = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var denied by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[audioPerm] == true || check()
        if (!granted) denied++
    }
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms) }
    // Acento fijo de Ajustes → Interfaz («Del disco» lo pone App con la portada que suena).
    val accent = SurcoSettings.state.collectAsState().value.accent
    LaunchedEffect(accent) { ACCENTS[accent]?.let { Accent.color = Color(0xFF000000.toInt() or it) } }
    // Si se da (o se quita) el permiso desde Ajustes del sistema, se nota al volver.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = check() }
    LaunchedEffect(granted) { if (granted) vm.scan() }

    // Tras dos negativas Android ya no vuelve a preguntar: el botón lleva a los ajustes de la app.
    val blocked = denied > 0 && !((ctx as? Activity)?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, audioPerm) } ?: true)
    if (!granted) PermissionScreen(blocked) {
        if (blocked) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        else launcher.launch(perms)
    } else App(vm)
}

@Composable
private fun App(vm: LibraryViewModel) {
    val all by vm.tracks.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val prefs by SurcoSettings.state.collectAsState()
    val tracks = remember(all, prefs.minDurationSec, prefs.excludedFolders) {
        all.filter {
            (prefs.minDurationSec <= 0 || it.durationMs <= 0 || it.durationMs >= prefs.minDurationSec * 1000L) &&
                (prefs.excludedFolders.isEmpty() || it.dir !in prefs.excludedFolders)
        }
    }
    val byId = remember(all) { all.associateBy { it.id } }
    val current = vm.player.currentId?.let { byId[it] ?: vm.player.currentAsTrack() }

    // «Del disco»: el acento sale de la portada que suena, aclarado hasta que se lea sobre el fondo.
    val ctx = LocalContext.current
    LaunchedEffect(prefs.accent, current?.artKey) {
        if (prefs.accent != ACCENT_FROM_ART) return@LaunchedEffect
        val c = current?.let { artColor(ctx, it) }
        Accent.color = if (c == null) Color(0xFF000000.toInt() or ACCENTS.getValue("Cobre"))
        // Contra Panel2, el fondo más claro sobre el que va el acento (pestaña activa, píldoras).
        else Color(0xFF000000.toInt() or legibleAccent(c.toArgb() and 0xFFFFFF, 0x20242C))
    }
    // «Pon … en Surco»: con la biblioteca ya consultada, suena lo que case con la frase.
    val voice by PendingVoice.query.collectAsStateWithLifecycle()
    val ready by vm.fresh.collectAsStateWithLifecycle()
    LaunchedEffect(voice, ready, tracks, vm.player.controller) {
        val q = voice ?: return@LaunchedEffect
        // Sin controlador conectado, play() no haría nada y la frase se perdería.
        if (!ready || tracks.isEmpty() || vm.player.controller == null) return@LaunchedEffect
        PendingVoice.query.value = null
        val hits = if (q.isBlank()) emptyList() else tracks.filter { matchesFolded(searchHay(it), q) }
        if (hits.isEmpty()) vm.player.play(tracks, 0, shuffled = true) else vm.player.play(hits, 0)
        vm.nowPlayingOpen = true
    }
    // Pantalla encendida con «Sonando» abierta, si se pide.
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn, vm.nowPlayingOpen) {
        view.keepScreenOn = prefs.keepScreenOn && vm.nowPlayingOpen
        onDispose { view.keepScreenOn = false }
    }

    // Al abrir sin nada sonando, deja preparada la cola de la última vez.
    // Solo con la lista ya consultada a MediaStore: con la caché podría restaurar canciones borradas.
    val fresh by vm.fresh.collectAsStateWithLifecycle()
    LaunchedEffect(vm.player.controller, fresh) {
        if (fresh) vm.player.restoreIfEmpty(vm.tracks.value.associateBy { it.id })
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
                    is Screen.Compatible -> CompatibleScreen(vm, s.id, tracks, byId)
                    is Screen.Settings -> s.page?.let { SettingsPageScreen(vm, it) } ?: SettingsScreen(vm)
                    Screen.Equalizer -> EqualizerScreen(vm)
                }
                // Encima del minirreproductor; con «Sonando» abierta, arriba (ver más abajo).
                if (!(vm.nowPlayingOpen && current != null)) NoticeHost()
            }
        }
        AnimatedVisibility(
            visible = vm.nowPlayingOpen && current != null,
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            current?.let { NowPlaying(vm, it) }
        }
        // En «Sonando» el aviso va bajo la barra de arriba: abajo están los mandos.
        if (vm.nowPlayingOpen && current != null) NoticeHost(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp, top = true)
        vm.sheet?.let { SongSheet(vm, it) }
        if (vm.queueOpen) QueueSheet(vm, byId)
        if (vm.sleepOpen) SleepDialog(vm)
        if (vm.speedOpen) SpeedDialog(vm)
        vm.addTo?.let { AddToPlaylistDialog(vm, it) }
    }
}
