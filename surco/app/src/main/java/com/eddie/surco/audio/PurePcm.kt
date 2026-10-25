package com.eddie.surco.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Codificaciones PCM con los mismos valores que `C.ENCODING_PCM_*` de Media3 (y AudioFormat). */
object Pcm {
    const val PCM_16 = 2
    const val PCM_8 = 3
    const val FLOAT = 4
    const val PCM_24 = 21
    const val PCM_32 = 22
    const val PCM_16_BE = 0x10000000
    const val PCM_24_BE = 0x50000000
    const val PCM_32_BE = 0x60000000

    /** Más de 16 bits: lo que Media3 recortaría a 16 bits si no se procesa en coma flotante. */
    fun isHighRes(enc: Int) = enc == PCM_24 || enc == PCM_24_BE || enc == PCM_32 || enc == PCM_32_BE || enc == FLOAT

    fun bytesPerSample(enc: Int) = when (enc) {
        PCM_8 -> 1
        PCM_16, PCM_16_BE -> 2
        PCM_24, PCM_24_BE -> 3
        PCM_32, PCM_32_BE, FLOAT -> 4
        else -> throw IllegalArgumentException("codificación PCM desconocida: $enc")
    }

    fun label(enc: Int) = when (enc) {
        PCM_8 -> "8 bits"
        PCM_16, PCM_16_BE -> "16 bits"
        PCM_24, PCM_24_BE -> "24 bits"
        PCM_32, PCM_32_BE -> "32 bits"
        FLOAT -> "coma flotante 32 bits"
        else -> "PCM $enc"
    }

    /**
     * Lee [n] muestras de [src] desde su posición (sin moverla) a coma flotante en [out].
     * 24 bits caben enteros en un float (24 bits de mantisa): la conversión es exacta.
     * 32 bits enteros pierden los 8 de abajo, igual que en el mezclador de Android.
     */
    fun toFloat(src: ByteBuffer, enc: Int, out: FloatArray, n: Int) {
        val p = src.position()
        when (enc) {
            FLOAT -> { val b = src.duplicate().order(ByteOrder.nativeOrder()); for (i in 0 until n) out[i] = b.getFloat(p + i * 4) }
            PCM_16 -> { val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) out[i] = b.getShort(p + i * 2) / 32768f }
            PCM_16_BE -> { val b = src.duplicate().order(ByteOrder.BIG_ENDIAN); for (i in 0 until n) out[i] = b.getShort(p + i * 2) / 32768f }
            PCM_24 -> for (i in 0 until n) {
                val o = p + i * 3
                val v = (src.get(o).toInt() and 0xFF) or ((src.get(o + 1).toInt() and 0xFF) shl 8) or (src.get(o + 2).toInt() shl 16)
                out[i] = v / 8388608f
            }
            PCM_24_BE -> for (i in 0 until n) {
                val o = p + i * 3
                val v = (src.get(o).toInt() shl 16) or ((src.get(o + 1).toInt() and 0xFF) shl 8) or (src.get(o + 2).toInt() and 0xFF)
                out[i] = v / 8388608f
            }
            PCM_32 -> { val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) out[i] = (b.getInt(p + i * 4) / 2147483648.0).toFloat() }
            PCM_32_BE -> { val b = src.duplicate().order(ByteOrder.BIG_ENDIAN); for (i in 0 until n) out[i] = (b.getInt(p + i * 4) / 2147483648.0).toFloat() }
            PCM_8 -> for (i in 0 until n) out[i] = ((src.get(p + i).toInt() and 0xFF) - 128) / 128f
            else -> throw IllegalArgumentException("codificación PCM desconocida: $enc")
        }
    }
}
