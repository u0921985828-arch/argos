package eus.argos.nativo

import android.util.Log
import eus.argos.core.Onvif
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Una cámara IP propia por ONVIF: con su IP, usuario y clave averigua la
 * dirección del vídeo (así no hay que saberse la ruta RTSP de cada marca) y,
 * si la cámara se mueve, la gira, la inclina y acerca el zoom.
 *
 * Solo habla con la cámara, en la red de casa (o por VPN). La clave no viaja:
 * va su resumen WS-Security, firmado con la hora de la cámara.
 */
class CamaraPtz(private val host: String, private val usuario: String, private val clave: String,
                private val conocido: String? = null) {
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "argos-ptz") }
    private val azar = SecureRandom()
    @Volatile private var desfaseMs = 0L
    @Volatile private var ptz: String? = null
    @Volatile private var perfil: String? = null
    /** Se puede mover. */
    val mueve: Boolean get() = ptz != null && perfil != null

    class Resultado(val rtsp: String?, val ptz: Boolean, val aviso: String?)

    private fun post(url: String, cuerpo: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2500; c.readTimeout = 5000; c.doOutput = true; c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val s = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            return s.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    private fun firmado(cuerpo: String): String {
        val nonce = ByteArray(16).also { azar.nextBytes(it) }
        return Onvif.sobre(cuerpo, Onvif.seguridad(usuario, clave, Instant.ofEpochMilli(System.currentTimeMillis() + desfaseMs), nonce))
    }

    /** La cámara a veces anuncia su dirección interna; se usa la que escribió el usuario. */
    private fun aqui(xaddr: String): String = runCatching {
        val u = URI(xaddr); URI(u.scheme, u.userInfo, host, u.port, u.path, u.query, null).toString()
    }.getOrDefault(xaddr)

    /** Prueba los puertos ONVIF habituales (Tapo 2020, la mayoría 80, Reolink 8000). */
    fun conecta(alFin: (Resultado) -> Unit) = hilo.execute {
        var aviso = "la cámara no responde a ONVIF en los puertos habituales (2020, 80, 8000, 8080)"
        // Si la búsqueda en la wifi dio su dirección, esa primero.
        val candidatos = listOfNotNull(conocido) + listOf(2020, 80, 8000, 8080, 8899).map { "http://$host:$it/onvif/device_service" }
        for (dispositivo in candidatos.distinct()) {
            val puerto = runCatching { URI(dispositivo).port }.getOrDefault(-1)
            val hora = runCatching { post(dispositivo, Onvif.sobre(Onvif.HORA_CAMARA, null)) }.getOrNull() ?: continue
            Onvif.leeHora(hora)?.let { desfaseMs = it.toEpochMilli() - System.currentTimeMillis() }
            try {
                val cap = post(dispositivo, firmado(Onvif.CAPACIDADES))
                Onvif.fallo(cap)?.let { alFin(Resultado(null, false, "la cámara no acepta ese usuario o esa clave ($it)")); return@execute }
                val media = Onvif.leeServicio(cap, "Media")?.let(::aqui) ?: dispositivo
                ptz = Onvif.leeServicio(cap, "PTZ")?.let(::aqui)
                val perfiles = post(media, firmado(Onvif.PERFILES))
                perfil = Onvif.leePerfil(perfiles)
                val p = perfil
                val uri = p?.let { Onvif.leeUri(post(media, firmado(Onvif.flujo(it)))) }?.let(::aqui)
                val rtsp = uri?.let { conClave(it) }
                Log.i(TAG, "ARGOS ip: ONVIF en el puerto $puerto · vídeo ${if (rtsp != null) "sí" else "no"} · se mueve ${if (mueve) "sí" else "no"}")
                alFin(Resultado(rtsp, mueve, if (rtsp == null) "no dio la dirección del vídeo" else null)); return@execute
            } catch (e: Exception) {
                aviso = "ONVIF en el puerto $puerto: ${e.javaClass.simpleName}"
            }
        }
        alFin(Resultado(null, false, aviso))
    }

    /** rtsp://usuario:clave@… para que el reproductor entre; la clave solo va a la cámara. */
    private fun conClave(uri: String): String {
        val u = URI(uri)
        val info = java.net.URLEncoder.encode(usuario, "UTF-8") + ":" + java.net.URLEncoder.encode(clave, "UTF-8")
        return "${u.scheme}://$info@${u.host}${if (u.port > 0) ":${u.port}" else ""}${u.rawPath ?: ""}${u.rawQuery?.let { "?$it" } ?: ""}"
    }

    /** Velocidades entre -1 y 1. Se mueve hasta `para()`. */
    fun mueve(x: Double, y: Double, zoom: Double) {
        val url = ptz ?: return; val p = perfil ?: return
        hilo.execute { runCatching { post(url, firmado(Onvif.mueve(p, x, y, zoom))) }.onFailure { Log.w(TAG, "ARGOS ip: mover falló: ${it.message}") } }
    }

    fun para() {
        val url = ptz ?: return; val p = perfil ?: return
        hilo.execute { runCatching { post(url, firmado(Onvif.para(p))) } }
    }

    fun aCasa() {
        val url = ptz ?: return; val p = perfil ?: return
        hilo.execute { runCatching { post(url, firmado(Onvif.aCasa(p))) } }
    }

    fun cierra() = hilo.shutdown()
}
