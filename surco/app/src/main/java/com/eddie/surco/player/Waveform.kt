package com.eddie.surco.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import com.eddie.surco.data.Track
import com.eddie.surco.data.WAVE_BARS
import com.eddie.surco.data.waveBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Forma de onda de la barra de progreso. Sin decodificar la canción entera: salta a [WAVE_BARS]
 * puntos repartidos y decodifica unos 50 ms en cada uno (un segundo de audio en total, menos que lo
 * que tarda en abrirse «Sonando»). Se guarda en la caché por ruta y fecha del archivo: la segunda
 * vez sale al instante. Nada sale del móvil.
 */
object Waveform {
    private val memory = LruCache<String, FloatArray>(64)
    /** Archivos que no se dejaron medir: no se vuelve a intentar en esta sesión. */
    private val failed = HashSet<String>()
    /** Una medida cada vez: al saltar canciones deprisa no se juntan decodificadores. */
    private val lock = Mutex()

    private fun key(t: Track) = "${t.path}|${t.dateModified}"

    /** Lo que ya está en memoria (sin tocar el disco): para pintarla sin esperar al volver a «Sonando». */
    fun cached(t: Track): FloatArray? = memory.get(key(t))

    /** null si el archivo no se deja leer. Se cancela entre barras si ya no hace falta (otra canción). */
    suspend fun load(ctx: Context, t: Track): FloatArray? = lock.withLock { withContext(Dispatchers.IO) { loadLocked(ctx, t) } }

    private suspend fun loadLocked(ctx: Context, t: Track): FloatArray? {
        val k = key(t)
        memory.get(k)?.let { return it }
        if (synchronized(failed) { k in failed }) return null
        val f = File(File(ctx.cacheDir, "ondas"), java.security.MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".bin")
        runCatching { f.readBytes() }.getOrNull()?.takeIf { it.size == WAVE_BARS }?.let { b ->
            return FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f }.also { memory.put(k, it) }
        }
        val bars = runCatching { measure(ctx, t) }.getOrNull()
        currentCoroutineContext().ensureActive()
        if (bars == null) { synchronized(failed) { failed += k }; return null }
        memory.put(k, bars)
        runCatching { f.parentFile?.mkdirs(); f.writeBytes(ByteArray(bars.size) { (bars[it] * 255).toInt().toByte() }) }
        return bars
    }

    private suspend fun measure(ctx: Context, t: Track): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, t.uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durationMs * 1000
            if (durUs <= 0) return null
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            // El primer salto antes de arrancar: un flush() sin salida todavía deja a los
            // decodificadores OMX (Android 8-9) sin los datos de configuración de AAC, Vorbis y Opus.
            ex.seekTo(durUs / (2 * WAVE_BARS), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val info = MediaCodec.BufferInfo()
            val rms = FloatArray(WAVE_BARS) { Float.NaN }
            for (b in 0 until WAVE_BARS) {
                if (!currentCoroutineContext().isActive) return null
                if (b > 0) {
                    ex.seekTo(durUs * (2 * b + 1) / (2 * WAVE_BARS), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    c.flush()
                }
                val want = rate / 20 // ~50 ms
                var sum = 0.0; var n = 0
                var inputDone = false
                var guard = 0
                while (n < want && guard++ < 400) {
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val size = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (size < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { c.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val frames = info.size / (channels * if (float) 4 else 2)
                        // Lo de antes del punto (el salto cae en el fotograma clave anterior) también vale:
                        // son milisegundos y la barra tiene un píxel por cada segundo o más.
                        for (fr in 0 until frames) {
                            var v = 0.0
                            for (ch in 0 until channels) { val s = if (float) buf.float.toDouble() else buf.short / 32768.0; v += s * s }
                            sum += v / channels; n++
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                if (n > 0) rms[b] = sqrt(sum / n).toFloat()
            }
            if (rms.count { !it.isNaN() } < WAVE_BARS / 2) return null
            return waveBars(rms)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
