package com.eddie.surco.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * BPM y tonalidad de una canción: decodifica 30 s de la parte central (a partir del 30 %: la
 * introducción suele ir sin batería) con el decodificador del móvil, los pasa a mono a ~11 kHz y
 * se los da a [estimateBpm] y [estimateKey]. De esos mismos 30 s, antes de pasarlos a mono, mide la
 * sonoridad (EBU R128, [LoudnessMeter]) para igualar el volumen de las que no traen ReplayGain: es
 * la parte central, la más fuerte casi siempre, así que la ganancia peca de prudente. Nada sale del móvil.
 */
object AudioAnalyzer {
    private const val SECONDS = 30
    private const val TARGET_RATE = 11025

    /** [decoded]: cómo llegó el audio del decodificador («44100 Hz · 2 canales · 16 bits»), para el registro de pruebas. */
    data class Result(val bpm: Float?, val key: MusicKey?, val lufs: Float? = null, val decoded: String = "")

    /** Sin [tempoKey], solo la sonoridad (las etiquetas o un análisis anterior ya dan BPM y tonalidad). */
    fun analyze(ctx: Context, t: Track, tempoKey: Boolean = true): Result? = runCatching {
        val pcm = decode(ctx, t) ?: return null
        val (x, rate, lufs) = pcm.first
        if (!tempoKey) return Result(null, null, lufs, pcm.second)
        Result(estimateBpm(x, rate), estimateKey(x, rate)?.takeIf { it.confidence > 0.02 }?.key, lufs, pcm.second)
    }.getOrNull()

    /** Mono, diezmado por un factor entero hasta ~11 kHz (promediando: hace de filtro paso bajo). */
    private fun decode(ctx: Context, t: Track): Pair<Triple<FloatArray, Int, Float?>, String>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, t.uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durationMs * 1000
            if (durUs > SECONDS * 2_000_000L) ex.seekTo(durUs * 3 / 10, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var factor = (rate / TARGET_RATE).coerceAtLeast(1)
            val out = FloatArray(SECONDS * (rate / factor) + 1)
            var n = 0
            var acc = 0.0; var accN = 0
            var meter: LoudnessMeter? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var guard = 0
            while (n < out.size && guard++ < 20_000) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    factor = (rate / TARGET_RATE).coerceAtLeast(1)
                    meter = null
                } else if (o >= 0) {
                    val m = meter ?: LoudnessMeter(rate, channels).also { meter = it }
                    val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val frames = info.size / (channels * if (float) 4 else 2)
                    for (fr in 0 until frames) {
                        var v = 0.0
                        for (ch in 0 until channels) {
                            val s = if (float) buf.float.toDouble() else buf.short / 32768.0
                            m.push(s); v += s
                        }
                        acc += v / channels; accN++
                        if (accN == factor) {
                            if (n < out.size) out[n++] = (acc / factor).toFloat()
                            acc = 0.0; accN = 0
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return if (n < TARGET_RATE * 8) null else Triple(out.copyOf(n), rate / factor, meter?.integrated()?.toFloat()) to "$rate Hz · $channels canales · ${if (float) "float" else "16 bits"}"
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
