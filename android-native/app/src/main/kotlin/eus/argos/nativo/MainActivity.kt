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

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else hud.text = "Sin permiso de cámara no hay nada que analizar."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        capa = Overlay(this)
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 13f; setPadding(18, 12, 18, 12); text = "Cargando el detector…"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(capa, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val p = Pipeline(model, threads)
            val warm = p.warmup()
            // El proveedor que se nombra es el que se usa: aquí no se pide
            // ninguno más que la CPU, así que no hay nada que pueda caerse en
            // silencio a otro. La lección del WebView fue un panel que decía
            // «webgpu» corriendo en WASM.
            Log.i(TAG, "ARGOS nativo detector: ONNX Runtime Android cpu, $threads hilo(s), " +
                       "entrada 416 px, primera inferencia $warm ms")
            selfTest(p)
            pipeline = p
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

    private fun report(p: Pipeline, f: Pipeline.Frame) {
        val now = SystemClock.elapsedRealtime()
        val det = f.boxes.count { it.klassSrc == "det" }
        val txt = "%.0f fps · %dx%d\ncaptura %.1f · reducir %.1f · análisis %.1f ms\n".format(
            java.util.Locale.ROOT, p.fps, f.w, f.h, p.grabMs, p.scaleMs, p.engineMs) +
            "detector %.0f ms (inferencia %.0f) · %d hilos · %d objetos\n".format(
            java.util.Locale.ROOT, p.detMs, p.inferMs, p.threads, p.lastDets) +
            "pistas %d · con clase del detector %d".format(f.boxes.size, det)
        runOnUiThread { hud.text = txt }
        if (now - lastLog > 3000) {
            lastLog = now
            Log.i(TAG, ("ARGOS nativo: frames %d · %.1f fps · %dx%d · captura %.2f ms · reducir %.2f ms · " +
                        "análisis %.2f ms · detector %.0f ms (inferencia %.0f ms, %d inferencias, %d obj) · " +
                        "pistas %d (det %d)").format(java.util.Locale.ROOT, p.frames, p.fps, f.w, f.h,
                        p.grabMs, p.scaleMs, p.engineMs, p.detMs, p.inferMs, p.inferences, p.lastDets,
                        f.boxes.size, det))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExec.shutdownNow()
        pipeline?.close()
    }
}
