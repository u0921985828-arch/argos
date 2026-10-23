package eus.argos.nativo

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * Una cámara por red: pública (HLS de Caltrans, clips de TfL) o una cámara IP
 * propia (RTSP). El vídeo lo decodifica el chip del teléfono con Media3 y se
 * pinta en un TextureView; de ahí se toma CADA imagen nueva que el decodificador
 * entrega (avisa con su hora de salida) mientras el análisis esté libre, para el
 * MISMO análisis que la cámara del móvil. Antes era una cada 80 ms: el análisis
 * nunca pasaba de 12 por segundo aunque la cámara diera 30.
 *
 * Solo se RECIBE: la app no envía nada a la cámara ni a ningún sitio.
 */
class FuenteRed(private val ctx: Context, val vista: TextureView,
                private val alFrame: (Bitmap) -> Unit, private val alEstado: (String, Int, Int) -> Unit) {
    private var player: ExoPlayer? = null
    private val hilo = Handler(Looper.getMainLooper())
    private var bmp: Bitmap? = null
    @Volatile var activa = false; private set
    /** Si el análisis puede con otra imagen: si no, tomarla sería tirarla. */
    var libre: () -> Boolean = { true }
    @Volatile private var pedida = false
    @Volatile private var ultimoAviso = 0L
    private val toma = Runnable { pedida = false; tomaAhora() }
    var ancho = 0; private set
    var alto = 0; private set
    var nombre = ""; private set

    fun abre(url: String, titulo: String, bucle: Boolean) {
        cierra()
        nombre = titulo
        val p = ExoPlayer.Builder(ctx).build()
        p.setVideoTextureView(vista)
        p.volume = 0f
        // Los clips de Londres duran unos segundos: en bucle hasta que se
        // renuevan. El directo de California no lo necesita.
        p.repeatMode = if (bucle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // Cada imagen nueva avisa (en el hilo del reproductor) de cuándo sale a
        // pantalla; se toma justo después, si el análisis está libre.
        p.setVideoFrameMetadataListener { _, salidaNs, _, _ ->
            ultimoAviso = android.os.SystemClock.elapsedRealtime()
            if (!pedida && libre()) {
                pedida = true
                hilo.postDelayed(toma, maxOf(0L, (salidaNs - System.nanoTime()) / 1_000_000) + 2)
            }
        }
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) { ancho = v.width; alto = v.height; alEstado("$titulo · ${v.width}×${v.height}", ancho, alto) }
            }
            override fun onPlayerError(e: PlaybackException) {
                Log.w(TAG, "ARGOS red: ${e.errorCodeName} en $titulo")
                alEstado("no se pudo abrir $titulo (${e.errorCodeName})", 0, 0)
            }
        })
        val item = MediaItem.fromUri(url)
        // RTSP por TCP: por UDP muchas redes (y el NAT del router) pierden paquetes.
        if (url.startsWith("rtsp", ignoreCase = true)) p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(item))
        else p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        player = p
        activa = true
        alEstado("conectando con $titulo…", 0, 0)
        Log.i(TAG, "ARGOS red: abriendo $titulo")
        hilo.post(captura)
    }

    /** La imagen que hay ahora en pantalla, a lo sumo de 960 px de ancho. */
    private fun tomaAhora() {
        if (!activa || !libre() || !vista.isAvailable || ancho <= 0 || alto <= 0) return
        val w = minOf(960, ancho); val h = maxOf(1, w * alto / ancho)
        val b = bmp?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
        runCatching { vista.getBitmap(b); alFrame(b) }
    }

    /** Por si un decodificador no avisa de sus imágenes: entonces, una cada 40 ms. */
    private val captura = object : Runnable {
        override fun run() {
            if (!activa) return
            if (android.os.SystemClock.elapsedRealtime() - ultimoAviso > 300) tomaAhora()
            hilo.postDelayed(this, 40)
        }
    }

    fun cierra() {
        activa = false
        hilo.removeCallbacks(captura); hilo.removeCallbacks(toma); pedida = false
        player?.release(); player = null
        ancho = 0; alto = 0
    }
}
