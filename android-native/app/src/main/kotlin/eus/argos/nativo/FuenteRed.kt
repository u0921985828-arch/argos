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
 * pinta en un TextureView; de ahí se toma una imagen cada ~80 ms para el MISMO
 * análisis que la cámara del móvil.
 *
 * Solo se RECIBE: la app no envía nada a la cámara ni a ningún sitio.
 */
class FuenteRed(private val ctx: Context, val vista: TextureView,
                private val alFrame: (Bitmap) -> Unit, private val alEstado: (String, Int, Int) -> Unit) {
    private var player: ExoPlayer? = null
    private val hilo = Handler(Looper.getMainLooper())
    private var bmp: Bitmap? = null
    @Volatile var activa = false; private set
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

    /** Una imagen cada 80 ms (12 por segundo), a lo sumo de 960 px de ancho. */
    private val captura = object : Runnable {
        override fun run() {
            if (!activa) return
            if (vista.isAvailable && ancho > 0 && alto > 0) {
                val w = minOf(960, ancho); val h = maxOf(1, w * alto / ancho)
                val b = bmp?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
                runCatching { vista.getBitmap(b); alFrame(b) }
            }
            hilo.postDelayed(this, 80)
        }
    }

    fun cierra() {
        activa = false
        hilo.removeCallbacks(captura)
        player?.release(); player = null
        ancho = 0; alto = 0
    }
}
