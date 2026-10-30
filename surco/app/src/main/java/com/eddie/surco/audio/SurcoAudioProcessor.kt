package com.eddie.surco.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pasa el PCM que va a sonar por [DspEngine]. Media3 lo entrega en 16 bits (sin salida
 * float, que se saltaría los procesadores); también acepta float por si acaso.
 * Con los efectos en neutro copia los bytes tal cual: salida idéntica a la entrada.
 */
@OptIn(UnstableApi::class)
class SurcoAudioProcessor : BaseAudioProcessor() {
    private val engine = DspEngine()
    private var applied: DspParams? = null
    private var work = FloatArray(0)
    private var seed = 0x2545F491

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            applied = null
        }
    }

    override fun onReset() {
        engine.reset()
        applied = null
    }

    /** Ruido triangular de ±1 LSB: al volver a 16 bits tras procesar, mejor ruido que distorsión. */
    private fun dither(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        val a = (seed and 0xFFFF) / 65536f
        val b = ((seed ushr 16) and 0xFFFF) / 65536f
        return a - b
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val p = SurcoSettings.dsp
        if (p !== applied) { engine.setParams(p); applied = p }
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (engine.isNeutral) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val src = inputBuffer.slice().order(ByteOrder.nativeOrder())
        val ch = inputAudioFormat.channelCount
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val n = size / 2
            if (work.size < n) work = FloatArray(n)
            for (i in 0 until n) work[i] = src.getShort(i * 2) / 32768f
            engine.process(work, n / ch)
            for (i in 0 until n) {
                val v = (work[i] * 32767f + dither()).roundToInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        } else {
            val n = size / 4
            if (work.size < n) work = FloatArray(n)
            for (i in 0 until n) work[i] = src.getFloat(i * 4)
            engine.process(work, n / ch)
            for (i in 0 until n) out.putFloat(work[i])
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
