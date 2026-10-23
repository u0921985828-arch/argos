package eus.argos.nativo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import eus.argos.core.Box
import eus.argos.core.AutoZoom
import eus.argos.core.DetBox
import eus.argos.core.DetectionMemory
import eus.argos.core.Engine
import eus.argos.core.SceneBrain
import eus.argos.core.SceneEvent
import eus.argos.core.TrackStore
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
class Pipeline(modelBytes: ByteArray, threads: Int, private val store: TrackStore? = null,
               behaviourFile: java.io.File? = null, traficoBytes: ByteArray? = null) {
    /** Dónde se guarda lo normal de la escena actual: uno por cámara, para que cada una aprenda lo suyo. */
    @Volatile private var archivoEscena: java.io.File? = behaviourFile
    /**
     * Lo que ve la interfaz en cada frame: cajas, y lo que se sabe de cada una
     * y de la escena. `camX/camY` en píxeles de fuente, para dibujar estelas
     * guardadas en coordenadas de escena.
     */
    class Frame(val boxes: List<Box>, val w: Int, val h: Int,
                val info: Map<Int, SceneBrain.TrackInfo> = emptyMap(),
                val camX: Double = 0.0, val camY: Double = 0.0,
                val moving: Boolean = false, val people: Int = 0, val vehicles: Int = 0,
                val peopleSeen: Int = 0, val scaleValid: Boolean = false, val scaleN: Int = 0,
                val events: List<SceneEvent> = emptyList(),
                val behaviourMature: Int = 0, val behaviourReady: Boolean = false,
                /** La cámara por red da siempre la misma imagen: está colgada, no es la escena quieta. */
                val congelada: Boolean = false, val rotulos: Int = 0,
                /** Entradas en zonas vigiladas en este frame. */
                val avisos: List<eus.argos.core.Alertas.Aviso> = emptyList())

    // Con el móvil en la mano todo se mueve: el núcleo estima el desplazamiento
    // de la imagen y mantiene el fondo y las pistas alineados con la escena.
    var engine = Engine().apply { egoMotion = true }; private set
    /** Escala métrica, velocidades, estaturas, eventos y recuentos (brain.js). */
    var brain = SceneBrain(); private set
    /**
     * Cambio de fuente (otra cámara): lo pide la interfaz y lo hace el hilo
     * del análisis al empezar el siguiente frame, para no cambiar el núcleo a
     * mitad de uno. `movil` = la cámara del teléfono, que se mueve; una cámara
     * fija remota no, así que no se compensa movimiento.
     */
    @Volatile private var nuevaEscena: Pair<Boolean, java.io.File?>? = null
    /**
     * `archivo`: dónde guardar y de dónde recuperar lo normal de ESA escena.
     * Cada cámara pública aprende lo suyo y lo conserva entre sesiones: son
     * agregados por celda (rumbos, velocidades, clases), ningún recorrido.
     */
    fun nuevaEscena(movil: Boolean, archivo: java.io.File?) { nuevaEscena = movil to archivo }

    /**
     * La cámara IP se ha girado o ha cambiado el zoom: el fondo aprendido ya no
     * es el de lo que se ve, así que se vuelve a aprender. Lo demás de la
     * cámara (rótulos, zoom del detector, lo normal) sigue valiendo.
     */
    @Volatile private var fondoViejo = false
    fun camaraMovida() { fondoViejo = true }
    // Hora real del instante 0 de la sesión, para guardar con fecha y hora.
    private val t0Epoch = System.currentTimeMillis() / 1000.0 - SystemClock.elapsedRealtime() / 1000.0
    private val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "argos-memoria") }
    private var lastSave = 0.0
    private var behaviourMature = 0
    private var behaviourReady = false

    init {
        // Lo normal de la escena sobrevive a cerrar la app: son agregados por
        // celda (rumbos, velocidades, clases), ningún recorrido individual.
        archivoEscena?.takeIf { it.exists() }?.let { f ->
            runCatching { eus.argos.core.BehaviourModel.deserialize(f.readText()) }.getOrNull()
                ?.let { brain.behaviour = it }
        }
    }

    /*
     * Lo aprendido de una cámara FIJA por red, además de lo normal de la
     * escena: dónde están sus rótulos sobreimpresos y qué zoom le va. Se guarda
     * junto a lo demás de esa cámara (`<archivo>.fijos`).
     */
    @Volatile private var fuenteFija = false
    @Volatile private var fijos = eus.argos.core.Fijos()
    private val congelada = eus.argos.core.Congelada()
    private fun archivoFijos(f: java.io.File) = java.io.File(f.parentFile, f.name.removeSuffix(".txt") + ".fijos")

    /*
     * Zonas dibujadas sobre esta cámara (vigiladas e ignoradas) y sus avisos.
     * Se guardan junto a lo demás de la cámara (`<archivo>.zonas`).
     */
    @Volatile var zonas: List<eus.argos.core.Zona> = emptyList(); private set
    private var alertas = eus.argos.core.Alertas()
    private fun archivoZonas(f: java.io.File) = java.io.File(f.parentFile, f.name.removeSuffix(".txt") + ".zonas")

    private fun cargaZonas(archivo: java.io.File?) {
        zonas = archivo?.let(::archivoZonas)?.takeIf { it.exists() }
            ?.let { runCatching { eus.argos.core.Zonas.lee(it.readText()) }.getOrNull() } ?: emptyList()
        alertas = eus.argos.core.Alertas()
    }

    /** Las zonas nuevas de la cámara actual: se usan desde el frame siguiente y se guardan ya. */
    fun ponZonas(nuevas: List<eus.argos.core.Zona>) {
        zonas = nuevas
        val f = archivoEscena ?: return
        val txt = eus.argos.core.Zonas.serializa(nuevas)
        try { ioExec.execute { runCatching { f.parentFile?.mkdirs(); archivoZonas(f).writeText(txt) } } } catch (_: Exception) {}
    }

    private fun cargaFijos(archivo: java.io.File?) {
        val fj = eus.argos.core.Fijos()
        var modo = 0.0
        if (fuenteFija) archivo?.let(::archivoFijos)?.takeIf { it.exists() }?.let { f ->
            runCatching {
                val t = f.readText()
                t.lineSequence().firstOrNull { it.startsWith("zoom ") }?.substringAfter(' ')?.toDoubleOrNull()?.let { modo = it }
                fj.lee(t, SystemClock.elapsedRealtime() / 1000.0)
            }
        }
        fijos = fj
        autoZoom = AutoZoom().apply { empiezaEn(modo) }
    }

    private fun saveBehaviour(sync: Boolean) {
        val f = archivoEscena ?: return
        if (fuenteFija) {
            val fj = fijos; val modo = autoZoom.mode
            val txt = "zoom $modo\n" + synchronized(fj) { fj.serializa(SystemClock.elapsedRealtime() / 1000.0) }
            val job = Runnable { runCatching { f.parentFile?.mkdirs(); archivoFijos(f).writeText(txt) } }
            if (sync) job.run() else try { ioExec.execute(job) } catch (_: Exception) {}
        }
        val txt = brain.behaviour?.serialize() ?: return
        val job = Runnable { runCatching { f.parentFile?.mkdirs(); f.writeText(txt) } }
        if (sync) job.run() else try { ioExec.execute(job) } catch (_: Exception) {}
    }
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
    /** Zoom automático: el detector elige el modo según lo que encuentra. */
    @Volatile var autoZoomOn = true
    @Volatile var autoZoom = AutoZoom(); private set
    /** Modo de la última pasada (0 cuadro entero, 2 teselas), para el panel. */
    @Volatile var lastZoom = 0.0; private set
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

    /** El último frame analizado, para el chat («qué ves»). */
    @Volatile var last: Frame? = null; private set

    private var src = ByteArray(0)
    private var small = ByteArray(0)
    private var detBuf = ByteArray(0)

    /*
     * El detector ajustado a las cámaras de tráfico, si viaja en el APK: se usa
     * en las cámaras FIJAS por red. La cámara del móvil sigue con el de serie,
     * que es el que conoce lo de cerca.
     */
    private val sesionTrafico: OrtSession?
    val detectorTrafico: YoloxDetector?
    /** El detector de la escena actual. */
    private val detActual: YoloxDetector get() = if (fuenteFija) detectorTrafico ?: detector else detector

    init {
        // Aquí y no en el primer init: las zonas se declaran después de aquel.
        cargaZonas(archivoEscena)
        this.threads = threads
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Sin espera activa. Por defecto los hilos de ORT giran en vacío
            // entre operadores para ganar microsegundos, y en un aparato con
            // pocos núcleos ese giro le roba la CPU al reloj rápido y a la
            // vista previa. Medido en el emulador del CI (2 núcleos) con el giro
            // puesto: inferencia 2.587 ms y análisis del frame 38-52 ms, frente
            // a 1,8 ms del mismo análisis en la JVM.
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
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
        sesionTrafico = traficoBytes?.let { env.createSession(it, opts) }
        detectorTrafico = sesionTrafico?.let { s ->
            val nombre = s.inputNames.first()
            YoloxDetector(416) { tensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 416, 416)).use { t ->
                    s.run(mapOf(nombre to t)).use { r ->
                        val fb = (r[0] as OnnxTensor).floatBuffer
                        FloatArray(fb.remaining()).also { fb.get(it) }
                    }
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
        detectorTrafico?.detect(zeros, 416 * 4, 416, 416)
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
        nuevaEscena?.let { (movil, archivo) ->
            nuevaEscena = null
            saveBehaviour(false)            // lo aprendido de la escena que se deja
            // Lo que estaba vivo se guarda en la memoria antes de cambiar.
            val vivos = brain.flushLive()
            store?.let { s -> try { ioExec.execute { vivos.forEach { s.save(it, t0Epoch) } } } catch (_: Exception) {} }
            engine = Engine().apply { egoMotion = movil }
            brain = SceneBrain()
            archivoEscena = archivo
            fuenteFija = !movil
            cargaFijos(archivo)
            cargaZonas(archivo)
            archivo?.takeIf { it.exists() }?.let { f ->
                runCatching { eus.argos.core.BehaviourModel.deserialize(f.readText()) }.getOrNull()?.let { brain.behaviour = it }
            }
            fresh.set(false)
        }
        if (fondoViejo) {
            fondoViejo = false
            engine = Engine().apply { egoMotion = !fuenteFija }
            fresh.set(false)
        }
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
        val colgada = fuenteFija && congelada.paso(small, SystemClock.elapsedRealtime() / 1000.0)

        // Las cajas del detector entran UNA vez, en el frame en que llegan.
        // Repetirlas hasta la pasada siguiente congela la caja mientras la
        // persona sigue andando; entre pasadas manda la resta de fondo y la
        // pista conserva la clase que le dio el detector.
        // Las cajas del detector se guardan en coordenadas de ESCENA (imagen +
        // desplazamiento acumulado de la cámara) y se devuelven a las de este
        // frame: si el móvil se ha movido desde que se capturó el frame que vio
        // el detector, la caja va donde está el objeto ahora, no donde estaba.
        val sc = w.toDouble() / aw
        val det = if (fresh.getAndSet(false))
            memory.forTracking()?.map { it.shifted(-engine.camX * sc, -engine.camY * sc) } else null
        val r = engine.analyze(small, aw, ah, w, h, det)
        // En una cámara fija, un «movimiento» que solo es luz (un faro que barre
        // el asfalto, su reflejo, una farola que se enciende) no se enseña.
        val fondo = engine.fondo
        val cajas = if (fuenteFija && fondo != null && engine.analysisW == aw) r.boxes.filter { b ->
            b.klassSrc == "det" || !eus.argos.core.Luz.esLuz(small, fondo, aw, ah, (b.x1 / sc).toInt(), (b.y1 / sc).toInt(),
                Math.ceil(b.x2 / sc).toInt(), Math.ceil(b.y2 / sc).toInt())
        } else r.boxes
        val zs = zonas
        val vistas = eus.argos.core.Zonas.filtra(cajas, w, h, zs)
        val avisos = alertas.paso(vistas, w, h, zs, SystemClock.elapsedRealtime() / 1000.0)
        val t3 = System.nanoTime()

        // La escena: física en coordenadas de escena (sirve aunque el móvil se
        // mueva) y escala métrica solo con la cámara quieta.
        val tSec = SystemClock.elapsedRealtime() / 1000.0
        val info = brain.update(engine.tracker.tracks, engine.tracker.minHits, tSec, sc, h.toDouble() / ah,
                                w, h, engine.camX * sc, engine.camY * sc, !engine.cameraMoving, engine.justSettled)
        if (tSec - lastSave > 60) {
            lastSave = tSec
            saveBehaviour(false)
            brain.behaviour?.maturity()?.let { behaviourMature = it.cellsMature; behaviourReady = it.ready }
        }
        if (brain.finished.isNotEmpty()) {
            val done = ArrayList(brain.finished); brain.finished.clear()
            store?.let { s -> try { ioExec.execute { done.forEach { s.save(it, t0Epoch) } } } catch (_: Exception) {} }
        }

        maybeDetect(rowStride, w, h, engine.camX * sc, engine.camY * sc)

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
        return Frame(vistas, w, h, info.associateBy { it.id }, engine.camX * sc, engine.camY * sc,
                     engine.cameraMoving, brain.people, brain.vehicles, brain.seen["person"]?.size ?: 0,
                     brain.scale.valid, brain.scale.n, brain.events.take(3), behaviourMature, behaviourReady,
                     colgada, fijos.fijas, avisos)
            .also { last = it }
    }

    /**
     * El reloj lento. Si el detector está libre se le da una COPIA de este
     * frame y se sigue; si está ocupado, nada: el frame no se encola. Encolar
     * frames viejos es exactamente lo que convierte un detector lento en uno
     * que va siempre por detrás.
     */
    private fun maybeDetect(rowStride: Int, w: Int, h: Int, camX: Double, camY: Double) {
        if (!busy.compareAndSet(false, true)) return
        val need = rowStride * h
        if (detBuf.size < need) detBuf = ByteArray(need)
        System.arraycopy(src, 0, detBuf, 0, need)
        val buf = detBuf
        try { detExec.execute {
            try {
                val t0 = System.nanoTime()
                // Una pasada es del modo PRINCIPAL o un SONDEO del otro. El
                // sondeo solo sirve para decidir el modo: si sus (pocas) cajas
                // entraran en la memoria del seguimiento, una de cada cuatro
                // pasadas lo dejaría casi sin cajas durante ~1 s —más que el
                // margen de una pista— y en una multitud las pistas morirían y
                // renacerían con id nuevo. Así que el sondeo NO toca la memoria.
                val modoPrincipal = if (autoZoomOn) autoZoom.mode else zoom
                val z = if (autoZoomOn) autoZoom.next() else zoom
                val esSondeo = autoZoomOn && z != modoPrincipal
                val d = detActual
                val crudas: List<DetBox> = d.detect(buf, rowStride, w, h, z)
                // En una cámara fija por red, los rótulos aprendidos no son
                // objetos: ni entran en el seguimiento ni empujan al zoom.
                val fj = fijos
                val dets = if (fuenteFija) synchronized(fj) {
                    fj.filtra(crudas, h, SystemClock.elapsedRealtime() / 1000.0) { d -> eus.argos.core.Luz.fraccion(buf, rowStride, w, h, d) }
                } else crudas
                if (autoZoomOn) autoZoom.report(z, dets)
                lastZoom = z
                lastTiles = d.lastTiles
                val ms = (System.nanoTime() - t0) / 1e6
                if (!esSondeo) {
                    memory.update(dets.map { it.shifted(camX, camY) }, SystemClock.elapsedRealtime().toDouble())
                    fresh.set(true)
                }
                val a = if (inferences == 0L) 1.0 else 0.2
                detMs = detMs * (1 - a) + ms * a
                inferMs = inferMs * (1 - a) + d.lastInferMs * a
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
        saveBehaviour(true)
        // Lo que estaba en pantalla al cerrar también se guarda.
        store?.let { s -> brain.flushLive().forEach { runCatching { s.save(it, t0Epoch) } } }
        ioExec.shutdown()
        detExec.shutdown()
        try { detExec.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: InterruptedException) { }
        session.close()
        sesionTrafico?.close()
    }
}
