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
    private lateinit var estado: TextView
    private var detalles = false
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

        // Arriba: nombre, estado de un vistazo y las fichas de acción.
        estado = TextView(this).apply { text = "● cargando"; setTextColor(Ui.SUAVE); textSize = 12f }
        fichaZoom = Ui.ficha(this, "Zoom: auto") { cambiarZoom() }
        fichaRadio = Ui.ficha(this, "Radio: no") { alternarRadio() }
        val barra = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(14), d(10), d(10), d(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT))
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = "ARGOS"; setTextColor(Ui.TEXTO); textSize = 20f
                        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
                        letterSpacing = 0.12f
                    })
                    addView(estado)
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.ficha(this@MainActivity, "Memoria") {
                    startActivity(android.content.Intent(this@MainActivity, ResumenActivity::class.java))
                })
            })
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, d(8), 0, 0)
                addView(fichaZoom, android.widget.LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8) })
                addView(fichaRadio)
            })
        }

        // Abajo: la tarjeta con lo que importa. Un toque enseña los detalles técnicos.
        val (cP, vP) = Ui.cifra(this, "personas", Ui.VERDE)
        val (cV, vV) = Ui.cifra(this, "vehículos", Ui.AZUL)
        val (cS, vS) = Ui.cifra(this, "en esta sesión")
        nPersonas = vP; nVehiculos = vV; nSesion = vS
        lEscena = Ui.linea(this); lEvento = Ui.linea(this, 13f, Ui.AMBAR)
        lRadio = Ui.linea(this, 12f, Ui.AZUL).apply { visibility = android.view.View.GONE }
        hud = Ui.linea(this, 11f, Ui.SUAVE).apply { text = "Cargando el detector…" }
        val tarjeta = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(16), d(12), d(16), d(12))
            background = Ui.fondo(Ui.TARJETA, d(20).toFloat(), Ui.BORDE, this@MainActivity)
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(cP, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(cV, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(cS, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(lEscena); addView(lEvento); addView(lRadio); addView(hud)
            setOnClickListener { detalles = !detalles }
        }

        lateinit var chatCaja: FrameLayout
        val botonChat = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Hablar con ARGOS"
            background = Ui.fondo(Color.argb(235, 21, 101, 192), d(30).toFloat())
            elevation = d(6).toFloat()
        }
        chat = Chat(this, { ask }, { askMicro.launch(Manifest.permission.RECORD_AUDIO) },
                    { chatCaja.visibility = android.view.View.GONE; botonChat.visibility = android.view.View.VISIBLE },
                    { voz })
        chatCaja = FrameLayout(this).apply {
            visibility = android.view.View.GONE
            addView(chat.view, FrameLayout.LayoutParams(-1, -1))
        }
        botonChat.setOnClickListener {
            chatCaja.visibility = android.view.View.VISIBLE; botonChat.visibility = android.view.View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // El tema es de pantalla completa: arriba solo hace falta librar el
            // recorte de la cámara frontal, no una barra de estado que no se ve.
            setOnApplyWindowInsetsListener { _, ins ->
                @Suppress("DEPRECATION")
                val arriba = maxOf(ins.systemWindowInsetTop,
                    if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout?.safeInsetTop ?: 0 else 0)
                barra.setPadding(d(14), arriba + d(6), d(10), d(10))
                ins
            }
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(capa, FrameLayout.LayoutParams(-1, -1))
            addView(barra, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(tarjeta, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(10), 0, d(10), d(14)) })
            addView(botonChat, FrameLayout.LayoutParams(d(60), d(60), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, d(20), d(200))
            })
            addView(chatCaja, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6).toInt(), Gravity.BOTTOM).apply {
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

    private fun cambiarZoom() {
        val p = pipeline ?: return
        when {
            p.autoZoomOn -> { p.autoZoomOn = false; p.zoom = 0.0 }
            p.zoom == 0.0 -> p.zoom = 2.0
            else -> { p.autoZoomOn = true }
        }
        fichaZoom.text = "Zoom: " + when { p.autoZoomOn -> "auto"; p.zoom == 0.0 -> "cuadro entero"; else -> "teselas ×2" }
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
            fichaRadio.text = "Radio: no"; lRadio.visibility = android.view.View.GONE
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
            fichaRadio.text = "Radio: no"
            lRadio.text = "radio apagada · ${problema ?: "no arrancó"}"
            return
        }
        prefs.edit().putBoolean("radio", true).apply()
        fichaRadio.text = "Radio: sí"
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
            hud.text = if (detalles) tecnico else "toca la tarjeta para ver detalles técnicos"
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
}
