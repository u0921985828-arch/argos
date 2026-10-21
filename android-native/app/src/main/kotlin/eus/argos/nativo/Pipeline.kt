package eus.argos.nativo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import eus.argos.core.Box
import eus.argos.core.DetBox
import eus.argos.core.DetectionMemory
import eus.argos.core.Engine
import eus.argos.core.YoloxDetector
import eus.argos.core.downscaleRgba
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

const val TAG = "ARGOS"

/**
 * Los dos relojes de ARGOS, en nativo.
 *
 *   RÁPIDO  cada frame de cámara: reducir, fondo, seguimiento, dibujo.
 *   LENTO   el detector, en su propio hilo, sobre el último frame libre.
 *
 * No se fusionan: un bucle que espera al detector va a tirones (medido en la
 * web: 33 fps con picos de 120 ms frente a 62 fps con el detector aparte). El
 * detector dice QUÉ hay; el seguimiento, DÓNDE está en cada frame.
 *
 * Lo que aquí desaparece respecto al WebView es la captura: CameraX entrega
 * los píxeles en memoria de CPU, sin lienzo ni `getImageData`, que era el 75 %
 * del coste del frame en el navegador.
 */
class Pipeline(modelBytes: ByteArray, threads: Int) {
    class Frame(val boxes: List<Box>, val w: Int, val h: Int)

    val engine = Engine()
    private val memory = DetectionMemory()

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val detector: YoloxDetector
    val threads: Int

    private val detExec = Executors.newSingleThreadExecutor { r -> Thread(r, "argos-detector") }
    private val busy = AtomicBoolean(false)
    /** Hay una pasada del detector que el seguimiento aún no ha recibido. */
    private val fresh = AtomicBoolean(false)

    // Estadísticas: medias móviles, como el panel de la web, repartidas por
    // etapa. Un «ms de frame» total no señala a nada.
    /**
     * 0 = cuadro entero (por defecto, invariante 3); 2 = teselas ×2 para
     * escenas amplias con objetos pequeños. No hay zoom universal: depende de
     * cuántos píxeles ocupa el objeto, y eso solo lo ve quien mira la escena.
     */
    @Volatile var zoom = 0.0
    @Volatile var frames = 0L; private set
    @Volatile var grabMs = 0.0; private set
    @Volatile var scaleMs = 0.0; private set
    @Volatile var engineMs = 0.0; private set
    @Volatile var inferMs = 0.0; private set
    @Volatile var detMs = 0.0; private set
    @Volatile var inferences = 0L; private set
    @Volatile var lastDets = 0; private set
    @Volatile var lastTiles = 1; private set
    @Volatile var fps = 0.0; private set
    private var lastT = 0L

    private var src = ByteArray(0)
    private var small = ByteArray(0)
    private var detBuf = ByteArray(0)

    init {
        this.threads = threads
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
        inputName = session.inputNames.first()
        detector = YoloxDetector(416) { tensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 416, 416)).use { t ->
                session.run(mapOf(inputName to t)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
    }

    /**
     * Primera inferencia, pagada al arrancar. ORT reserva la arena y prepara
     * los kernels en ella; si cae sobre el primer frame de cámara se ve como un
     * tirón y la cola que descarta mientras hay trabajo se traga los primeros
     * barridos. Además es el único número que dice cuánto cuesta inferir EN
     * ESTE aparato.
     */
    fun warmup(): Long {
        val zeros = ByteArray(416 * 416 * 4)
        detector.detect(zeros, 416 * 4, 416, 416)
        // Solo la inferencia, sin letterbox ni decodificación: es lo mismo que
        // mide el calentamiento de la web (un `sess.run` sobre ceros), y sin
        // eso las dos cifras no serían comparables.
        return Math.round(detector.lastInferMs)
    }

    /**
     * Un frame del reloj rápido. `buf` es el plano RGBA de CameraX, con su
     * `rowStride`, que casi nunca es anchura × 4.
     */
    fun onFrame(buf: ByteBuffer, rowStride: Int, w: Int, h: Int): Frame {
        val t0 = System.nanoTime()
        val need = rowStride * h
        if (src.size < need) src = ByteArray(need)
        buf.rewind()
        // La última fila puede venir sin relleno: se copia lo que haya.
        buf.get(src, 0, minOf(buf.remaining(), need))
        val t1 = System.nanoTime()

        val aw = 320
        val ah = maxOf(1, Math.round(h * aw.toDouble() / w).toInt())
        if (small.size != aw * ah * 4) small = ByteArray(aw * ah * 4)
        downscaleRgba(src, rowStride, w, h, small, aw, ah)
        val t2 = System.nanoTime()

        // Las cajas del detector entran UNA vez, en el frame en que llegan.
        // Repetirlas hasta la pasada siguiente congela la caja mientras la
        // persona sigue andando; entre pasadas manda la resta de fondo y la
        // pista conserva la clase que le dio el detector.
        val det = if (fresh.getAndSet(false)) memory.forTracking() else null
        val r = engine.analyze(small, aw, ah, w, h, det)
        val t3 = System.nanoTime()

        maybeDetect(rowStride, w, h)

        val a = 0.1
        grabMs = grabMs * (1 - a) + (t1 - t0) / 1e6 * a
        scaleMs = scaleMs * (1 - a) + (t2 - t1) / 1e6 * a
        engineMs = engineMs * (1 - a) + (t3 - t2) / 1e6 * a
        if (lastT != 0L) {
            val inst = 1e9 / (t0 - lastT).coerceAtLeast(1)
            fps = if (fps == 0.0) inst else fps * 0.9 + inst * 0.1
        }
        lastT = t0
        frames++
        return Frame(r.boxes, w, h)
    }

    /**
     * El reloj lento. Si el detector está libre se le da una COPIA de este
     * frame y se sigue; si está ocupado, nada: el frame no se encola. Encolar
     * frames viejos es exactamente lo que convierte un detector lento en uno
     * que va siempre por detrás.
     */
    private fun maybeDetect(rowStride: Int, w: Int, h: Int) {
        if (!busy.compareAndSet(false, true)) return
        val need = rowStride * h
        if (detBuf.size < need) detBuf = ByteArray(need)
        System.arraycopy(src, 0, detBuf, 0, need)
        val buf = detBuf
        try { detExec.execute {
            try {
                val t0 = System.nanoTime()
                val dets: List<DetBox> = detector.detect(buf, rowStride, w, h, zoom)
                lastTiles = detector.lastTiles
                val ms = (System.nanoTime() - t0) / 1e6
                memory.update(dets, SystemClock.elapsedRealtime().toDouble())
                fresh.set(true)
                val a = if (inferences == 0L) 1.0 else 0.2
                detMs = detMs * (1 - a) + ms * a
                inferMs = inferMs * (1 - a) + detector.lastInferMs * a
                lastDets = dets.size
                inferences++
            } catch (e: Throwable) {
                Log.e(TAG, "ARGOS detector falló: ${e.message}", e)
            } finally {
                busy.set(false)
            }
        } } catch (e: java.util.concurrent.RejectedExecutionException) {
            busy.set(false)      // cerrando: el frame simplemente no se detecta
        }
    }

    /**
     * Cierre ordenado. `shutdownNow()` no detiene código nativo: cerrar la
     * sesión con un `session.run` en vuelo libera memoria que ORT sigue
     * usando. Se deja de aceptar trabajo, se espera a la inferencia en curso
     * y solo entonces se libera.
     */
    fun close() {
        detExec.shutdown()
        try { detExec.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: InterruptedException) { }
        session.close()
    }
}
