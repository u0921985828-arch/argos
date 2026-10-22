package eus.argos.nativo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
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
 * Todo en el teléfono. La voz pasa por el reconocedor de Android EN EL
 * APARATO cuando existe (Android 12+ con el paquete de idioma); si no, por el
 * del sistema pidiéndole que trabaje sin red, y entonces se dice en el panel,
 * porque ese sí puede usar la red del fabricante. Las respuestas salen de
 * `Ask`, que es una gramática sobre la memoria y la escena: no inventa.
 */
class Chat(private val act: ComponentActivity, private val ask: () -> Ask?,
           private val pedirMicro: () -> Unit) {
    val view: LinearLayout
    private val log = TextView(act).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(20, 16, 20, 16) }
    private val scroll = ScrollView(act).apply { addView(log) }
    private val input = EditText(act).apply {
        hint = "Pregunta a ARGOS…"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
    }
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    /** Qué reconocedor hay, dicho tal cual. Un diagnóstico que miente cuesta más que no tenerlo. */
    val reconocimiento: String

    init {
        reconocimiento = when {
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act) -> "en el aparato"
            SpeechRecognizer.isRecognitionAvailable(act) -> "del sistema, pedido sin red (puede usar la del fabricante)"
            else -> "no disponible"
        }
        val mic = ImageButton(act).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Hablar"
            setOnClickListener { escuchar() }
            isEnabled = reconocimiento != "no disponible"
        }
        val enviar = Button(act).apply { text = "Enviar"; setOnClickListener { enviarTexto() } }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviarTexto(); true } else false }
        view = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 10, 12, 16))
            addView(TextView(act).apply {
                text = "ARGOS · voz $reconocimiento · nada sale del teléfono"
                setTextColor(Color.rgb(160, 200, 160)); textSize = 12f; setPadding(20, 12, 20, 0)
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(input, LinearLayout.LayoutParams(0, -2, 1f)); addView(mic); addView(enviar)
            })
        }
        di("argos", "Hola. Pregúntame, por ejemplo, «qué ves», «cuánta gente pasó en la última hora» o «algo raro hoy».")
        tts = TextToSpeech(act) { st ->
            ttsOk = st == TextToSpeech.SUCCESS &&
                (tts?.setLanguage(Locale("es", "ES")) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    private fun di(quien: String, texto: String) {
        act.runOnUiThread {
            log.append((if (log.text.isEmpty()) "" else "\n\n") + (if (quien == "tú") "Tú: " else "ARGOS: ") + texto)
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
        val a = ask()
        val r = a?.ask(q) ?: "Aún estoy cargando el detector; pregúntame en un momento."
        di("argos", r)
        if (porVoz && ttsOk) tts?.speak(r, TextToSpeech.QUEUE_FLUSH, null, "argos")
        return r
    }

    fun escuchar() {
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pedirMicro(); return
        }
        recognizer?.destroy()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(act) else SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) di("argos", "No te he entendido.") else preguntar(t, true)
            }
            override fun onError(error: Int) {
                di("argos", when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído bien. Pulsa el micro y habla."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permiso de micrófono no puedo oírte."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "El reconocedor de este teléfono necesita red. Descarga el paquete de voz en español para usarlo sin conexión."
                    else -> "El reconocimiento de voz falló (código $error)."
                })
            }
            override fun onReadyForSpeech(p: Bundle?) { di("argos", "Te escucho…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        Log.i(TAG, "ARGOS chat: escuchando, reconocimiento $reconocimiento")
    }

    fun close() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }
}
