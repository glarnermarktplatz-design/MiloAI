package ch.milo.ai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var face: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var keyField: EditText
    private lateinit var profileField: EditText
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var enabled = false
    private var listening = false
    private var busy = false
    private var speaking = false
    private var ttsReady = false
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private val prefs by lazy { getSharedPreferences("milo", MODE_PRIVATE) }
    private val dark = Color.rgb(11, 19, 34)
    private val cyan = Color.rgb(95, 227, 240)
    private val restart = Runnable { startListening() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(35, 35, 35, 35)
            setBackgroundColor(dark)
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        root.addView(text("MILO AI", 26f).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(text("Dein persönlicher KI-Begleiter", 13f))
        face = text("●     ●", 72f).apply { setTextColor(cyan) }
        root.addView(face, LinearLayout.LayoutParams(-1, 185))
        status = text("Bereit. Hinterlege deinen API-Key und starte Milo.", 16f)
        root.addView(status)
        root.addView(text("", 10f))
        keyField = EditText(this).apply {
            hint = "OpenAI API-Key (sk-...)"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        }
        root.addView(keyField, LinearLayout.LayoutParams(-1, -2))
        val saveKey = Button(this).apply { text = "API-Key sicher speichern"; setOnClickListener {
            val key = keyField.text.toString().trim()
            if (key.isBlank()) { status.text = "Bitte API-Key eingeben."; return@setOnClickListener }
            try { prefs.edit().putString("api_key", encrypt(key)).apply(); keyField.text.clear(); status.text = "API-Key verschlüsselt gespeichert." }
            catch (e: Exception) { status.text = "Speichern fehlgeschlagen: ${e.localizedMessage}" }
        } }
        root.addView(saveKey, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("Mein Profil (wird bei Gesprächen an die KI übermittelt)", 12f))
        profileField = EditText(this).apply {
            setText(prefs.getString("profile", "Ich heisse Claudio. Sprich mit mir auf Deutsch (Schweiz), natürlich und direkt. Du heisst Milo."))
            minLines = 2; maxLines = 4; setTextColor(Color.WHITE)
        }
        root.addView(profileField, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply { text = "Profil speichern"; setOnClickListener {
            prefs.edit().putString("profile", profileField.text.toString()).apply()
            status.text = "Profil gespeichert."
        } }, LinearLayout.LayoutParams(-1, -2))
        toggle = Button(this).apply { text = "Milo starten · Freihändig"; setOnClickListener { if (enabled) stopConversation() else beginConversation() } }
        root.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("Zum Starten einmal tippen. Milo hört danach automatisch wieder zu, sobald er fertig gesprochen hat. Kopfhörer reduzieren Echo.", 12f))
        root.addView(text("V1: Sprache + KI. Keine Kamera, keine Gesichtserkennung, keine ChatGPT-Kontosynchronisation. Mikrofon nur während aktivem Gespräch.", 11f))
        setContentView(ScrollView(this).apply { addView(root) })
        ui.post(object : Runnable {
            var tick = 0
            override fun run() {
                if (!isFinishing) {
                    tick++
                    face.text = if (tick % 13 == 0) "—     —" else if (speaking) "◉     ◉" else if (listening) "●     ●" else "•     •"
                    ui.postDelayed(this, 350)
                }
            }
        })
    }

    private fun beginConversation() {
        if (getKey().isBlank()) { status.text = "Zuerst API-Key speichern."; return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Android-Spracherkennung nicht verfügbar."; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 15); return
        }
        if (!ttsReady) { status.text = "Sprachausgabe wird noch vorbereitet. Bitte gleich erneut versuchen."; return }
        enabled = true; toggle.text = "Milo stoppen"
        speak("Hallo Claudio! Ich bin Milo. Was beschäftigt dich heute?")
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 15 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginConversation()
    }
    private fun stopConversation() {
        enabled = false; listening = false; busy = false; speaking = false
        ui.removeCallbacks(restart); recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop(); toggle.text = "Milo starten · Freihändig"; status.text = "Milo pausiert."
    }
    private fun startListening() {
        if (!enabled || speaking || busy || listening || isFinishing) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "Ich höre dir zu …" }
            override fun onBeginningOfSpeech() { status.text = "Du sprichst …" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "Einen Moment …" }
            override fun onError(error: Int) {
                listening = false
                if (enabled && !busy && !speaking) ui.postDelayed(restart, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1600 else 850)
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (words.isNotBlank()) askAI(words) else if (enabled) ui.postDelayed(restart, 600)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-CH")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try { listening = true; recognizer?.startListening(intent) }
        catch (e: Exception) { listening = false; status.text = "Mikrofonfehler: ${e.localizedMessage}" }
    }
    private fun askAI(words: String) {
        busy = true; status.text = "Du: $words\nMilo denkt …"
        val key = getKey()
        val profile = prefs.getString("profile", "Ich heisse Claudio.").orEmpty()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Du bist Milo, ein freundlicher persönlicher KI-Begleiter. Antworte auf Deutsch (Schweiz), natürlich, prägnant und mündlich gut verständlich. Nutzerprofil: $profile"))
        for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
        messages.put(JSONObject().put("role", "user").put("content", words))
        Thread {
            var answer: String
            var success = false
            try {
                val connection = (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20000; readTimeout = 45000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject().put("model", "gpt-4o-mini").put("messages", messages).put("max_tokens", 300)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                if (code !in 200..299) throw Exception("API-Fehler $code: " + JSONObject(response).optJSONObject("error")?.optString("message", "Unbekannt"))
                answer = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                success = true
                connection.disconnect()
            } catch (e: Exception) { answer = "Verbindung fehlgeschlagen: ${e.localizedMessage}" }
            val ok = success
            ui.post {
                if (!enabled) return@post
                busy = false
                if (ok) {
                    history.put(JSONObject().put("role", "user").put("content", words))
                    history.put(JSONObject().put("role", "assistant").put("content", answer))
                    while (history.length() > 12) history.remove(0)
                    speak(answer)
                } else {
                    status.text = answer
                    // Pause on authentication/quota errors to avoid repeated requests.
                    if (answer.contains("401") || answer.contains("429")) stopConversation()
                    else ui.postDelayed(restart, 1700)
                }
            }
        }.start()
    }
    private fun speak(message: String) {
        speaking = true; status.text = "Milo: $message"
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "milo_answer")
        if (result != TextToSpeech.SUCCESS) {
            speaking = false; if (enabled) ui.postDelayed(restart, 1000)
        }
    }
    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.forLanguageTag("de-CH")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { ui.post { speaking = false; if (enabled) ui.postDelayed(restart, 550) } }
                @Deprecated("Deprecated Android callback")
                override fun onError(utteranceId: String?) { ui.post { speaking = false; if (enabled) ui.postDelayed(restart, 550) } }
            })
        }
    }
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("milo_key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("milo_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun getKey(): String = try {
        val bytes = Base64.decode(prefs.getString("api_key", ""), Base64.NO_WRAP)
        if (bytes.size < 13) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) { "" }
    override fun onDestroy() { stopConversation(); tts?.shutdown(); super.onDestroy() }
}
