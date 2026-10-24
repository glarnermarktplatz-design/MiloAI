package ch.milo.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var face: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var keyField: EditText
    private lateinit var profileField: EditText
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsButton: TextView
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
    private val dark = Color.rgb(9, 17, 32)
    private val cyan = Color.rgb(95, 227, 240)
    private val restart = Runnable { startListening() }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(dark)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = text("MILO", 27f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            letterSpacing = 0.13f
        }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(52), 1f))
        settingsButton = text("⚙", 29f).apply {
            contentDescription = "Einstellungen öffnen"
            setOnClickListener { settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(54), dp(52)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("Dein persönlicher KI-Begleiter", 14f).apply { setTextColor(Color.LTGRAY) })
        face = text("●       ●", 76f).apply { setTextColor(cyan) }
        root.addView(face, LinearLayout.LayoutParams(-1, dp(260)))
        status = text("Milo ist bereit.", 18f)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(96)))
        toggle = Button(this).apply {
            text = "MILO STARTEN"
            setOnClickListener { if (enabled) stopConversation() else beginConversation() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(text("Tippe einmal, um freihändig zu sprechen.", 12f).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(18))
        })

        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(16))
            visibility = View.GONE
        }
        settingsPanel.addView(text("EINSTELLUNGEN", 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        settingsPanel.addView(text("API-Key wird verschlüsselt auf diesem Gerät gespeichert.", 12f))
        keyField = EditText(this).apply {
            hint = if (getKey().isNotBlank()) "API-Key gespeichert • neuen Key eingeben" else "OpenAI API-Key (sk-...)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        settingsPanel.addView(keyField, LinearLayout.LayoutParams(-1, -2))
        settingsPanel.addView(Button(this).apply {
            text = "API-KEY SICHER SPEICHERN"
            setOnClickListener {
                val key = keyField.text.toString().trim()
                if (key.isBlank()) { status.text = "Bitte API-Key eingeben."; return@setOnClickListener }
                try {
                    prefs.edit().putString("api_key", encrypt(key)).apply()
                    keyField.text.clear()
                    keyField.hint = "API-Key gespeichert • neuen Key eingeben"
                    status.text = "API-Key sicher gespeichert."
                    settingsPanel.visibility = View.GONE
                } catch (e: Exception) { status.text = "Speichern fehlgeschlagen: ${e.localizedMessage}" }
            }
        })
        settingsPanel.addView(text("Persönliches Profil", 13f))
        profileField = EditText(this).apply {
            setText(prefs.getString("profile", "Ich heisse Claudio. Sprich mit mir auf Deutsch (Schweiz), natürlich und direkt. Du heisst Milo."))
            minLines = 2
            maxLines = 5
            setTextColor(Color.WHITE)
        }
        settingsPanel.addView(profileField, LinearLayout.LayoutParams(-1, -2))
        settingsPanel.addView(Button(this).apply {
            text = "PROFIL SPEICHERN"
            setOnClickListener {
                prefs.edit().putString("profile", profileField.text.toString()).apply()
                status.text = "Profil gespeichert."
                settingsPanel.visibility = View.GONE
            }
        })
        root.addView(settingsPanel, LinearLayout.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply {
            fillViewport = true
            addView(root)
        })
        if (getKey().isBlank()) {
            settingsPanel.visibility = View.VISIBLE
            status.text = "Willkommen! Bitte zuerst API-Key speichern."
        }
        ui.post(object : Runnable {
            var tick = 0
            override fun run() {
                if (!isFinishing) {
                    tick++
                    face.text = when {
                        tick % 17 == 0 -> "—       —"
                        speaking -> "◉       ◉"
                        busy -> "◌       ◌"
                        listening -> "●       ●"
                        else -> "•       •"
                    }
                    ui.postDelayed(this, 250)
                }
            }
        })
    }

    private fun beginConversation() {
        if (getKey().isBlank()) { settingsPanel.visibility = View.VISIBLE; status.text = "Zuerst API-Key speichern."; return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Android-Spracherkennung nicht verfügbar."; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 15); return
        }
        if (!ttsReady) { status.text = "Sprachausgabe wird vorbereitet. Bitte erneut versuchen."; return }
        enabled = true
        toggle.text = "MILO STOPPEN"
        settingsPanel.visibility = View.GONE
        speak("Hallo Claudio! Ich bin Milo. Was beschäftigt dich heute?")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 15 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginConversation()
    }

    private fun stopConversation() {
        enabled = false; listening = false; busy = false; speaking = false
        ui.removeCallbacks(restart)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop()
        if (::toggle.isInitialized) toggle.text = "MILO STARTEN"
        if (::status.isInitialized) status.text = "Milo pausiert."
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
        busy = true
        status.text = "Milo denkt …"
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
                    if (answer.contains("401") || answer.contains("429")) stopConversation()
                    else ui.postDelayed(restart, 1700)
                }
            }
        }.start()
    }

    private fun speak(message: String) {
        speaking = true
        status.text = "Milo spricht …"
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "milo_answer")
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            if (enabled) ui.postDelayed(restart, 1000)
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
