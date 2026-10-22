package eus.argos.nativo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import eus.argos.core.VozPiper
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors

/**
 * La voz propia de ARGOS: un modelo Piper empaquetado en la app, que habla en
 * el teléfono sin red. Si no viene en el APK, o falla, el chat usa la voz del
 * sistema; nunca se queda mudo por esto.
 *
 * El modelo se copia una vez de los recursos a un fichero, para que ONNX
 * Runtime lo abra desde disco en vez de tener una copia entera en memoria.
 */
class VozApp private constructor(private val voz: VozPiper, private val ses: OrtSession, val nombre: String) {
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "argos-voz") }
    @Volatile private var pista: AudioTrack? = null
    @Volatile private var turno = 0

    /** Habla el texto; lo anterior que estuviera sonando se corta. */
    fun habla(texto: String) {
        val mio = ++turno
        para()
        hilo.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val pcm = VozPiper.pcm16(voz.habla(texto))
                if (mio != turno || pcm.isEmpty()) return@execute
                Log.i(TAG, "ARGOS voz: %.1f s de audio en %d ms".format(java.util.Locale.ROOT,
                    pcm.size / voz.muestreo.toDouble(), SystemClock.elapsedRealtime() - t0))
                val p = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(voz.muestreo).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                p.write(pcm, 0, pcm.size)
                pista = p
                p.play()
            } catch (e: Throwable) {
                Log.e(TAG, "ARGOS voz: falló: ${e.message}", e)
            }
        }
    }

    fun para() {
        pista?.let { runCatching { it.stop() }; runCatching { it.release() } }
        pista = null
    }

    fun close() { turno++; para(); hilo.shutdown(); runCatching { ses.close() } }

    companion object {
        /** La voz empaquetada, o null si el APK no trae ninguna. Tarda: fuera del hilo de la interfaz. */
        fun carga(ctx: Context): VozApp? = try {
            val nombres = ctx.assets.list("")?.toSet() ?: emptySet()
            if ("voz.onnx" !in nombres || "voz.onnx.json" !in nombres) null else {
                val dst = File(ctx.noBackupFilesDir, "voz.onnx")
                val tam = ctx.assets.openFd("voz.onnx").use { it.length }
                if (!dst.exists() || dst.length() != tam)
                    ctx.assets.open("voz.onnx").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                val config = ctx.assets.open("voz.onnx.json").use { it.readBytes().toString(Charsets.UTF_8) }
                val env = OrtEnvironment.getEnvironment()
                val ses = env.createSession(dst.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
                val voz = VozPiper(config) { ids, esc ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { a ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)).use { b ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(esc), longArrayOf(3)).use { c ->
                        ses.run(mapOf("input" to a, "input_lengths" to b, "scales" to c)).use { r ->
                            val fb = (r[0] as OnnxTensor).floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) }
                        } } } }
                }
                val nombre = Regex("\"dataset\"\\s*:\\s*\"([^\"]+)\"").find(config)?.groupValues?.get(1) ?: "Piper"
                Log.i(TAG, "ARGOS voz: Piper «$nombre», ${voz.muestreo} Hz")
                VozApp(voz, ses, nombre)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ARGOS voz: no cargó: ${e.message}", e); null
        }
    }
}
