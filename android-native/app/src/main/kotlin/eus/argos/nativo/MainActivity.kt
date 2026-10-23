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
import eus.argos.core.Orden
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
    @Volatile private var cargandoCatalogo = false
    @Volatile private var fallosCatalogo = ""
    @Volatile private var insetArriba = 0
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
    private val pideAvisos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        chat = Chat(this, { ask }, { askMicro.launch(Manifest.permission.RECORD_AUDIO) }, { panel(null) }, { voz }, { o -> manda(o) })
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
        fuenteRed.libre = { !ocupadoRed.get() }
        fichaFuente = Ui.fila(this, "Fuente", "cámara del móvil") { panel(fuentesCaja); cargaCatalogo() }
        abre = { q ->
            panel(when (q) { "ajustes" -> ajustesCaja; "fuentes" -> fuentesCaja; "chat" -> chatCaja; else -> null })
            if (q == "fuentes") cargaCatalogo()
        }
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
            addView(fichaFuente)
            addView(Ui.fila(this@MainActivity, "Zonas", "dibujar dónde avisar y qué ignorar") { panel(null); empiezaZonas() })
            addView(fichaEstable); addView(fichaZoom); addView(fichaRadio)
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
                insetArriba = arriba
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
            padPtz = construyePad()
            addView(padPtz, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, d(10), 0) })
            addView(barra, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(chatCaja, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62).toInt(), Gravity.BOTTOM).apply {
                setMargins(d(6), 0, d(6), d(6))
            })
            addView(ajustesCaja, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(6), 0, d(6), d(6)) })
            addView(fuentesCaja, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.7).toInt(), Gravity.BOTTOM).apply {
                setMargins(d(6), 0, d(6), d(6))
            })
            barraZonas = construyeBarraZonas()
            addView(barraZonas, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(6), 0, d(6), d(6)) })
        })

        // La prueba del CI pide la lista de cámaras públicas al arrancar, para
        // ver en el aparato que se baja y se lee (en uso, al abrir «Fuente»).
        if (intent?.getBooleanExtra("catalogo", false) == true) cargaCatalogo()

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
            val trafico = runCatching { assets.open("argos_trafico.onnx").use { it.readBytes() } }.getOrNull()
            val p = Pipeline(model, threads, store, java.io.File(filesDir, "memoria/comportamiento.txt"), trafico)
            Log.i(TAG, "ARGOS nativo detector de tráfico: " + (if (trafico != null) "entrenado con cámaras públicas, ${trafico.size / 1024} KB" else "no viene; se usa el de serie"))
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
            // Hasta 60 imágenes por segundo si la cámara las da a esta
            // resolución. Con rango variable (p. ej. 15-60) la exposición puede
            // alargarse de noche: una imagen oscura no la ve el detector.
            val fps = mejorFps(provider)
            fps?.let { pideFps(prevB, it) }
            val prev = prevB.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                // RGBA girado por CameraX: un solo búfer sirve al detector, que
                // necesita color y la imagen derecha (una persona tumbada 90°
                // no la reconoce), y al modelo de fondo.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .also { if (ois) pideOis(it); fps?.let { r -> pideFps(it, r) } }
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
            Log.i(TAG, "ARGOS nativo cámara: análisis RGBA, objetivo 640x480 · ${fps?.let { "${it.lower}-${it.upper} fps" } ?: "fps por defecto"} · estabilización óptica " +
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

    /** El rango de imágenes por segundo más alto (hasta 60) que admite la cámara trasera; dentro de él, el más amplio. */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun mejorFps(provider: ProcessCameraProvider): android.util.Range<Int>? = runCatching {
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper <= 60 }?.maxWithOrNull(compareBy<android.util.Range<Int>>({ it.upper }, { -it.lower }))
    }.getOrNull()

    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun <T> pideFps(b: androidx.camera.core.ExtendableBuilder<T>, r: android.util.Range<Int>) {
        androidx.camera.camera2.interop.Camera2Interop.Extender(b).setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
    }

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
            f.congelada -> "la cámara no se actualiza: da siempre la misma imagen"
            f.moving -> "cámara en mano · compensando el movimiento"
            f.scaleValid -> "cámara quieta · escala aprendida de ${f.scaleN} recorridos"
            else -> "cámara quieta · aprendiendo la escala"
        } + " · " + (if (f.behaviourReady) "lo normal: aprendido" else "lo normal: aprendiendo (${f.behaviourMature} zonas)") +
            (if (f.rotulos > 0) " · ${f.rotulos} rótulo${if (f.rotulos == 1) "" else "s"} de la cámara ignorado${if (f.rotulos == 1) "" else "s"}" else "")
        val ev = f.events.firstOrNull()?.let { "último: ${it.kind} · objeto ${it.id} · ${it.detail}" } ?: "sin eventos"
        val tecnico = "%.0f fps · análisis %.1f ms · detector %.0f ms (%d tesela%s) · zoom %s".format(
            loc, p.fps, p.engineMs, p.detMs, p.lastTiles, if (p.lastTiles == 1) "" else "s", modo(p))
        if (f.avisos.isNotEmpty()) f.avisos.forEach { avisaZona(it) }
        val lineaRadio = radio.linea()
        val aviso = radio.hayAviso()
        runOnUiThread {
            nPersonas.text = f.people.toString(); nVehiculos.text = f.vehicles.toString(); nSesion.text = f.peopleSeen.toString()
            lEscena.text = escena; lEvento.text = ev
            if (radio.activa && lineaRadio.isNotEmpty()) { lRadio.text = lineaRadio; lRadio.setTextColor(if (aviso) Ui.ROJO else Ui.AZUL) }
            hud.text = tecnico
            estado.text = when {
                aviso -> "● aviso de radio"
                f.congelada -> "● imagen congelada"
                f.moving -> "● cámara en mano"
                else -> "● vigilando"
            }
            estado.setTextColor(when { aviso -> Ui.ROJO; f.moving || f.congelada -> Ui.AMBAR; else -> Ui.VERDE })
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
        camara?.cierra()
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
        camara?.cierra(); camara = null; padPtz.visibility = android.view.View.GONE
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

    /** La lista de cámaras públicas, bajada de los propios organismos. */
    private var listaFuentes: android.widget.ArrayAdapter<String>? = null
    private var estadoFuentes: TextView? = null
    private var filtradas: List<eus.argos.core.CamaraPublica> = emptyList()
    private var filtro = ""

    /**
     * Las diez listas se piden a la vez y cada una se enseña según llega: en
     * serie, con un distrito lento, la lista tardaba un minuto en salir y
     * parecía rota. Si no llega ninguna se puede reintentar.
     */
    private fun cargaCatalogo() {
        if (cargandoCatalogo || !catalogo.isNullOrEmpty()) return
        cargandoCatalogo = true; fallosCatalogo = ""
        catalogo = emptyList()
        pintaLista()
        Thread({
            val todas = ArrayList<eus.argos.core.CamaraPublica>()
            val fallos = java.util.concurrent.ConcurrentHashMap<String, String>()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            fun baja(url: String): String {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "ARGOS/1.0 (Android)")
                c.setRequestProperty("Accept", "application/json")
                return try {
                    if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                    c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } finally { c.disconnect() }
            }
            for (l in eus.argos.core.Catalogo.listas()) pool.execute {
                try {
                    val txt = baja(l.url)
                    // Las que vienen por páginas: el resto, en el mismo hilo.
                    val nuevas = l.lee(txt) + l.mas(txt).flatMap { u -> runCatching { l.lee(baja(u)) }.getOrDefault(emptyList()) }
                    if (nuevas.isEmpty()) throw java.io.IOException("lista vacía")
                    synchronized(todas) {
                        todas += nuevas
                        catalogo = todas.distinctBy { it.id }.sortedWith(compareBy({ it.proveedor }, { it.zona }, { it.nombre }))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ARGOS red: ${l.url} → ${e.javaClass.simpleName}: ${e.message}")
                    fallos.putIfAbsent(l.sitio, when (e) {
                        is java.net.UnknownHostException -> "sin conexión"
                        is java.net.SocketTimeoutException -> "no responde"
                        else -> e.message ?: e.javaClass.simpleName
                    })
                }
                runOnUiThread { pintaLista() }
            }
            pool.shutdown()
            pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES)
            val n = catalogo?.size ?: 0
            Log.i(TAG, "ARGOS red: catálogo de cámaras públicas, $n con vídeo" +
                (if (fallos.isEmpty()) "" else " · fallos: $fallos"))
            fallosCatalogo = fallos.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
            if (n == 0) catalogo = null      // así se puede volver a intentar
            cargandoCatalogo = false
            runOnUiThread { pintaLista() }
            // Sin búsqueda salen primero las de menos retraso: se mide una
            // muestra de cada sitio y de cada distrito.
            catalogo?.let { mide(eus.argos.core.Retraso.muestra(it, 240, System.currentTimeMillis()), false) }
        }, "argos-catalogo").start()
    }

    /*
     * Retraso de cada cámara, medido desde ESTE teléfono: lo que tarda su red
     * en contestar más lo que el vídeo va por detrás de la realidad. Las que
     * no contestan, o dan una lista vacía o un clip viejo, quedan fuera.
     */
    private val retrasos = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val caidas: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val porMedir = java.util.concurrent.LinkedBlockingDeque<eus.argos.core.CamaraPublica>()
    private val pendientes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val midiendo = java.util.concurrent.atomic.AtomicInteger()
    private val medidores = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refrescoPedido = java.util.concurrent.atomic.AtomicBoolean(false)

    /** `primero`: lo que se acaba de buscar pasa delante de la muestra. */
    private fun mide(cams: List<eus.argos.core.CamaraPublica>, primero: Boolean) {
        for (c in (if (primero) cams.asReversed() else cams)) {
            if (retrasos.containsKey(c.id) || c.id in caidas || !pendientes.add(c.id)) continue
            if (primero) porMedir.addFirst(c) else porMedir.addLast(c)
        }
        if (medidores.compareAndSet(false, true)) repeat(6) { k ->
            Thread({
                while (true) {
                    val c = porMedir.take()
                    midiendo.incrementAndGet()
                    try { mideUna(c) } finally { pendientes.remove(c.id); midiendo.decrementAndGet() }
                    refresca()
                    if (porMedir.isEmpty() && midiendo.get() == 0) {
                        val mejor = retrasos.entries.filter { !it.value.isNaN() }.minByOrNull { it.value }
                        Log.i(TAG, "ARGOS red: retraso medido en ${retrasos.size} cámaras, ${caidas.size} caídas" +
                            (mejor?.let { m -> "; la de menos, %.1f s (%s)".format(m.value,
                                catalogo.orEmpty().firstOrNull { it.id == m.key }?.nombre ?: m.key) } ?: ""))
                    }
                }
            }, "argos-mide-$k").apply { isDaemon = true }.start()
        }
    }

    private class Respuesta(val codigo: Int, val texto: String, val ultimaMs: Long, val redS: Double)

    private fun pideHttp(url: String, cabeza: Boolean): Respuesta {
        val t0 = SystemClock.elapsedRealtime()
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "ARGOS/1.0 (Android)")
            if (cabeza) c.requestMethod = "HEAD"
            val codigo = c.responseCode
            val red = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val txt = if (cabeza || codigo != 200) "" else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return Respuesta(codigo, txt, c.lastModified, red)
        } finally { c.disconnect() }
    }

    private fun mideUna(c: eus.argos.core.CamaraPublica) {
        val rt = eus.argos.core.Retraso
        val r: Double? = try {
            if (c.directo) {
                var url = c.video
                var res = pideHttp(url, false)
                var h = if (res.codigo == 200) rt.leeHls(res.texto) else null
                val variante = h?.variante
                if (variante != null) {
                    url = java.net.URI(url).resolve(variante).toString()
                    res = pideHttp(url, false)
                    h = if (res.codigo == 200) rt.leeHls(res.texto) else null
                }
                h?.let { rt.directo(it, System.currentTimeMillis(), res.redS) }
            } else {
                val res = pideHttp(c.video, true)
                if (res.codigo == 200) rt.clip(res.ultimaMs, System.currentTimeMillis(), res.redS) else null
            }
        } catch (e: Exception) { null }
        if (r == null) caidas.add(c.id) else retrasos[c.id] = r
    }

    /** Las medidas llegan de seis en seis: la lista se repinta como mucho dos veces por segundo. */
    private fun refresca() {
        if (!refrescoPedido.compareAndSet(false, true)) return
        window.decorView.postDelayed({ refrescoPedido.set(false); pintaLista() }, 500)
    }

    private var medirBusqueda: Runnable? = null

    private fun pintaLista() {
        val a = listaFuentes ?: return
        val todas = catalogo.orEmpty()
        val encontradas = eus.argos.core.Catalogo.busca(todas, filtro)
        filtradas = eus.argos.core.Retraso.ordena(encontradas, retrasos, caidas)
        a.clear()
        a.addAll(filtradas.take(400).map { c ->
            (if (c.directo) "● " else "○ ") + c.nombre + "\n" + c.proveedor + (if (c.zona.isEmpty()) "" else " · " + c.zona) +
                (retrasos[c.id]?.let { " · " + eus.argos.core.Retraso.texto(it, c.directo) } ?: "")
        })
        a.notifyDataSetChanged()
        val medidas = filtradas.count { retrasos.containsKey(it.id) }
        val enMedida = !porMedir.isEmpty() || midiendo.get() > 0
        estadoFuentes?.text = when {
            todas.isEmpty() && cargandoCatalogo -> "Buscando cámaras públicas…"
            todas.isEmpty() -> "No se pudo bajar la lista" + (if (fallosCatalogo.isEmpty()) "" else " ($fallosCatalogo)") +
                ". Toca aquí para reintentar."
            filtradas.isEmpty() -> "Ninguna con «${filtro.trim()}». Hay cámaras de ${eus.argos.core.Catalogo.SITIOS}: " +
                "prueba una ciudad («Los Angeles», «Minneapolis», «Kansas City»), un estado o «London»."
            else -> (if (filtradas.size > 400) "400 de ${filtradas.size}" else "${filtradas.size}") +
                " cámara${if (filtradas.size == 1) "" else "s"}" +
                (if (medidas > 0) ", primero las de menos retraso" else "") +
                (if (cargandoCatalogo) " · buscando más…" else if (enMedida) " · midiendo…" else "") +
                (if (!cargandoCatalogo && fallosCatalogo.isNotEmpty()) " · sin respuesta de $fallosCatalogo" else "") +
                "   ● directo  ○ clips"
        }
    }

    private fun construyeFuentes(cerrar: () -> Unit): android.widget.LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        val lista = android.widget.ListView(this).apply { divider = null }
        val adapt = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(pos: Int, conv: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                (super.getView(pos, conv, parent) as TextView).apply {
                    val t = getItem(pos).orEmpty(); val k = t.indexOf('\n')
                    text = if (k < 0) t else Ui.textoFila(t.substring(0, k), t.substring(k + 1))
                    setTextColor(Ui.TEXTO); textSize = 15f; minHeight = d(56)
                }
        }
        listaFuentes = adapt
        lista.adapter = adapt
        lateinit var caja: android.widget.LinearLayout
        lateinit var buscar: android.widget.EditText
        val teclado = { getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager }
        val cierra = {
            teclado().hideSoftInputFromWindow(buscar.windowToken, 0)
            buscar.clearFocus(); cerrar()
        }
        lista.setOnItemClickListener { _, _, pos, _ ->
            val c = filtradas.getOrNull(pos) ?: return@setOnItemClickListener
            camara?.cierra(); camara = null; padPtz.visibility = android.view.View.GONE
            usarRed(c.video, c.nombre, !c.directo, c.id); cierra()
        }
        // Al deslizar la lista se baja el teclado para ver más resultados.
        lista.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView?, estado: Int) {
                if (estado == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL)
                    teclado().hideSoftInputFromWindow(buscar.windowToken, 0)
            }
            override fun onScroll(v: android.widget.AbsListView?, a: Int, b: Int, c: Int) {}
        })
        val filaMovil = Ui.fila(this, "Cámara del móvil", "la de este teléfono") { usarMovil(); cierra() }
        val filaIp = Ui.fila(this, "Cámara IP propia", "IP, usuario y contraseña; si se mueve, con mandos") { pideRtsp(cierra) }
        val rotulo = TextView(this).apply {
            text = "Cámaras públicas de tráfico"
            setTextColor(Ui.SUAVE); textSize = 13f; setPadding(d(4), d(14), 0, d(6))
        }
        estadoFuentes = TextView(this).apply {
            setTextColor(Ui.SUAVE); textSize = 13f; setPadding(d(4), d(8), d(4), d(4))
            setOnClickListener { if (catalogo == null && !cargandoCatalogo) cargaCatalogo() }
        }
        /*
         * Escribir: el teclado tapa la mitad de abajo, que es donde estaba la
         * lista, y con pantalla completa Android no encoge la ventana. Mientras
         * se busca, el panel sube arriba y la lista queda bajo el buscador.
         */
        fun modoBusqueda(on: Boolean) {
            filaMovil.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
            filaIp.visibility = filaMovil.visibility; rotulo.visibility = filaMovil.visibility
            (caja.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (on) { lp.height = -1; lp.topMargin = insetArriba + d(6) }
                else { lp.height = (resources.displayMetrics.heightPixels * 0.7).toInt(); lp.topMargin = 0 }
                caja.layoutParams = lp
            }
        }
        buscar = android.widget.EditText(this).apply {
            hint = "Buscar: ciudad, carretera, país…"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUAVE); isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = Ui.fondo(Color.argb(140, 40, 44, 52), d(14).toFloat())
            setPadding(d(14), d(10), d(14), d(10))
            setOnFocusChangeListener { _, foco -> modoBusqueda(foco) }
            setOnEditorActionListener { _, _, _ -> teclado().hideSoftInputFromWindow(windowToken, 0); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    filtro = e?.toString().orEmpty(); pintaLista()
                    // Lo buscado se mide antes que la muestra, cuando se deja de escribir.
                    medirBusqueda?.let { removeCallbacks(it) }
                    medirBusqueda = Runnable {
                        if (filtro.isNotBlank()) mide(eus.argos.core.Catalogo.busca(catalogo.orEmpty(), filtro)
                            .filter { !retrasos.containsKey(it.id) && it.id !in caidas }.take(40), true)
                    }.also { postDelayed(it, 600) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        caja = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(18), d(14), d(18), d(14))
            background = Ui.fondo(Ui.FONDO, d(24).toFloat())
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Fuente de vídeo"; setTextColor(Ui.TEXTO); textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.boton(this@MainActivity, R.drawable.ic_cerrar, "Cerrar", 44, Color.TRANSPARENT) { cierra() })
            })
            addView(filaMovil)
            addView(filaIp)
            addView(rotulo)
            addView(buscar)
            addView(estadoFuentes)
            addView(lista, android.widget.LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d(2) })
        }
        return caja
    }

    /*
     * Cámara IP propia. Basta con su IP, usuario y clave: por ONVIF se le pide
     * la dirección del vídeo (cada marca tiene la suya) y se averigua si se
     * puede mover. Si la cámara no habla ONVIF, se escribe la dirección RTSP a
     * mano. Todo se recuerda solo en este teléfono.
     */
    private var camara: CamaraPtz? = null
    /** La dirección ONVIF que dio la búsqueda en la wifi: se prueba primero. */
    private var dispositivoElegido: String? = null
    private lateinit var padPtz: android.widget.LinearLayout

    private fun pideRtsp(cerrar: () -> Unit) {
        val d = { v: Int -> Ui.dp(this, v) }
        fun campo(pista: String, valor: String?, clave: Boolean = false) = android.widget.EditText(this).apply {
            hint = pista; setText(valor.orEmpty()); isSingleLine = true
            if (clave) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val ip = campo("IP de la cámara (p. ej. 192.168.1.50)", prefs.getString("ip_host", ""))
        val usuario = campo("Usuario de la cámara", prefs.getString("ip_usuario", ""))
        val clave = campo("Contraseña", prefs.getString("ip_clave", ""), true)
        val rtsp = campo("Dirección RTSP (solo si no la encuentra)", prefs.getString("rtsp", ""))
        val caja = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(d(20), d(8), d(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "🔍 Buscar cámaras en esta wifi"; setTextColor(Ui.AZUL); textSize = 15f; setPadding(0, d(6), 0, d(10))
                setOnClickListener {
                    text = "Buscando…"
                    buscaCamaras { cams ->
                        text = "🔍 Buscar cámaras en esta wifi"
                        if (cams.isEmpty()) { lEvento.text = "no ha contestado ninguna cámara ONVIF en esta wifi"; return@buscaCamaras }
                        android.app.AlertDialog.Builder(this@MainActivity).setTitle("Cámaras encontradas")
                            .setItems(cams.map { c -> listOf(c.nombre.ifEmpty { c.modelo }, c.ip).filter { it.isNotEmpty() }.joinToString(" · ") }.toTypedArray()) { _, i ->
                                ip.setText(cams[i].ip); dispositivoElegido = cams[i].dispositivo
                            }.show()
                    }
                }
            })
            addView(ip); addView(usuario); addView(clave)
            addView(TextView(this@MainActivity).apply {
                text = "En Tapo, el usuario y la contraseña son los de «Cuenta de la cámara» (Ajustes avanzados), no los de la app. " +
                    "El móvil tiene que estar en la misma wifi que la cámara, o conectado por VPN."
                textSize = 12f; setPadding(0, d(8), 0, d(8))
            })
            addView(rtsp)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cámara IP")
            .setView(android.widget.ScrollView(this).apply { addView(caja) })
            .setPositiveButton("Conectar") { _, _ ->
                val host = ip.text.toString().trim().removePrefix("http://").removePrefix("rtsp://").substringBefore('/')
                val u = usuario.text.toString().trim(); val c = clave.text.toString()
                val manual = rtsp.text.toString().trim()
                prefs.edit().putString("ip_host", host).putString("ip_usuario", u).putString("ip_clave", c).putString("rtsp", manual).apply()
                conectaIp(host, u, c, manual); cerrar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun conectaIp(host: String, usuario: String, clave: String, manual: String) {
        camara?.cierra(); camara = null; padPtz.visibility = android.view.View.GONE
        // La clave no entra en el identificador de lo aprendido: solo el servidor.
        val id = "ip:" + (host.ifEmpty { manual.substringAfter("@").substringBefore('/') })
        if (manual.startsWith("rtsp://", true) || manual.startsWith("http", true)) usarRed(manual, "cámara IP", false, id)
        if (host.isEmpty()) return
        lEvento.text = "buscando la cámara $host…"
        val cam = CamaraPtz(host, usuario, clave, dispositivoElegido?.takeIf { it.contains("//$host") })
        camara = cam
        cam.conecta { r ->
            runOnUiThread {
                if (camara !== cam) return@runOnUiThread
                when {
                    manual.isNotEmpty() -> {}
                    r.rtsp != null -> usarRed(r.rtsp, "cámara IP", false, id)
                    // Sin ONVIF: la ruta más común (Tapo y muchas otras).
                    else -> usarRed("rtsp://${java.net.URLEncoder.encode(usuario, "UTF-8")}:${java.net.URLEncoder.encode(clave, "UTF-8")}@$host:554/stream1",
                                    "cámara IP", false, id)
                }
                padPtz.visibility = if (r.ptz) android.view.View.VISIBLE else android.view.View.GONE
                lEvento.text = when {
                    r.aviso != null && r.rtsp == null && manual.isEmpty() -> "cámara IP: ${r.aviso}; probando la ruta habitual"
                    r.ptz -> "cámara IP conectada · se puede mover con los mandos"
                    else -> "cámara IP conectada" + (r.aviso?.let { " · $it" } ?: "")
                }
            }
        }
    }

    /**
     * Mandos de la cámara: mientras se mantiene pulsado, se mueve; al soltar,
     * para, y el análisis vuelve a aprender el fondo de lo que ahora se ve.
     */
    private fun construyePad(): android.widget.LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        fun tecla(t: String, desc: String, x: Double, y: Double, z: Double, casa: Boolean = false) = TextView(this).apply {
            text = t; contentDescription = desc; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.TEXTO)
            background = Ui.fondo(Color.argb(150, 20, 24, 30), d(26).toFloat(), Ui.BORDE, this@MainActivity)
            layoutParams = android.widget.LinearLayout.LayoutParams(d(52), d(52)).apply { setMargins(d(3), d(3), d(3), d(3)) }
            setOnTouchListener { v, e ->
                val cam = camara ?: return@setOnTouchListener false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.6f
                        if (casa) cam.aCasa() else cam.mueve(x, y, z)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
                        if (!casa) cam.para()
                        pipeline?.camaraMovida()
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        }
        fun fila(vararg vs: android.view.View) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; vs.forEach { addView(it) }
        }
        fun hueco() = android.view.View(this).apply { layoutParams = android.widget.LinearLayout.LayoutParams(d(58), d(58)) }
        val v = 0.5
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER
            visibility = android.view.View.GONE
            addView(fila(hueco(), tecla("▲", "Subir", 0.0, v, 0.0), tecla("+", "Acercar", 0.0, 0.0, v)))
            addView(fila(tecla("◀", "Izquierda", -v, 0.0, 0.0), tecla("⌂", "Posición inicial", 0.0, 0.0, 0.0, true), tecla("▶", "Derecha", v, 0.0, 0.0)))
            addView(fila(hueco(), tecla("▼", "Bajar", 0.0, -v, 0.0), tecla("−", "Alejar", 0.0, 0.0, -v)))
        }
    }

    /*
     * Zonas, como en los programas de vigilancia de siempre: se tocan las
     * esquinas sobre la imagen. Una zona VIGILADA avisa cuando entra una
     * persona, un vehículo o una bici; una IGNORADA deja de enseñar lo que pasa
     * dentro (un árbol que se mueve, la carretera de fondo).
     */
    private lateinit var barraZonas: android.widget.LinearLayout
    private val puntosZona = ArrayList<DoubleArray>()
    private var zonaVigilar = true
    private lateinit var tipoZona: TextView

    private fun empiezaZonas() {
        puntosZona.clear(); capa.dibujando = emptyList(); capa.dibujandoVigilar = zonaVigilar
        capa.zonas = pipeline?.zonas.orEmpty()
        barraZonas.visibility = android.view.View.VISIBLE
        capa.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                capa.aImagen(e.x, e.y)?.let { puntosZona.add(it); capa.dibujando = puntosZona.toList(); capa.invalidate() }
            }
            true
        }
    }

    private fun acabaZonas() {
        barraZonas.visibility = android.view.View.GONE
        capa.setOnTouchListener(null); capa.isClickable = false
        puntosZona.clear(); capa.dibujando = emptyList(); capa.invalidate()
    }

    private fun construyeBarraZonas(): android.widget.LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        fun boton(t: String, alPulsar: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Ui.TEXTO); textSize = 14f; gravity = Gravity.CENTER
            setPadding(d(12), d(10), d(12), d(10))
            background = Ui.fondo(Color.argb(150, 40, 44, 52), d(16).toFloat(), Ui.BORDE, this@MainActivity)
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins(d(3), d(3), d(3), d(3)) }
            setOnClickListener { alPulsar() }
        }
        tipoZona = boton("Tipo: vigilar") {
            zonaVigilar = !zonaVigilar; capa.dibujandoVigilar = zonaVigilar
            tipoZona.text = if (zonaVigilar) "Tipo: vigilar" else "Tipo: ignorar"; capa.invalidate()
        }
        val guardar = boton("Guardar zona") {
            if (puntosZona.size < 3) { lEvento.text = "una zona necesita al menos 3 esquinas"; return@boton }
            val p = pipeline ?: return@boton
            val n = p.zonas.count { it.vigilar == zonaVigilar } + 1
            val z = eus.argos.core.Zona(if (zonaVigilar) "zona $n" else "ignorar $n", zonaVigilar, puntosZona.toList())
            p.ponZonas(p.zonas + z); capa.zonas = p.zonas
            puntosZona.clear(); capa.dibujando = emptyList(); capa.invalidate()
            lEvento.text = if (z.vigilar) "«${z.nombre}» guardada: avisará cuando entre alguien" else "«${z.nombre}» guardada: lo de dentro no se enseña"
            if (z.vigilar && android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                pideAvisos.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; visibility = android.view.View.GONE
            setPadding(d(14), d(12), d(14), d(12))
            background = Ui.fondo(Ui.FONDO, d(24).toFloat())
            addView(TextView(this@MainActivity).apply {
                text = "Toca la imagen para marcar las esquinas de la zona"; setTextColor(Ui.TEXTO); textSize = 15f
                setPadding(d(4), 0, d(4), d(6))
            })
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                addView(android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    addView(tipoZona)
                    addView(boton("Deshacer") { if (puntosZona.isNotEmpty()) puntosZona.removeAt(puntosZona.size - 1); capa.dibujando = puntosZona.toList(); capa.invalidate() })
                    addView(guardar)
                    addView(boton("Borrar todas") { pipeline?.ponZonas(emptyList()); capa.zonas = emptyList(); capa.invalidate(); lEvento.text = "zonas borradas" })
                    addView(boton("Hecho") { acabaZonas() })
                })
            })
        }
    }

    private var canalAvisos = false
    private fun avisaZona(a: eus.argos.core.Alertas.Aviso) {
        val que = when (a.clase) { "person" -> "Persona"; "vehicle" -> "Vehículo"; "bike" -> "Bici"; else -> a.clase }
        runOnUiThread { lEvento.text = "⚠ $que en «${a.zona}»" }
        val nm = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java) ?: return
        if (!canalAvisos && android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(android.app.NotificationChannel("avisos", "Avisos de zona", android.app.NotificationManager.IMPORTANCE_HIGH))
            canalAvisos = true
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val abre = android.app.PendingIntent.getActivity(this, 0, android.content.Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(this, "avisos")
            .setSmallIcon(R.drawable.ic_argos)
            .setContentTitle("$que en «${a.zona}»")
            .setContentText(fichaFuente.text.toString().substringAfter('\n').ifEmpty { "ARGOS" })
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(abre).setAutoCancel(true)
            .build()
        runCatching { nm.notify(a.zona.hashCode(), n) }
    }

    /*
     * Buscar cámaras en la wifi sin saber su IP (WS-Discovery de ONVIF): una
     * pregunta por multidifusión y cada cámara contesta con su dirección.
     */
    private fun buscaCamaras(alFin: (List<eus.argos.core.Descubre.Camara>) -> Unit) {
        Thread({
            val wifi = ContextCompat.getSystemService(applicationContext, android.net.wifi.WifiManager::class.java)
            val cerrojo = wifi?.createMulticastLock("argos-busca")?.apply { setReferenceCounted(false); runCatching { acquire() } }
            val vistas = LinkedHashMap<String, eus.argos.core.Descubre.Camara>()
            try {
                java.net.DatagramSocket().use { so ->
                    so.soTimeout = 500
                    val q = eus.argos.core.Descubre.pregunta(java.util.UUID.randomUUID().toString()).toByteArray()
                    val grupo = java.net.InetAddress.getByName(eus.argos.core.Descubre.GRUPO)
                    repeat(2) { so.send(java.net.DatagramPacket(q, q.size, grupo, eus.argos.core.Descubre.PUERTO)) }
                    val fin = SystemClock.elapsedRealtime() + 3000
                    val buf = ByteArray(16384)
                    while (SystemClock.elapsedRealtime() < fin) {
                        val p = java.net.DatagramPacket(buf, buf.size)
                        try { so.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                        eus.argos.core.Descubre.lee(String(p.data, 0, p.length, Charsets.UTF_8))?.let { vistas.putIfAbsent(it.ip, it) }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "ARGOS ip: búsqueda falló: ${e.message}") }
            finally { runCatching { cerrojo?.release() } }
            Log.i(TAG, "ARGOS ip: búsqueda en la wifi, ${vistas.size} cámara(s)")
            runOnUiThread { alFin(vistas.values.toList()) }
        }, "argos-busca").start()
    }

    /*
     * El asistente maneja la app: lo que se le PIDE en voz o por escrito
     * (Ordenes) se hace aquí y se contesta qué se ha hecho. Lo que se le
     * PREGUNTA sigue yendo a Ask.
     */
    private var abre: (String?) -> Unit = {}
    private var listaVoz: List<eus.argos.core.CamaraPublica> = emptyList()
    private var idxVoz = 0

    private fun manda(o: Orden): String? {
        return when (o) {
            Orden.Movil -> { usarMovil(); "Pongo la cámara del móvil." }
            Orden.CamaraIp -> {
                val h = prefs.getString("ip_host", "").orEmpty(); val manual = prefs.getString("rtsp", "").orEmpty()
                if (h.isEmpty() && manual.isEmpty()) "No tengo ninguna cámara IP guardada. Añádela en Ajustes, Fuente, Cámara IP."
                else { conectaIp(h, prefs.getString("ip_usuario", "").orEmpty(), prefs.getString("ip_clave", "").orEmpty(), manual); "Conecto con tu cámara IP." }
            }
            is Orden.Publica -> ponPublica(o.lugar, false)
            Orden.OtraCamara -> ponPublica(null, true)
            is Orden.Mueve -> {
                val c = camara
                if (c == null || !c.mueve) "Esta cámara no se puede mover."
                else { c.mueve(o.x, o.y, o.zoom); escena.postDelayed({ c.para(); pipeline?.camaraMovida() }, o.ms); "Hecho." }
            }
            Orden.ACasa -> camara?.takeIf { it.mueve }?.let { it.aCasa(); escena.postDelayed({ pipeline?.camaraMovida() }, 2000); "Vuelvo a la posición inicial." }
                ?: "Esta cámara no se puede mover."
            is Orden.Estabilizar -> {
                if (estabilizar != o.si) fichaEstable.performClick()
                if (o.si) "Estabilizado activado." else "Estabilizado desactivado."
            }
            is Orden.Radio -> {
                if (radio.activa != o.si) alternarRadio()
                when { !o.si -> "Radio apagada."; radio.activa -> "La radio ya está encendida."; else -> "Para encender la radio necesito tu permiso: te lo pido en pantalla." }
            }
            is Orden.Zoom -> {
                val p = pipeline ?: return "Aún estoy cargando el detector."
                when (o.modo) { "auto" -> p.autoZoomOn = true; "entero" -> { p.autoZoomOn = false; p.zoom = 0.0 }; else -> { p.autoZoomOn = false; p.zoom = 2.0 } }
                fichaZoom.text = Ui.textoFila("Zoom", when { p.autoZoomOn -> "auto"; p.zoom == 0.0 -> "cuadro entero"; else -> "teselas ×2" })
                "Zoom " + when (o.modo) { "auto" -> "automático."; "entero" -> "en cuadro entero."; else -> "por teselas ×2." }
            }
            is Orden.Abre -> when (o.que) {
                "memoria" -> { startActivity(android.content.Intent(this, ResumenActivity::class.java)); "Abro la memoria." }
                "zonas" -> { abre(null); empiezaZonas(); "Toca la imagen para marcar las esquinas de la zona." }
                "fuentes" -> { abre("fuentes"); "Aquí tienes las cámaras." }
                else -> { abre("ajustes"); "Abro los ajustes." }
            }
            Orden.BorraZonas -> { pipeline?.ponZonas(emptyList()); capa.zonas = emptyList(); capa.invalidate(); "Zonas borradas." }
            Orden.Cierra -> { abre(null); "Cerrado." }
            Orden.Calla -> null
        }
    }

    /** Una cámara pública por voz: la de menos retraso del sitio pedido, o la siguiente de la última lista. */
    private fun ponPublica(lugar: String?, siguiente: Boolean): String {
        val todas = catalogo.orEmpty()
        if (todas.isEmpty()) { cargaCatalogo(); return "Estoy bajando la lista de cámaras públicas; pídemelo otra vez en unos segundos." }
        if (!siguiente || listaVoz.isEmpty()) {
            val enc = if (lugar == null) todas else eus.argos.core.Catalogo.busca(todas, lugar)
            listaVoz = eus.argos.core.Retraso.ordena(enc, retrasos, caidas); idxVoz = 0
        } else idxVoz++
        if (listaVoz.isEmpty()) return if (lugar != null) "No encuentro cámaras de $lugar. Tengo de ${eus.argos.core.Catalogo.SITIOS}." else "Ahora no hay cámaras públicas disponibles."
        val c = listaVoz[idxVoz % listaVoz.size]
        camara?.cierra(); camara = null; padPtz.visibility = android.view.View.GONE
        usarRed(c.video, c.nombre, !c.directo, c.id)
        // Las siguientes de la lista se miden ya, para que «otra» salte a una viva.
        mide(listaVoz.drop(idxVoz + 1).take(15), true)
        return "Pongo ${c.nombre}, ${c.proveedor}" + (if (c.zona.isNotEmpty()) " (${c.zona})" else "") + "."
    }
}
