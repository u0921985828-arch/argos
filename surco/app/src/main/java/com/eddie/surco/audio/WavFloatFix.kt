package com.eddie.surco.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor

/**
 * Los extractores de siempre, salvo el de WAV, que se envuelve: si la cabecera dice coma flotante
 * extensible, el formato que sale se corrige de «32 bits enteros» a coma flotante.
 */
@OptIn(UnstableApi::class)
class SurcoExtractorsFactory : ExtractorsFactory {
    private val inner = DefaultExtractorsFactory()
    private fun wrap(a: Array<Extractor>): Array<Extractor> = Array(a.size) { if (a[it] is WavExtractor) FloatWavExtractor(a[it]) else a[it] }
    override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors())
    override fun createExtractors(uri: android.net.Uri, responseHeaders: MutableMap<String, MutableList<String>>): Array<Extractor> =
        wrap(inner.createExtractors(uri, responseHeaders))
}

@OptIn(UnstableApi::class)
private class FloatWavExtractor(private val inner: Extractor) : Extractor {
    private var isFloat = false

    override fun sniff(input: ExtractorInput): Boolean {
        val h = ByteArray(256)
        var n = 0
        while (n < h.size && input.peekFully(h, n, 1, true)) n++
        isFloat = isFloatExtensibleWav(h, n)
        input.resetPeekPosition()
        return inner.sniff(input)
    }

    override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput by output {
        override fun track(id: Int, type: Int): TrackOutput {
            val t = output.track(id, type)
            return object : TrackOutput by t {
                override fun format(format: Format) = t.format(
                    if (isFloat && format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_32BIT)
                        format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
                    else format,
                )
            }
        }
    })

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
}
