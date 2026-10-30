package com.eddie.surco.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.data.Disk
import com.eddie.surco.data.Json
import com.eddie.surco.data.LB_SUBMIT
import com.eddie.surco.data.LB_VALIDATE
import com.eddie.surco.data.LbStatus
import com.eddie.surco.data.Listen
import com.eddie.surco.data.jsonStr
import com.eddie.surco.data.lbBody
import com.eddie.surco.data.lbCleanToken
import com.eddie.surco.data.lbEnqueue
import com.eddie.surco.data.lbFlush
import com.eddie.surco.data.lbHttp
import com.eddie.surco.data.lbQueueParse
import com.eddie.surco.data.lbQueueText
import com.eddie.surco.data.lbRemove
import com.eddie.surco.data.lbSendable
import com.eddie.surco.data.lbValidUser
import com.eddie.surco.ui.Notice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Envía las escuchas a ListenBrainz si el usuario lo activa y pega su token (apagado por defecto).
 * Lo que no se puede enviar (sin red, servidor ocupado) espera en una cola en disco y sale en lotes
 * en la siguiente escucha o en un reintento cada vez más espaciado. Token y cola van en
 * noBackupFilesDir: el token es una credencial y no debe viajar en la copia de seguridad.
 */
object Scrobbler {
    data class State(
        val enabled: Boolean = false,
        val token: String = "",
        /** Usuario de la última comprobación buena. */
        val user: String? = null,
        /** El servidor dijo 401 (o validate-token «no válido»): no se envía hasta cambiar el token. */
        val badToken: Boolean = false,
        val pending: Int = 0,
    )

    /** Resultado de comprobar el token. */
    sealed interface Check {
        data class Ok(val user: String) : Check
        data object Bad : Check
        /** Sin red o el servidor no contesta bien: no dice nada del token. */
        data object Offline : Check
    }

    private const val CONFIG = "listenbrainz.json"
    private const val QUEUE = "listenbrainz-cola.json"
    private const val RETRY_FIRST_MS = 60_000L
    private const val RETRY_MAX_MS = 30 * 60_000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private var dir: File? = null
    private lateinit var app: Context
    private var queue: List<Listen> = emptyList()
    private var retryJob: Job? = null
    private var retryMs = RETRY_FIRST_MS

    /** Lee token y cola (una vez por proceso). Archivos pequeños: vale en cualquier hilo. */
    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        app = ctx.applicationContext
        val d = app.noBackupFilesDir
        dir = d
        val cfgFile = File(d, CONFIG)
        val cfg = Disk.read(cfgFile)?.let { text -> (Json.parse(text) as? Map<*, *>).also { if (it == null) Disk.quarantine(cfgFile) } }
        val qFile = File(d, QUEUE)
        queue = Disk.read(qFile)?.let { text -> lbQueueParse(text).also { if (it == null) Disk.quarantine(qFile) } }.orEmpty()
        _state.value = State(
            enabled = cfg?.get("on") == true,
            token = cfg?.get("token") as? String ?: "",
            user = cfg?.get("user") as? String,
            badToken = cfg?.get("bad") == true,
            pending = queue.size,
        )
        if (queue.isNotEmpty()) scope.launch { flush() }
    }

    /** Empieza a sonar una canción: «sonando ahora» (no se guarda ni se reintenta). */
    fun nowPlaying(ctx: Context, item: MediaItem, durationMs: Long) {
        if (dir != null && !_state.value.enabled) return
        val l = listenOf(item, durationMs, 0) ?: return
        scope.launch {
            init(ctx)
            val s = _state.value
            if (!s.enabled || s.token.isEmpty() || s.badToken) return@launch
            if (lbHttp(LB_SUBMIT, s.token, lbBody("playing_now", listOf(l)))?.first == 401) tokenRejected()
            else flush() // si hay red para esto, la hay para lo pendiente
        }
    }

    /** Una escucha que cuenta (la mitad o 4 minutos), con la hora a la que empezó en segundos. */
    fun listened(ctx: Context, item: MediaItem, durationMs: Long, startedAtSec: Long) {
        if (dir != null && !_state.value.enabled) return
        val l = listenOf(item, durationMs, startedAtSec) ?: return
        scope.launch {
            init(ctx)
            if (!_state.value.enabled) return@launch
            synchronized(this@Scrobbler) { queue = lbEnqueue(queue, l); publishQueue() }
            flush()
        }
    }

    private fun listenOf(item: MediaItem, durationMs: Long, at: Long): Listen? {
        val m = item.mediaMetadata
        val artist = (m.artist ?: m.albumArtist)?.toString().orEmpty().trim()
        val title = m.title?.toString().orEmpty().trim()
        if (!lbSendable(artist, title, durationMs)) return null
        return Listen(artist, title, m.albumTitle?.toString().orEmpty().trim(), durationMs, at)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        init(ctx)
        _state.value = _state.value.copy(enabled = on)
        saveConfig()
        if (on) scope.launch { flush() }
    }

    /** El token tal como se escribe o se pega; un token nuevo borra el «no válido» y el usuario. */
    fun setToken(ctx: Context, raw: String) {
        init(ctx)
        val t = lbCleanToken(raw)
        val s = _state.value
        if (t == s.token) return
        _state.value = s.copy(token = t, user = null, badToken = false)
        saveConfig()
    }

    /** validate-token; si vale, envía lo pendiente. */
    suspend fun validate(ctx: Context): Check = withContext(Dispatchers.IO) {
        init(ctx)
        val token = _state.value.token
        if (token.isEmpty()) return@withContext Check.Bad
        val r = lbHttp(LB_VALIDATE, token) ?: return@withContext Check.Offline
        val user = if (r.first == 200) lbValidUser(r.second) else null
        // Solo cuenta si el token no ha cambiado mientras se comprobaba.
        if (_state.value.token != token) return@withContext Check.Offline
        when {
            user != null -> {
                _state.value = _state.value.copy(user = user, badToken = false); saveConfig()
                retryMs = RETRY_FIRST_MS
                scope.launch { flush() }
                Check.Ok(user)
            }
            r.first == 200 || r.first == 400 || r.first == 401 -> {
                _state.value = _state.value.copy(user = null, badToken = true); saveConfig()
                Check.Bad
            }
            else -> Check.Offline
        }
    }

    /** Envía la cola; uno a la vez (los demás esperan y encuentran la cola ya vacía). */
    private suspend fun flush() {
        sending.withLock {
            while (true) {
                val s = _state.value
                if (!s.enabled || s.token.isEmpty() || s.badToken) return
                val snap = synchronized(this) { queue }
                if (snap.isEmpty()) return
                val r = lbFlush(snap) { body -> lbHttp(LB_SUBMIT, s.token, body)?.first }
                val left = synchronized(this) { queue = lbRemove(queue, r.done); publishQueue(); queue }
                when (r.status) {
                    LbStatus.OK -> { retryMs = RETRY_FIRST_MS; if (left.isEmpty()) return } // si no, llegaron más mientras tanto
                    LbStatus.BAD_TOKEN -> { tokenRejected(); return }
                    LbStatus.RETRY -> { scheduleRetry(); return }
                }
            }
        }
    }

    @Synchronized
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = retryMs
        retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
        retryJob = scope.launch { delay(wait); flush() }
    }

    /** 401: se para y se avisa una vez; sigue parado hasta que el usuario cambie o compruebe el token. */
    private fun tokenRejected() {
        val s = _state.value
        if (s.badToken) return
        _state.value = s.copy(badToken = true, user = null)
        saveConfig()
        Handler(Looper.getMainLooper()).post {
            runCatching { Notice.fromService(app, Res.str(R.string.lb_bad_token_notice), long = true) }
        }
    }

    // Se llama con el cerrojo del objeto tomado.
    private fun publishQueue() {
        _state.value = _state.value.copy(pending = queue.size)
        val f = File(dir ?: return, QUEUE)
        val text = lbQueueText(queue)
        scope.launch(Disk.writer) { Disk.write(f, text) }
    }

    private fun saveConfig() {
        val f = File(dir ?: return, CONFIG)
        val s = _state.value
        val text = "{\"on\":${s.enabled},\"token\":${jsonStr(s.token)},\"bad\":${s.badToken}" +
            (s.user?.let { ",\"user\":${jsonStr(it)}" } ?: "") + "}"
        scope.launch(Disk.writer) { Disk.write(f, text) }
    }
}
