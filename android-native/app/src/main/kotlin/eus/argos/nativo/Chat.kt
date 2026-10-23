package eus.argos.nativo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import eus.argos.core.Ask
import java.util.Locale

/**
 * El chat con ARGOS: se escribe o se habla, y responde por escrito y, si se
 * le habló, en voz alta.
 *
 * Todo en el teléfono. La voz pasa primero por el reconocedor de Android EN EL
 * APARATO. Si ese falla de una forma que solo indica que no está listo
 * —código 11, «servidor desconectado», visto en el teléfono; o idioma sin
 * descargar— se reintenta solo con el del sistema, sin hacer repetir la frase
 * a nadie, y se queda con ese el resto de la sesión. El panel dice cuál usa.
 * Las respuestas salen de `Ask`, una gramática sobre la memoria y la escena:
 * no inventa.
 */
class Chat(private val act: ComponentActivity, private val ask: () -> Ask?,
           private val pedirMicro: () -> Unit, private val cerrar: () -> Unit,
           private val vozPropia: () -> VozApp? = { null },
           /** Lo que se PIDE (cambiar de cámara, moverla, encender cosas): lo hace la app y dice qué ha hecho. */
           private val mando: (eus.argos.core.Orden) -> String? = { null }) {
    val view: LinearLayout
    private val burbujas = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4)) }
    private val scroll = ScrollView(act).apply { addView(burbujas) }
    private val input = EditText(act).apply {
        hint = "Pregunta a ARGOS…"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUAVE); textSize = 15f
        imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
        background = Ui.fondo(Color.argb(160, 40, 44, 52), Ui.dp(act, 20).toFloat())
        setPadding(Ui.dp(act, 14), Ui.dp(act, 9), Ui.dp(act, 14), Ui.dp(act, 9))
    }
    private val mic = ImageButton(act)
    private val estadoVoz = TextView(act).apply { setTextColor(Ui.SUAVE); textSize = 11f }
    private val prefs = act.getSharedPreferences("argos", android.content.Context.MODE_PRIVATE)
    /** Ficha para elegir el tratamiento de la voz propia; solo se ve si la hay. */
    private val fichaEfecto = Ui.ficha(act, "Voz: natural") { cambiarEfecto() }.apply { visibility = android.view.View.GONE }
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var escuchando = false
    /** El reconocedor del aparato falló por no estar listo: el resto de la sesión, el del sistema. */
    private var usarSistema = false
    private val idioma: String = Locale.getDefault().let { if (it.language == "es") it.toLanguageTag() else "es-ES" }
    var voz = "voz del sistema"; private set

    /** Qué reconocedor hay, dicho tal cual. Un diagnóstico que miente cuesta más que no tenerlo. */
    val reconocimiento: String get() = when {
        !SpeechRecognizer.isRecognitionAvailable(act) && !enAparato() -> "no disponible"
        enAparato() && !usarSistema -> "en el aparato"
        else -> "del sistema (puede usar la red del fabricante)"
    }

    private fun enAparato() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)

    init {
        mic.apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Hablar"
            background = Ui.fondo(Color.argb(200, 46, 125, 50), Ui.dp(act, 24).toFloat())
            setOnClickListener { if (escuchando) parar() else escuchar() }
            isEnabled = reconocimiento != "no disponible"
        }
        val enviar = ImageButton(act).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            contentDescription = "Enviar"
            background = Ui.fondo(Color.argb(160, 40, 44, 52), Ui.dp(act, 24).toFloat())
            setOnClickListener { enviarTexto() }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviarTexto(); true } else false }
        val cab = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 4))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(act).apply { text = "Hablar con ARGOS"; setTextColor(Ui.TEXTO); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
                addView(estadoVoz)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(fichaEfecto)
            addView(TextView(act).apply {
                text = "✕"; setTextColor(Ui.SUAVE); textSize = 20f
                setPadding(Ui.dp(act, 14), Ui.dp(act, 4), Ui.dp(act, 14), Ui.dp(act, 4))
                setOnClickListener { cerrar() }
            })
        }
        val fila = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 10))
            val tam = Ui.dp(act, 46)
            addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dp(act, 8) })
            addView(mic, LinearLayout.LayoutParams(tam, tam).apply { rightMargin = Ui.dp(act, 6) })
            addView(enviar, LinearLayout.LayoutParams(tam, tam))
        }
        view = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.fondo(Ui.FONDO, Ui.dp(act, 22).toFloat())
            addView(cab)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = Ui.dp(act, 10); rightMargin = Ui.dp(act, 10) })
            addView(fila)
        }
        di("argos", "Hola. Pregúntame «qué ves», «cuánta gente pasó hoy» o «algo raro», o di «ayuda» para ver todo lo que sé.")
        tts = TextToSpeech(act) { st ->
            ttsOk = st == TextToSpeech.SUCCESS && elegirVoz()
            pintaEstado()
        }
        pintaEstado()
    }

    /**
     * La mejor voz española que hay EN EL TELÉFONO: sin red, instalada, de la
     * variante del usuario si la hay, y de más calidad. La que da el sistema
     * por defecto suele ser la más básica.
     */
    private fun elegirVoz(): Boolean {
        val t = tts ?: return false
        if (t.setLanguage(Locale.forLanguageTag(idioma)) < TextToSpeech.LANG_AVAILABLE &&
            t.setLanguage(Locale("es", "ES")) < TextToSpeech.LANG_AVAILABLE) return false
        val pais = Locale.forLanguageTag(idioma).country
        val mejor = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "es" && !it.isNetworkConnectionRequired &&
                      !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == pais }
                .thenByDescending { it.quality }.thenBy { it.latency })
            .firstOrNull()
        if (mejor != null) {
            t.voice = mejor
            voz = "voz ${mejor.locale.toLanguageTag()}" + (if (mejor.quality >= android.speech.tts.Voice.QUALITY_HIGH) " de alta calidad" else "")
        }
        t.setSpeechRate(1.02f)
        return true
    }

    private fun pintaEstado() = act.runOnUiThread {
        val propia = vozPropia()
        estadoVoz.text = (if (escuchando) "escuchando… " else "") + "reconocimiento $reconocimiento · " +
            (if (propia != null) "voz de ARGOS (${propia.nombre})" else if (ttsOk) voz else "sin voz") + (if (reconocimiento == "en el aparato") " · nada sale del teléfono" else "")
        mic.background = Ui.fondo(if (escuchando) Color.argb(230, 198, 40, 40) else Color.argb(200, 46, 125, 50), Ui.dp(act, 24).toFloat())
    }

    private fun di(quien: String, texto: String) {
        act.runOnUiThread {
            val yo = quien == "tú"
            val b = TextView(act).apply {
                text = texto; textSize = 15f; setTextColor(Ui.TEXTO)
                setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
                background = Ui.fondo(if (yo) Color.argb(220, 21, 101, 192) else Color.argb(220, 38, 42, 50), Ui.dp(act, 16).toFloat())
            }
            burbujas.addView(b, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = if (yo) Gravity.END else Gravity.START
                topMargin = Ui.dp(act, 6)
                if (yo) leftMargin = Ui.dp(act, 48) else rightMargin = Ui.dp(act, 48)
            })
            while (burbujas.childCount > 80) burbujas.removeViewAt(0)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun enviarTexto() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        preguntar(q, false)
    }

    /** Pregunta y respuesta; en voz alta si la pregunta llegó hablada. */
    fun preguntar(q: String, porVoz: Boolean): String {
        di("tú", q)
        val orden = eus.argos.core.Ordenes.lee(q)
        if (orden == eus.argos.core.Orden.Calla) { tts?.stop(); vozPropia()?.para(); return "" }
        val r = orden?.let { mando(it) } ?: run {
            val a = ask()
            var x = a?.ask(q) ?: "Aún estoy cargando el detector; pregúntame en un momento."
            // A quien pregunta qué sabe hacer, también lo que puede mandar.
            if (Regex("\\b(que (puedes|sabes) hacer|ayuda|como funcionas|para que sirves|comandos|ordenes|que te puedo (decir|pedir))\\b")
                    .containsMatchIn(eus.argos.core.Ask.norm(q)))
                x += " Y manejo la app: " + eus.argos.core.Ordenes.EJEMPLOS.joinToString(", ") { "«$it»" } + "."
            x
        }
        di("argos", r)
        if (porVoz) {
            val propia = vozPropia()
            if (propia != null) propia.habla(r)
            else if (ttsOk) tts?.speak(r, TextToSpeech.QUEUE_FLUSH, null, "argos")
        }
        return r
    }

    private fun parar() {
        recognizer?.stopListening()
        escuchando = false; pintaEstado()
    }

    fun escuchar() = escuchar(reintento = false)

    private fun escuchar(reintento: Boolean) {
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pedirMicro(); return
        }
        tts?.stop(); vozPropia()?.para()
        recognizer?.destroy()
        val delAparato = enAparato() && !usarSistema
        val r = if (delAparato && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(act)
                else SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                escuchando = false; pintaEstado()
                act.runOnUiThread { input.hint = "Pregunta a ARGOS…" }
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) di("argos", "No te he entendido. Pulsa el micro y vuelve a probar.") else preguntar(t, true)
            }
            override fun onError(error: Int) {
                escuchando = false
                act.runOnUiThread { input.hint = "Pregunta a ARGOS…" }
                // 11 = servidor desconectado, 12 = idioma no admitido, 13 = idioma
                // sin descargar: el reconocedor del aparato no está listo. No es
                // culpa de quien habla: se pasa al del sistema, y el panel lo
                // dice, porque ese puede usar la red del fabricante.
                if (delAparato && !reintento && error in setOf(11, 12, 13)) {
                    Log.i(TAG, "ARGOS chat: reconocedor del aparato falló ($error), paso al del sistema")
                    usarSistema = true
                    act.runOnUiThread { escuchar(reintento = true) }
                    return
                }
                pintaEstado()
                di("argos", when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído bien. Pulsa el micro y habla."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permiso de micrófono no puedo oírte."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "El reconocedor de este teléfono necesita red. En Ajustes → Sistema → Idiomas → Reconocimiento de voz, descarga el español para usarlo sin conexión."
                    12, 13 -> "Falta el paquete de voz en español. Descárgalo en Ajustes → Sistema → Idiomas → Reconocimiento de voz."
                    SpeechRecognizer.ERROR_CLIENT -> return   // se paró a mano
                    else -> "El reconocimiento de voz falló (código $error). Puedes escribir la pregunta."
                })
            }
            override fun onReadyForSpeech(p: Bundle?) { escuchando = true; pintaEstado() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { escuchando = false; pintaEstado() }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { p ->
                    act.runOnUiThread { input.hint = "$p…" }
                }
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        input.hint = "Pregunta a ARGOS…"
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        Log.i(TAG, "ARGOS chat: escuchando, reconocimiento $reconocimiento, idioma $idioma")
    }

    /** La voz propia terminó de cargar: se le pone el efecto guardado y el panel lo dice. */
    fun vozLista() {
        val v = vozPropia() ?: return pintaEstado()
        v.efecto = runCatching { eus.argos.core.EfectoVoz.Tipo.valueOf(prefs.getString("efecto", "NATURAL")!!) }
            .getOrDefault(eus.argos.core.EfectoVoz.Tipo.NATURAL)
        act.runOnUiThread { fichaEfecto.visibility = android.view.View.VISIBLE; fichaEfecto.text = "Voz: ${v.efecto.texto}" }
        pintaEstado()
    }

    /** Natural ↔ cálida, y lo dice con el acabado nuevo. */
    private fun cambiarEfecto() {
        val v = vozPropia() ?: return
        val tipos = eus.argos.core.EfectoVoz.Tipo.values()
        v.efecto = tipos[(v.efecto.ordinal + 1) % tipos.size]
        prefs.edit().putString("efecto", v.efecto.name).apply()
        fichaEfecto.text = "Voz: ${v.efecto.texto}"
        v.habla("Así sueno ahora.")
    }

    fun close() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }
}
