package eus.argos.nativo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    @Volatile private var pipeline: Pipeline? = null
    private lateinit var preview: PreviewView
    // No se llama `overlay`: dentro de `FrameLayout.apply` ese nombre es la
    // `ViewGroupOverlay` de la propia vista y el compilador elige esa.
    private lateinit var capa: Overlay
    private lateinit var hud: TextView
    private val analysisExec = Executors.newSingleThreadExecutor { r -> Thread(r, "argos-rapido") }
    private var lastLog = 0L
    @Volatile private var destroyed = false
    private lateinit var chat: Chat
    @Volatile private var ask: eus.argos.core.Ask? = null
    @Volatile private var store: eus.argos.core.TrackStore? = null
    private var chatProbado = false

    private val askMicro = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) chat.escuchar()
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else hud.text = "Sin permiso de cámara no hay nada que analizar."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El sistema guarda una captura de la pantalla para la lista de
        // recientes: sería un fotograma de la cámara escrito en disco, y aquí
        // no se guarda vídeo ni recortes. Las capturas que hace el usuario
        // siguen funcionando (FLAG_SECURE las bloquearía también).
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        capa = Overlay(this)
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 13f; setPadding(18, 12, 18, 12); text = "Cargando el detector…"
        }
        chat = Chat(this, { ask }, { askMicro.launch(Manifest.permission.RECORD_AUDIO) })
        chat.view.visibility = android.view.View.GONE
        val botonChat = android.widget.Button(this).apply {
            text = "Hablar con ARGOS"
            setOnClickListener {
                chat.view.visibility = if (chat.view.visibility == android.view.View.VISIBLE) android.view.View.GONE
                                       else android.view.View.VISIBLE
            }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // Un toque recorre los modos del detector: automático → cuadro
            // entero → teselas ×2 → automático. Una pulsación larga abre la
            // memoria del día.
            setOnClickListener {
                val p = pipeline ?: return@setOnClickListener
                when {
                    p.autoZoomOn -> { p.autoZoomOn = false; p.zoom = 0.0 }
                    p.zoom == 0.0 -> p.zoom = 2.0
                    else -> { p.autoZoomOn = true }
                }
                Log.i(TAG, "ARGOS nativo modo: " + modo(p))
            }
            setOnLongClickListener {
                startActivity(android.content.Intent(this@MainActivity, ResumenActivity::class.java)); true
            }
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(capa, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(chat.view, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55).toInt(), Gravity.TOP))
            addView(botonChat, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        })

        // El detector se carga fuera del hilo de la interfaz: leer 3,6 MB de
        // modelo y pagar la primera inferencia bloquearía la pantalla.
        Thread({ loadDetector() }, "argos-carga").start()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun loadDetector() {
        try {
            val model = assets.open("yolox_nano.onnx").use { it.readBytes() }
            // Un núcleo queda para el reloj rápido: el detector no puede
            // comérselos todos o la cámara va a tirones mientras infiere.
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (cores - 1).coerceIn(1, 4)
            // La memoria: trayectorias por día, sin imagen; la retención de 30
            // días se aplica al abrir, como en la web.
            val store = eus.argos.core.TrackStore(java.io.File(filesDir, "memoria"))
            val borrados = store.applyRetention(System.currentTimeMillis() / 1000.0)
            Log.i(TAG, "ARGOS nativo memoria: retención aplicada, $borrados día(s) borrados")
            this.store = store
            val p = Pipeline(model, threads, store, java.io.File(filesDir, "memoria/comportamiento.txt"))
            val warm = p.warmup()
            // El proveedor que se nombra es el que se usa: aquí no se pide
            // ninguno más que la CPU, así que no hay nada que pueda caerse en
            // silencio a otro. La lección del WebView fue un panel que decía
            // «webgpu» corriendo en WASM.
            Log.i(TAG, "ARGOS nativo detector: ONNX Runtime Android cpu, $threads hilo(s), " +
                       "entrada 416 px, primera inferencia $warm ms")
            selfTest(p)
            // Si la actividad murió mientras cargaba, nadie cerraría esta sesión.
            if (destroyed) { p.close(); return }
            pipeline = p
            // El chat: una gramática sobre la memoria y la escena viva.
            ask = eus.argos.core.Ask(object : eus.argos.core.Ask.Source {
                override fun rows(desdeS: Double?, hastaS: Double?, nowS: Double) = store.rowsBetween(desdeS, hastaS, nowS)
                override fun live(): eus.argos.core.Ask.Live? {
                    val f = pipeline?.last ?: return null
                    val raros = f.info.values.filter { (it.anomaly?.score ?: 0.0) >= 0.2 }
                        .sortedByDescending { it.anomaly!!.score }
                        .map { "objeto ${it.id}: " + (it.anomaly!!.reasons.firstOrNull() ?: "fuera de lo habitual") }
                    return eus.argos.core.Ask.Live(f.people, f.vehicles, f.boxes.size, raros, f.moving, f.scaleValid,
                                                   f.events.map { "${it.kind} #${it.id}" })
                }
            })
            Log.i(TAG, "ARGOS nativo listo")
        } catch (e: Throwable) {
            Log.e(TAG, "ARGOS nativo: el detector no cargó: ${e.message}", e)
            runOnUiThread { hud.text = "El detector no cargó: ${e.message}" }
        }
    }

    /**
     * Autoprueba con una foto fija empaquetada: perro, bicicleta y furgoneta,
     * sin personas a propósito. Pasa por el MISMO detector que la cámara y su
     * resultado se compara en el CI con el de `detector.js` sobre esa foto.
     *
     * Es la diferencia entre «el detector cargó» y «el detector ve»: un modelo
     * que carga y no detecta nada --- entrada normalizada, rejilla sin
     * decodificar --- no da ningún error, solo cero cajas.
     */
    private fun selfTest(p: Pipeline) {
        val bmp = assets.open("autoprueba.jpg").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inScaled = false
            })
        } ?: throw IllegalStateException("autoprueba.jpg no se pudo decodificar")
        val buf = ByteBuffer.allocate(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)      // ARGB_8888 en memoria es R,G,B,A
        val t0 = SystemClock.elapsedRealtime()
        val dets = p.detector.detect(buf.array(), bmp.rowBytes, bmp.width, bmp.height)
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "ARGOS autoprueba: ${dets.size} obj en ${bmp.width}x${bmp.height}, $ms ms · " +
                   dets.joinToString(" · ") { "${it.klass} ${"%.3f".format(java.util.Locale.ROOT, it.score)}" })
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // El proveedor de cámara llega cuando quiere. Si para entonces esta
            // actividad ya se destruyó (el sistema la recreó), atar la cámara a
            // su ciclo de vida tumba la app: «LifecycleCamera with destroyed
            // lifecycle», visto en el emulador del CI.
            if (isFinishing || isDestroyed ||
                lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) return@addListener
            val provider = future.get()
            // Misma proporción en vista previa y análisis: con FIT_CENTER las
            // dos ven el mismo campo y las cajas caen donde está el objeto.
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val prev = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                // RGBA girado por CameraX: un solo búfer sirve al detector, que
                // necesita color y la imagen derecha (una persona tumbada 90°
                // no la reconoce), y al modelo de fondo.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExec) { image ->
                try {
                    val p = pipeline
                    if (p != null) {
                        val plane = image.planes[0]
                        val f = p.onFrame(plane.buffer, plane.rowStride, image.width, image.height)
                        capa.show(f)
                        report(p, f)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "ARGOS nativo: frame falló: ${e.message}", e)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis)
            Log.i(TAG, "ARGOS nativo cámara: análisis RGBA, objetivo 640x480")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun modo(p: Pipeline) = when {
        p.autoZoomOn -> "automático (" + (if (p.autoZoom.mode == 0.0) "cuadro entero" else "teselas ×2") + ")"
        p.zoom == 0.0 -> "cuadro entero"
        else -> "teselas ×2"
    }

    private fun report(p: Pipeline, f: Pipeline.Frame) {
        val now = SystemClock.elapsedRealtime()
        val det = f.boxes.count { it.klassSrc == "det" }
        val loc = java.util.Locale("es")
        val escena = when {
            f.moving -> "cámara en mano: compensando el movimiento"
            f.scaleValid -> "cámara quieta · escala aprendida de ${f.scaleN} trayectorias"
            else -> "cámara quieta · aprendiendo la escala"
        }
        val ev = f.events.firstOrNull()?.let { "último: ${it.kind} #${it.id} · ${it.detail}" } ?: "sin eventos"
        val normal = if (f.behaviourReady) "lo normal de la escena: aprendido (${f.behaviourMature} zonas)"
                     else "lo normal de la escena: aprendiendo (${f.behaviourMature} zonas)"
        val txt = "personas %d · vehículos %d · %d en esta sesión\n".format(loc, f.people, f.vehicles, f.peopleSeen) +
            escena + "\n" + normal + "\n" + ev + "\n" +
            "%.0f fps · análisis %.1f ms · detector %.0f ms (%d tesela%s)\n".format(
                loc, p.fps, p.engineMs, p.detMs, p.lastTiles, if (p.lastTiles == 1) "" else "s") +
            "modo " + modo(p) + " · toca para cambiar · mantén para la memoria"
        runOnUiThread { hud.text = txt }
        // Una pregunta de prueba al chat cuando ya hay escena, para que el
        // registro diga que responde y no solo que existe.
        if (!chatProbado && p.frames > 60) {
            chatProbado = true
            val r = ask?.ask("qué ves") ?: "(sin chat)"
            Log.i(TAG, "ARGOS chat: voz ${chat.reconocimiento} · «qué ves» -> $r")
        }
        if (now - lastLog > 3000) {
            lastLog = now
            Log.i(TAG, ("ARGOS nativo: frames %d · %.1f fps · %dx%d · captura %.2f ms · reducir %.2f ms · " +
                        "análisis %.2f ms · detector %.0f ms (inferencia %.0f ms, %d inferencias, %d obj) · " +
                        "pistas %d (det %d)").format(java.util.Locale.ROOT, p.frames, p.fps, f.w, f.h,
                        p.grabMs, p.scaleMs, p.engineMs, p.detMs, p.inferMs, p.inferences, p.lastDets,
                        f.boxes.size, det))
            Log.i(TAG, "ARGOS nativo escena: " + (if (f.moving) "en mano" else "quieta") +
                       " · escala " + (if (f.scaleValid) "válida" else "aprendiendo") +
                       " · personas ${f.people} · vistas ${f.peopleSeen} · zonas aprendidas ${f.behaviourMature}" +
                       " · modo ${modo(p)}")
        }
    }

    override fun onDestroy() {
        destroyed = true
        chat.close()
        super.onDestroy()
        // Primero el reloj rápido (deja de mandar trabajo al detector), luego
        // el detector, que espera a su inferencia en curso antes de liberar.
        analysisExec.shutdown()
        try { analysisExec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: InterruptedException) { }
        pipeline?.close()
        pipeline = null
    }
}
