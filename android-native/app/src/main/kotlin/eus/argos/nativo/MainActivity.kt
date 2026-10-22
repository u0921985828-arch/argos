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
    private lateinit var nPersonas: TextView
    private lateinit var nVehiculos: TextView
    private lateinit var nSesion: TextView
    private lateinit var lEscena: TextView
    private lateinit var lEvento: TextView
    private lateinit var lRadio: TextView
    private lateinit var fichaRadio: TextView
    private lateinit var fichaZoom: TextView
    private lateinit var fichaEstable: TextView
    /** Vista previa y capa juntas: el estabilizado las mueve a la vez y las cajas siguen en su sitio. */
    private lateinit var escena: FrameLayout
    private val estabilizador = eus.argos.core.Estabilizador()
    // Fuente del vídeo: la cámara del móvil o una por red (pública o IP).
    private lateinit var fuenteRed: FuenteRed
    private lateinit var textura: android.view.TextureView
    private lateinit var fichaFuente: TextView
    private var proveedor: ProcessCameraProvider? = null
    @Volatile private var fuenteMovil = true
    private val ocupadoRed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var bufRed: ByteBuffer = ByteBuffer.allocate(0)
    @Volatile private var catalogo: List<eus.argos.core.CamaraPublica>? = null
    private val giro = eus.argos.core.EstabilizaGiro()
    @Volatile private var estabilizar = true
    @Volatile private var ois = false
    // Lo último de cada fuente: desplazamiento (del análisis) y giro (del giroscopio).
    @Volatile private var despX = 0f
    @Volatile private var despY = 0f
    @Volatile private var giroGrados = 0f
    private var ultimoGiroNs = 0L
    private val sensores by lazy { getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager }
    private val oyenteGiro = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val dt = if (ultimoGiroNs == 0L) 0.0 else (e.timestamp - ultimoGiroNs) / 1e9
            ultimoGiroNs = e.timestamp
            // values[2]: giro alrededor del eje que sale de la pantalla, el de
            // la cámara trasera. Positivo = antihorario visto de frente.
            giroGrados = giro.paso(e.values[2].toDouble(), dt).toFloat()
            if (estabilizar && fuenteMovil) aplicaVista()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private lateinit var estado: TextView
    private lateinit var radio: RadioVigia
    private val prefs by lazy { getSharedPreferences("argos", MODE_PRIVATE) }
    private val analysisExec = Executors.newSingleThreadExecutor { r -> Thread(r, "argos-rapido") }
    private var lastLog = 0L
    @Volatile private var destroyed = false
    private lateinit var chat: Chat
    @Volatile private var ask: eus.argos.core.Ask? = null
    @Volatile private var store: eus.argos.core.TrackStore? = null
    @Volatile private var voz: VozApp? = null
    private var chatProbado = false

    private val askMicro = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) chat.escuchar()
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else hud.text = "Sin permiso de cámara no hay nada que analizar."
    }

    private val askRadio = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        encenderRadio()
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
        radio = RadioVigia(this)
        val d = { v: Int -> Ui.dp(this, v) }

        // Arriba, solo lo que se mira: logo, nombre y estado. Todo lo que se
        // TOCA va abajo, al alcance del pulgar.
        estado = TextView(this).apply {
            text = "● cargando"; setTextColor(Ui.SUAVE); textSize = 13f
            setPadding(d(10), d(4), d(10), d(4))
            background = Ui.fondo(Color.argb(140, 20, 24, 30), d(12).toFloat())
        }
        fichaZoom = Ui.fila(this, "Zoom", "auto") { cambiarZoom() }
        fichaRadio = Ui.fila(this, "Radio pasiva", "apagada") { alternarRadio() }
        estabilizar = prefs.getBoolean("estabilizar", true)
        fichaEstable = Ui.fila(this, "Estabilizar", if (estabilizar) "sí" else "no") {
            estabilizar = !estabilizar
            prefs.edit().putBoolean("estabilizar", estabilizar).apply()
            fichaEstable.text = Ui.textoFila("Estabilizar", if (estabilizar) (if (ois) "sí · óptico" else "sí") else "no")
            if (!estabilizar) { escena.scaleX = 1f; escena.scaleY = 1f; escena.translationX = 0f; escena.translationY = 0f; escena.rotation = 0f }
            estabilizador.reinicia(); giro.reinicia()
        }
        val barra = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(10), d(16), d(14))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
            addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_argos) },
                    android.widget.LinearLayout.LayoutParams(d(34), d(34)).apply { rightMargin = d(8) })
            addView(TextView(this@MainActivity).apply {
                text = "ARGOS"; setTextColor(Ui.TEXTO); textSize = 20f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                letterSpacing = 0.14f
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(estado)
        }

        // La tarjeta: tres cifras grandes y una línea de lo último que pasó.
        val (cP, vP) = Ui.cifra(this, "personas", Ui.VERDE)
        val (cV, vV) = Ui.cifra(this, "vehículos", Ui.AZUL)
        val (cS, vS) = Ui.cifra(this, "hoy en sesión")
        nPersonas = vP; nVehiculos = vV; nSesion = vS
        lEscena = Ui.linea(this, 12f, Ui.SUAVE)
        lEvento = Ui.linea(this, 14f, Ui.AMBAR).apply { maxLines = 2 }
        lRadio = Ui.linea(this, 13f, Ui.AZUL).apply { visibility = android.view.View.GONE; maxLines = 2 }
        hud = Ui.linea(this, 12f, Ui.SUAVE).apply { text = "Cargando el detector…" }
        val tarjeta = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(18), d(14), d(18), d(14))
            background = Ui.fondo(Ui.TARJETA, d(22).toFloat(), Ui.BORDE, this@MainActivity)
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(cP, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(cV, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(cS, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(lEvento, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8) })
            addView(lRadio)
        }

        // Paneles que suben desde abajo: el chat y los ajustes.
        lateinit var chatCaja: FrameLayout
        lateinit var ajustesCaja: android.widget.LinearLayout
        lateinit var fuentesCaja: android.widget.LinearLayout
        lateinit var botonera: android.widget.LinearLayout
        fun panel(abrir: android.view.View?) {
            chatCaja.visibility = if (abrir === chatCaja) android.view.View.VISIBLE else android.view.View.GONE
            ajustesCaja.visibility = if (abrir === ajustesCaja) android.view.View.VISIBLE else android.view.View.GONE
            fuentesCaja.visibility = if (abrir === fuentesCaja) android.view.View.VISIBLE else android.view.View.GONE
            val libre = abrir == null
            tarjeta.visibility = if (libre) android.view.View.VISIBLE else android.view.View.GONE
            botonera.visibility = if (libre) android.view.View.VISIBLE else android.view.View.GONE
        }
        chat = Chat(this, { ask }, { askMicro.launch(Manifest.permission.RECORD_AUDIO) }, { panel(null) }, { voz })
        chatCaja = FrameLayout(this).apply {
            visibility = android.view.View.GONE
            addView(chat.view, FrameLayout.LayoutParams(-1, -1))
        }
        textura = android.view.TextureView(this).apply { visibility = android.view.View.GONE }
        fuenteRed = FuenteRed(this, textura, { b -> frameRed(b) }) { texto, w, h ->
            runOnUiThread {
                lEvento.text = texto
                if (w > 0 && h > 0 && escena.width > 0) {
                    // Encajada y centrada, como la vista previa: así la capa de
                    // cajas, que encaja el frame igual, cae encima.
                    val s = minOf(escena.width.toFloat() / w, escena.height.toFloat() / h)
                    textura.layoutParams = FrameLayout.LayoutParams((w * s).toInt(), (h * s).toInt(), Gravity.CENTER)
                }
            }
        }
        fichaFuente = Ui.fila(this, "Fuente", "cámara del móvil") { panel(fuentesCaja); cargaCatalogo() }
        fuentesCaja = construyeFuentes { panel(null) }.apply { visibility = android.view.View.GONE }
        ajustesCaja = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(d(18), d(14), d(18), d(18))
            background = Ui.fondo(Ui.FONDO, d(24).toFloat())
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Ajustes"; setTextColor(Ui.TEXTO); textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.boton(this@MainActivity, R.drawable.ic_cerrar, "Cerrar", 44, Color.TRANSPARENT) { panel(null) })
            })
            addView(fichaFuente); addView(fichaEstable); addView(fichaZoom); addView(fichaRadio)
            addView(lEscena, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12) })
            addView(hud)
        }
        botonera = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(Ui.accion(this@MainActivity, R.drawable.ic_memoria, "Memoria", false) {
                startActivity(android.content.Intent(this@MainActivity, ResumenActivity::class.java))
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.accion(this@MainActivity, R.drawable.ic_mic, "Hablar", true) { panel(chatCaja) },
                    android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.accion(this@MainActivity, R.drawable.ic_ajustes, "Ajustes", false) { panel(ajustesCaja) },
                    android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        val abajo = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(12), 0, d(12), d(12))
            addView(tarjeta)
            addView(botonera, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(10) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // Pantalla completa: arriba se libra el recorte de la cámara frontal
            // y abajo la barra de gestos, para que nada quede debajo.
            setOnApplyWindowInsetsListener { _, ins ->
                @Suppress("DEPRECATION")
                val arriba = maxOf(ins.systemWindowInsetTop,
                    if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout?.safeInsetTop ?: 0 else 0)
                @Suppress("DEPRECATION")
                val bajo = ins.systemWindowInsetBottom
                barra.setPadding(d(16), arriba + d(8), d(16), d(14))
                abajo.setPadding(d(12), 0, d(12), d(12) + bajo)
                ins
            }
            escena = FrameLayout(this@MainActivity).apply {
                addView(preview, FrameLayout.LayoutParams(-1, -1))
                addView(textura, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                addView(capa, FrameLayout.LayoutParams(-1, -1))
            }
            addView(escena, FrameLayout.LayoutParams(-1, -1))
            addView(barra, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(chatCaja, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62).toInt(), Gravity.BOTTOM).apply {
                setMargins(d(6), 0, d(6), d(6))
            })
            addView(ajustesCaja, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(6), 0, d(6), d(6)) })
            addView(fuentesCaja, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.7).toInt(), Gravity.BOTTOM).apply {
                setMargins(d(6), 0, d(6), d(6))
            })
        })

        // La radio vuelve como se dejó, pero solo si sigue teniendo permisos:
        // nunca se pide nada sin que se pulse.
        if (prefs.getBoolean("radio", false) && radio.permisos().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) encenderRadio()

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
            // El chat: una gramática sobre la memoria, la escena viva y la radio.
            ask = eus.argos.core.Ask(object : eus.argos.core.Ask.Source {
                override fun radio() = this@MainActivity.radio.vivo()
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
            // La voz propia, si el APK la trae: después del detector, que es lo urgente.
            voz = VozApp.carga(this)
            if (destroyed) { voz?.close(); voz = null } else chat.vozLista()
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
            proveedor = provider
            if (!fuenteMovil) return@addListener
            // Misma proporción en vista previa y análisis: con FIT_CENTER las
            // dos ven el mismo campo y las cajas caen donde está el objeto.
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            // Estabilización ÓPTICA si la lente la tiene: mueve el sensor de
            // verdad, así que la vista previa y el análisis siguen alineados
            // (la electrónica recorta y los desalinearía).
            ois = oisDisponible(provider)
            runOnUiThread { fichaEstable.text = Ui.textoFila("Estabilizar", if (estabilizar) (if (ois) "sí · óptico" else "sí") else "no") }
            val prevB = Preview.Builder().setResolutionSelector(selector)
            if (ois) pideOis(prevB)
            val prev = prevB.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                // RGBA girado por CameraX: un solo búfer sirve al detector, que
                // necesita color y la imagen derecha (una persona tumbada 90°
                // no la reconoce), y al modelo de fondo.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .also { if (ois) pideOis(it) }
                .build()
            analysis.setAnalyzer(analysisExec) { image ->
                try {
                    val p = pipeline
                    if (p != null) {
                        val plane = image.planes[0]
                        val f = p.onFrame(plane.buffer, plane.rowStride, image.width, image.height)
                        capa.show(f)
                        estabiliza(f)
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
            Log.i(TAG, "ARGOS nativo cámara: análisis RGBA, objetivo 640x480 · estabilización óptica " +
                       (if (ois) "sí" else "no disponible") + " · estabilizado en pantalla " + (if (estabilizar) "sí" else "no") +
                       " · giroscopio " + (if (sensores.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null) "sí" else "no"))
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun oisDisponible(provider: ProcessCameraProvider): Boolean = runCatching {
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull() ?: return false
        androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
    }.getOrDefault(false)

    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun <T> pideOis(b: androidx.camera.core.ExtendableBuilder<T>) {
        androidx.camera.camera2.interop.Camera2Interop.Extender(b).setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    /**
     * Estabilizado en pantalla: con el movimiento de cámara que ya estima el
     * núcleo, la escena (vista previa y cajas a la vez) se desplaza para
     * quitar el balanceo de la mano, ampliada un 11 % para que no se vean los
     * bordes. Un paneo se deja pasar.
     */
    private fun estabiliza(f: Pipeline.Frame) {
        if (!estabilizar || !fuenteMovil || f.w == 0) return
        val (dx, dy) = estabilizador.paso(f.camX, f.camY, f.w)
        runOnUiThread {
            if (escena.width == 0) return@runOnUiThread
            val s = minOf(escena.width.toFloat() / f.w, escena.height.toFloat() / f.h)
            despX = (dx * s).toFloat(); despY = (dy * s).toFloat()
            aplicaVista()
        }
    }

    /**
     * Desplazamiento (de la imagen) y giro (del giroscopio) sobre la escena,
     * con la ampliación justa para que ninguno de los dos enseñe bordes. Un
     * giro antihorario del móvil hace girar la imagen en sentido horario en
     * pantalla; se compensa girando la vista al revés.
     */
    private fun aplicaVista() {
        if (escena.width == 0) return
        val aspecto = escena.height.toDouble() / escena.width
        val z = (estabilizador.zoom * giro.zoom(aspecto)).toFloat()
        escena.scaleX = z; escena.scaleY = z
        escena.translationX = despX * z; escena.translationY = despY * z
        escena.rotation = -giroGrados
    }

    override fun onResume() {
        super.onResume()
        sensores.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let {
            ultimoGiroNs = 0L
            sensores.registerListener(oyenteGiro, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensores.unregisterListener(oyenteGiro)
        super.onPause()
    }

    private fun cambiarZoom() {
        val p = pipeline ?: return
        when {
            p.autoZoomOn -> { p.autoZoomOn = false; p.zoom = 0.0 }
            p.zoom == 0.0 -> p.zoom = 2.0
            else -> { p.autoZoomOn = true }
        }
        fichaZoom.text = Ui.textoFila("Zoom", when { p.autoZoomOn -> "auto"; p.zoom == 0.0 -> "cuadro entero"; else -> "teselas ×2" })
        Log.i(TAG, "ARGOS nativo modo: " + modo(p))
    }

    /**
     * La radio se enciende solo a mano y después de decir qué hace y qué pide.
     * Android exige permiso de ubicación para escanear Bluetooth y para ver los
     * satélites; se dice tal cual.
     */
    private fun alternarRadio() {
        if (radio.activa) {
            radio.para(); prefs.edit().putBoolean("radio", false).apply()
            fichaRadio.text = Ui.textoFila("Radio pasiva", "apagada"); lRadio.visibility = android.view.View.GONE
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Radio pasiva")
            .setMessage("ARGOS escuchará el Bluetooth y los satélites de GPS de este teléfono para avisarte de " +
                "rastreadores que se quedan cerca (AirTag, Tile, SmartTag…), drones con Remote ID e interferencias o " +
                "suplantación del GPS.\n\nSolo escucha: no emite ni se conecta a nada. No guarda direcciones ni tu " +
                "posición, nada se escribe en el teléfono y al apagarla se olvida todo. No se cruza con las personas de " +
                "la cámara.\n\nAndroid pide permiso de ubicación para escanear Bluetooth y ver los satélites.")
            .setPositiveButton("Encender") { _, _ -> askRadio.launch(radio.permisos()) }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun encenderRadio() {
        val problema = radio.empieza()
        lRadio.visibility = android.view.View.VISIBLE
        // Solo se recuerda «encendida» si algo arrancó de verdad. Con todo
        // denegado se apaga y se dice por qué; no vuelve sola otro día.
        if (!radio.bleEnMarcha && !radio.gnssEnMarcha) {
            radio.para()
            prefs.edit().putBoolean("radio", false).apply()
            fichaRadio.text = Ui.textoFila("Radio pasiva", "apagada")
            lRadio.text = "radio apagada · ${problema ?: "no arrancó"}"
            return
        }
        prefs.edit().putBoolean("radio", true).apply()
        fichaRadio.text = Ui.textoFila("Radio pasiva", "encendida")
        if (problema != null) lRadio.text = "radio · $problema"
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
            f.moving -> "cámara en mano · compensando el movimiento"
            f.scaleValid -> "cámara quieta · escala aprendida de ${f.scaleN} recorridos"
            else -> "cámara quieta · aprendiendo la escala"
        } + " · " + (if (f.behaviourReady) "lo normal: aprendido" else "lo normal: aprendiendo (${f.behaviourMature} zonas)")
        val ev = f.events.firstOrNull()?.let { "último: ${it.kind} · objeto ${it.id} · ${it.detail}" } ?: "sin eventos"
        val tecnico = "%.0f fps · análisis %.1f ms · detector %.0f ms (%d tesela%s) · zoom %s".format(
            loc, p.fps, p.engineMs, p.detMs, p.lastTiles, if (p.lastTiles == 1) "" else "s", modo(p))
        val lineaRadio = radio.linea()
        val aviso = radio.hayAviso()
        runOnUiThread {
            nPersonas.text = f.people.toString(); nVehiculos.text = f.vehicles.toString(); nSesion.text = f.peopleSeen.toString()
            lEscena.text = escena; lEvento.text = ev
            if (radio.activa && lineaRadio.isNotEmpty()) { lRadio.text = lineaRadio; lRadio.setTextColor(if (aviso) Ui.ROJO else Ui.AZUL) }
            hud.text = tecnico
            estado.text = when {
                aviso -> "● aviso de radio"
                f.moving -> "● cámara en mano"
                else -> "● vigilando"
            }
            estado.setTextColor(when { aviso -> Ui.ROJO; f.moving -> Ui.AMBAR; else -> Ui.VERDE })
        }
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
        fuenteRed.cierra()
        chat.close()
        voz?.close()
        radio.para()
        super.onDestroy()
        // Primero el reloj rápido (deja de mandar trabajo al detector), luego
        // el detector, que espera a su inferencia en curso antes de liberar.
        analysisExec.shutdown()
        try { analysisExec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: InterruptedException) { }
        pipeline?.close()
        pipeline = null
    }

    /* ------------------------------ Fuentes ------------------------------ */

    /** Un frame de la cámara por red, al mismo análisis que la del móvil. Si el anterior no ha terminado, se salta. */
    private fun frameRed(b: android.graphics.Bitmap) {
        if (fuenteMovil || !ocupadoRed.compareAndSet(false, true)) return
        if (bufRed.capacity() < b.byteCount) bufRed = ByteBuffer.allocate(b.byteCount)
        bufRed.clear(); b.copyPixelsToBuffer(bufRed); bufRed.rewind()
        val w = b.width; val h = b.height; val stride = b.rowBytes; val buf = bufRed
        try {
            analysisExec.execute {
                try {
                    val p = pipeline
                    if (p != null && !fuenteMovil) { val f = p.onFrame(buf, stride, w, h); capa.show(f); report(p, f) }
                } catch (e: Throwable) {
                    Log.e(TAG, "ARGOS red: frame falló: ${e.message}", e)
                } finally { ocupadoRed.set(false) }
            }
        } catch (_: Exception) { ocupadoRed.set(false) }
    }

    private fun quitaEstabilizado() {
        escena.scaleX = 1f; escena.scaleY = 1f; escena.translationX = 0f; escena.translationY = 0f; escena.rotation = 0f
    }

    private fun usarMovil() {
        fuenteRed.cierra()
        fuenteMovil = true
        textura.visibility = android.view.View.GONE
        preview.visibility = android.view.View.VISIBLE
        pipeline?.nuevaEscena(true, java.io.File(filesDir, "memoria/comportamiento.txt"))
        estabilizador.reinicia(); giro.reinicia()
        fichaFuente.text = Ui.textoFila("Fuente", "cámara del móvil")
        lEvento.text = "cámara del móvil"
        startCamera()
    }

    /** Archivo de lo aprendido de una cámara remota: nombre a partir de su identificador, sin la dirección ni claves. */
    private fun archivoDe(id: String): java.io.File {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return java.io.File(filesDir, "memoria/escenas/$h.txt")
    }

    private fun usarRed(url: String, nombre: String, bucle: Boolean, id: String) {
        fuenteMovil = false
        proveedor?.unbindAll()
        preview.visibility = android.view.View.GONE
        textura.visibility = android.view.View.VISIBLE
        quitaEstabilizado()
        pipeline?.nuevaEscena(false, archivoDe(id))
        fichaFuente.text = Ui.textoFila("Fuente", nombre)
        fuenteRed.abre(url, nombre, bucle)
    }

    /** La lista de cámaras públicas, bajada una vez de los propios organismos. */
    private var listaFuentes: android.widget.ArrayAdapter<String>? = null
    private var filtradas: List<eus.argos.core.CamaraPublica> = emptyList()
    private var filtro = ""

    private fun cargaCatalogo() {
        if (catalogo != null) return
        catalogo = emptyList()
        pintaLista("Buscando cámaras públicas…")
        Thread({
            val todas = ArrayList<eus.argos.core.CamaraPublica>()
            fun baja(url: String): String? = runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { c.disconnect() }
            }.getOrNull()
            for (d in eus.argos.core.Catalogo.DISTRITOS)
                baja(eus.argos.core.Catalogo.urlCaltrans(d))?.let { todas += eus.argos.core.Catalogo.caltrans(it) }
            baja(eus.argos.core.Catalogo.URL_TFL)?.let { todas += eus.argos.core.Catalogo.tfl(it) }
            Log.i(TAG, "ARGOS red: catálogo de cámaras públicas, ${todas.size} con vídeo")
            catalogo = todas.sortedWith(compareBy({ it.proveedor }, { it.nombre }))
            runOnUiThread { pintaLista(if (todas.isEmpty()) "No se pudo bajar la lista. ¿Hay conexión?" else null) }
        }, "argos-catalogo").start()
    }

    private fun pintaLista(aviso: String?) {
        val a = listaFuentes ?: return
        val todas = catalogo.orEmpty()
        val f = eus.argos.core.Ask.norm(filtro)
        filtradas = if (f.isBlank()) todas else todas.filter { eus.argos.core.Ask.norm(it.nombre + " " + it.proveedor).contains(f) }
        a.clear()
        if (aviso != null) a.add(aviso)
        else a.addAll(filtradas.take(400).map { (if (it.directo) "● " else "○ ") + it.proveedor + " · " + it.nombre })
        a.notifyDataSetChanged()
    }

    private fun construyeFuentes(cerrar: () -> Unit): android.widget.LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        val lista = android.widget.ListView(this).apply { divider = null }
        val adapt = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(pos: Int, conv: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                (super.getView(pos, conv, parent) as TextView).apply { setTextColor(Ui.TEXTO); textSize = 15f; minHeight = d(48) }
        }
        listaFuentes = adapt
        lista.adapter = adapt
        lista.setOnItemClickListener { _, _, pos, _ ->
            val c = filtradas.getOrNull(pos) ?: return@setOnItemClickListener
            usarRed(c.video, c.nombre, !c.directo, c.id); cerrar()
        }
        val buscar = android.widget.EditText(this).apply {
            hint = "Buscar (ciudad, carretera…)"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUAVE); isSingleLine = true
            background = Ui.fondo(Color.argb(140, 40, 44, 52), d(14).toFloat())
            setPadding(d(14), d(10), d(14), d(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { filtro = e?.toString().orEmpty(); pintaLista(null) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(18), d(14), d(18), d(14))
            background = Ui.fondo(Ui.FONDO, d(24).toFloat())
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Fuente de vídeo"; setTextColor(Ui.TEXTO); textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.boton(this@MainActivity, R.drawable.ic_cerrar, "Cerrar", 44, Color.TRANSPARENT) { cerrar() })
            })
            addView(Ui.fila(this@MainActivity, "Cámara del móvil", "la de este teléfono") { usarMovil(); cerrar() })
            addView(Ui.fila(this@MainActivity, "Cámara IP propia", "dirección RTSP de tu cámara") { pideRtsp(cerrar) })
            addView(TextView(this@MainActivity).apply {
                text = "Cámaras públicas de tráfico   ● directo   ○ clips"
                setTextColor(Ui.SUAVE); textSize = 13f; setPadding(d(4), d(14), 0, d(6))
            })
            addView(buscar)
            addView(lista, android.widget.LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d(6) })
        }
    }

    /** Cámara IP: la dirección RTSP con usuario y clave; se recuerda en este teléfono. */
    private fun pideRtsp(cerrar: () -> Unit) {
        val campo = android.widget.EditText(this).apply {
            hint = "rtsp://usuario:clave@192.168.1.50:554/stream1"
            setText(prefs.getString("rtsp", "")); isSingleLine = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cámara IP")
            .setMessage("La dirección RTSP de tu cámara. Suele venir en su manual o en su app (Hikvision, Dahua, Reolink, Tapo…). El móvil tiene que estar en su misma wifi, o conectado por VPN.")
            .setView(campo)
            .setPositiveButton("Conectar") { _, _ ->
                val url = campo.text.toString().trim()
                if (url.startsWith("rtsp://", ignoreCase = true) || url.startsWith("http", ignoreCase = true)) {
                    prefs.edit().putString("rtsp", url).apply()
                    // La clave no entra en el identificador: solo servidor y ruta.
                    usarRed(url, "cámara IP", false, "ip:" + url.substringAfter("@")); cerrar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
