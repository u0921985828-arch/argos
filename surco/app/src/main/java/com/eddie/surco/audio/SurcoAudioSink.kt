package com.eddie.surco.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cómo sale lo que suena: lo enseña «Sonando» y lo comprueba la prueba de humo. */
data class OutputInfo(val source: String, val sampleRate: Int, val channels: Int, val float: Boolean)

object AudioOutput {
    private val _info = MutableStateFlow<OutputInfo?>(null)
    val info: StateFlow<OutputInfo?> = _info
    internal fun publish(i: OutputInfo) {
        _info.value = i
        Log.i("Surco", "Surco salida: ${i.source} → ${if (i.float) "coma flotante 32 bits" else "16 bits"} · ${i.sampleRate} Hz · ${i.channels} canales")
    }
}

/**
 * Salida sin pérdida. Media3, sin salida en coma flotante, recorta a 16 bits todo lo de más
 * resolución antes de los procesadores; con ella, se salta los procesadores. Aquí:
 *  - lo de 16 bits va por el camino normal (recorte de huecos para el «sin cortes», saltar
 *    silencios y [SurcoAudioProcessor], que no toca un bit con los efectos en neutro);
 *  - lo de 24/32 bits y coma flotante se pasa a float sin perder nada, se procesa en float y
 *    llega en float a AudioTrack, a su frecuencia de muestreo.
 */
@OptIn(UnstableApi::class)
class SurcoAudioSink private constructor(inner: AudioSink) : ForwardingAudioSink(inner) {
    private val engine = DspEngine()
    private var applied: DspParams? = null
    private var floatMode = false
    private var inEnc = C.ENCODING_PCM_16BIT
    private var channels = 2
    private var work = FloatArray(0)
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var pendingIn: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        floatMode = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType && Pcm.isHighRes(inputFormat.pcmEncoding)
        pendingIn = null
        if (floatMode) {
            inEnc = inputFormat.pcmEncoding
            channels = inputFormat.channelCount
            engine.configure(inputFormat.sampleRate, channels)
            applied = null
            super.configure(inputFormat.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build(), specifiedBufferSize, outputChannels)
        } else {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }
        if (MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType) {
            AudioOutput.publish(OutputInfo(Pcm.label(inputFormat.pcmEncoding), inputFormat.sampleRate, inputFormat.channelCount, floatMode))
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!floatMode) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!buffer.hasRemaining() && pendingIn == null) return true
        // El reproductor repite el mismo búfer hasta que se consume entero: se procesa una sola vez.
        if (buffer !== pendingIn) {
            val n = buffer.remaining() / Pcm.bytesPerSample(inEnc)
            if (work.size < n) work = FloatArray(n)
            Pcm.toFloat(buffer, inEnc, work, n)
            val p = SurcoSettings.dsp
            if (p !== applied) { engine.setParams(p); applied = p }
            engine.process(work, n / channels)
            if (out.capacity() < n * 4) out = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            out.clear()
            for (i in 0 until n) out.putFloat(work[i])
            out.flip()
            pendingIn = buffer
        }
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            buffer.position(buffer.limit())
            pendingIn = null
        }
        return done
    }

    override fun flush() {
        pendingIn = null
        engine.reset(); applied = null
        super.flush()
    }

    override fun reset() {
        pendingIn = null
        engine.reset(); applied = null
        super.reset()
    }

    companion object {
        fun create(ctx: Context): SurcoAudioSink = SurcoAudioSink(
            DefaultAudioSink.Builder(ctx)
                .setEnableFloatOutput(true)
                // Velocidad y tono en AudioTrack: valen igual para 16 bits y para float.
                .setEnableAudioTrackPlaybackParams(true)
                .setAudioProcessors(arrayOf(SurcoAudioProcessor()))
                .build()
        )
    }
}

/** Formatos sin pérdida: su decodificador entrega coma flotante (24 bits enteros, sin recortar). */
private val LOSSLESS = setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, "audio/x-flac", "audio/x-alac")

/**
 * Los formatos con pérdida (MP3, AAC, Opus, Vorbis) se piden en 16 bits: así conservan el recorte
 * de huecos que da el «sin cortes» entre pistas, que Media3 solo aplica en 16 bits. Los sin
 * pérdida se piden en coma flotante para no tirar la resolución por encima de 16 bits.
 */
@OptIn(UnstableApi::class)
class SurcoAudioRenderer(ctx: Context, handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, listener, sink) {

    override fun getMediaFormat(format: Format, codecMimeType: String, codecMaxInputSize: Int, codecOperatingRate: Float): MediaFormat {
        val mf = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate)
        if (codecMimeType !in LOSSLESS && mf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            mf.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        return mf
    }
}
