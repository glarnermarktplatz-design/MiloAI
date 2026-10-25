package ch.milo.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.animation.DecelerateInterpolator
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
    private lateinit var face: MiloFaceView
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
    private var requestGeneration = 0
    private var paused = false
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private lateinit var transcriptPanel: LinearLayout
    private lateinit var transcriptText: TextView
    private var transcriptVisible = false
    private val archiveKey = "conversation_archive_v21"
    private val defaultProfile = """Du heisst Milo. Antworte natürlich, direkt und auf Deutsch (Schweiz).
Beruflicher Kontext: Der Nutzer arbeitet bei Sunrise im Schweizer B2B-Partnergeschäft als Partner Key Account Manager und Deputy Head of Indirect Sales. Er akquiriert, qualifiziert, onboardet und betreut ICT- und Telekommunikationspartner. Zu seinen typischen Aufgaben gehören Partnerprogramm und Vertragsprozesse, Partnerkommunikation, Salesforce-Pipeline, DocuSign, Onboarding, Partnerzugänge, Geschäftskundenlösungen und interne Abstimmungen. Er bevorzugt konkrete, professionelle, kurze E-Mails, klare Zusammenfassungen und umsetzbare nächste Schritte. Bei Sunrise-Fragen sollst du diese Rolle berücksichtigen, aber keine vertraulichen Zahlen, Kundendaten, Vertragsbedingungen oder internen Richtlinien erfinden. Frage nach fehlenden Fakten, wenn sie entscheidend sind. Verwende für aktuelle Informationen die Websuche und kennzeichne Unsicherheit."""
    private fun loadArchive() {
        try {
            val saved = JSONArray(prefs.getString(archiveKey, "[]"))
            for (i in 0 until saved.length()) history.put(saved.getJSONObject(i))
        } catch (_: Exception) { /* Invalid archive: start empty */ }
    }
    private fun saveArchive() { prefs.edit().putString(archiveKey, history.toString()).apply() }
    private fun transcript(): String {
        val lines = mutableListOf<String>()
        for (i in 0 until history.length()) {
            val msg = history.optJSONObject(i) ?: continue
            val who = if (msg.optString("role") == "user") "Du" else "Milo"
            lines.add("$who: ${msg.optString("content")}")
        }
        return lines.joinToString("\n\n")
    }
    private fun refreshTranscript() {
        if (::transcriptText.isInitialized) transcriptText.text = transcript().ifBlank { "Noch keine Gespräche gespeichert." }
    }
    private fun copyText(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Milo Gespräch", value))
        Toast.makeText(this, "Text kopiert", Toast.LENGTH_SHORT).show()
    }
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
        // Seed local profile once; never publish personal data in source-control history.
        if (!prefs.contains("profile")) prefs.edit().putString("profile", defaultProfile).apply()
        loadArchive()
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
        root.addView(text("DEIN KI-BEGLEITER  •  ONLINE", 12f).apply { setTextColor(cyan); letterSpacing = 0.15f })
        face = MiloFaceView(this)
        root.addView(face, LinearLayout.LayoutParams(-1, dp(290)))
        status = text("Milo ist bereit.", 18f)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(96)))
        toggle = Button(this).apply {
            text = "MILO STARTEN"
            setOnClickListener { if (enabled) stopConversation() else beginConversation() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(-1, dp(60)))
        val interrupt = Button(this).apply {
            text = "ANTWORT STOPPEN  /  MIKROFON PAUSIEREN"
            setOnClickListener {
                when {
                    speaking -> {
                        tts?.stop()
                        speaking = false
                        ui.removeCallbacks(restart)
                        status.text = "Unterbrochen. Du kannst jetzt sprechen."
                        if (enabled) ui.postDelayed(restart, 350)
                    }
                    busy -> {
                        requestGeneration++ // ignore response from in-flight request
                        busy = false
                        status.text = "Recherche abgebrochen. Ich höre wieder zu."
                        if (enabled) ui.postDelayed(restart, 350)
                    }
                    enabled -> {
                        paused = !paused
                        if (paused) {
                            ui.removeCallbacks(restart)
                            recognizer?.cancel()
                            listening = false
                            status.text = "Zuhören pausiert. Nochmals tippen zum Fortsetzen."
                        } else {
                            status.text = "Ich höre wieder zu …"
                            ui.postDelayed(restart, 350)
                        }
                    }
                    else -> status.text = "Starte Milo zuerst."
                }
            }
        }
        root.addView(interrupt, LinearLayout.LayoutParams(-1, dp(55)))
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
        settingsPanel.addView(text("Berufliches Sunrise-Profil (nur lokal gespeichert)", 13f))
        profileField = EditText(this).apply {
            setText(prefs.getString("profile", defaultProfile))
            minLines = 2
            maxLines = 5
            setTextColor(Color.WHITE)
        }
        settingsPanel.addView(profileField, LinearLayout.LayoutParams(-1, -2))
        settingsPanel.addView(Button(this).apply {
            text = "SUNRISE-GRUNDWISSEN EINFÜGEN"
            setOnClickListener {
                profileField.setText(defaultProfile)
                status.text = "Sunrise-Grundwissen eingefügt. Bitte PROFIL SPEICHERN tippen."
            }
        })
        settingsPanel.addView(Button(this).apply {
            text = "PROFIL SPEICHERN"
            setOnClickListener {
                prefs.edit().putString("profile", profileField.text.toString()).apply()
                status.text = "Profil gespeichert."
                settingsPanel.visibility = View.GONE
            }
        })
        root.addView(settingsPanel, LinearLayout.LayoutParams(-1, -2))
        val transcriptToggle = Button(this).apply {
            text = "GESPRÄCHSVERLAUF ANZEIGEN"
            setOnClickListener {
                transcriptVisible = !transcriptVisible
                transcriptPanel.visibility = if (transcriptVisible) View.VISIBLE else View.GONE
                text = if (transcriptVisible) "GESPRÄCHSVERLAUF SCHLIESSEN" else "GESPRÄCHSVERLAUF ANZEIGEN"
                refreshTranscript()
            }
        }
        root.addView(transcriptToggle, LinearLayout.LayoutParams(-1, dp(54)))
        transcriptPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(12), dp(4), dp(16))
        }
        transcriptText = text("", 15f).apply {
            gravity = Gravity.START
            setTextIsSelectable(true) // Long press selects and copies individual messages/text
        }
        transcriptPanel.addView(transcriptText, LinearLayout.LayoutParams(-1, -2))
        transcriptPanel.addView(Button(this).apply {
            text = "GANZES GESPRÄCH KOPIEREN"
            setOnClickListener { copyText(transcript()) }
        })
        transcriptPanel.addView(Button(this).apply {
            text = "NEUES GESPRÄCH"
            setOnClickListener {
                stopConversation()
                historyClear()
                saveArchive()
                refreshTranscript()
                status.text = "Neues Gespräch bereit. Bisheriger Verlauf wurde gelöscht."
            }
        })
        root.addView(transcriptPanel, LinearLayout.LayoutParams(-1, -2))
        refreshTranscript()
        setContentView(ScrollView(this).apply {
            isFillViewport = true
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
                    face.mode = when {
                        speaking -> 3
                        busy -> 2
                        listening -> 1
                        else -> 0
                    }
                    face.frame = tick
                    face.invalidate()
                    ui.postDelayed(this, 250)
                }
            }
        })
    }

    private fun historyClear() {
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    private fun beginConversation() {
        if (getKey().isBlank()) { settingsPanel.visibility = View.VISIBLE; status.text = "Zuerst API-Key speichern."; return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Android-Spracherkennung nicht verfügbar."; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 15); return
        }
        if (!ttsReady) { status.text = "Sprachausgabe wird vorbereitet. Bitte erneut versuchen."; return }
        enabled = true
        paused = false
        toggle.text = "MILO STOPPEN"
        settingsPanel.visibility = View.GONE
        speak("Hallo! Ich bin Milo. Was beschäftigt dich heute?")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 15 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginConversation()
    }

    private fun stopConversation() {
        enabled = false; listening = false; busy = false; speaking = false
        paused = false
        requestGeneration++
        ui.removeCallbacks(restart)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop()
        if (::toggle.isInitialized) toggle.text = "MILO STARTEN"
        if (::status.isInitialized) status.text = "Milo pausiert."
    }

    private fun startListening() {
        if (!enabled || paused || speaking || busy || listening || isFinishing) return
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
                if (enabled && !paused && !busy && !speaking) ui.postDelayed(restart, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1600 else 850)
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
        ui.removeCallbacks(restart)
        recognizer?.cancel()
        listening = false
        status.text = "Milo recherchiert und denkt …"
        val generation = ++requestGeneration
        val key = getKey()
        val profile = prefs.getString("profile", defaultProfile).orEmpty()
        val instructions = """Du bist Milo, ein persönlicher KI-Assistent. Sprich Deutsch (Schweiz), natürlich und direkt.
Du hast Zugriff auf die Websuche. Bei aktuellen Fakten, konkreten Firmen, Ersatzteilen, Preisen, Angeboten und Quellen recherchiere selbst, statt nur Suchanleitungen zu geben. Behaupte keine Recherche, wenn keine stattgefunden hat. Gib konkrete Resultate, sofern verifizierbar. Antworte standardmässig in 2 bis 3 kurzen Sätzen und maximal ungefähr 65 Wörtern. Beginne mit der konkreten Antwort, ohne Einleitung oder Wiederholung der Frage. Bei Produktsuche nenne zuerst Teilenummer, Anbieter und Preis, falls verifiziert. Wenn der Nutzer ausdrücklich Details verlangt, antworte ausführlicher. Quellen-URLs können im Chatverlauf stehen.
Persönlicher Kontext (vom Nutzer lokal verwaltet):
$profile"""
        val input = JSONArray()
        val from = maxOf(0, history.length() - 12)
        for (i in from until history.length()) {
            val old = history.optJSONObject(i) ?: continue
            input.put(JSONObject().put("role", old.optString("role")).put("content", old.optString("content")))
        }
        input.put(JSONObject().put("role", "user").put("content", words))
        Thread {
            var answer = ""
            var success = false
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 90000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject()
                    .put("model", "gpt-4.1-mini")
                    .put("instructions", instructions)
                    .put("input", input)
                    .put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))
                    .put("max_output_tokens", 450)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    throw Exception("API $code: ${detail ?: response.take(180)}")
                }
                val output = JSONObject(response).optJSONArray("output") ?: JSONArray()
                val parts = mutableListOf<String>()
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    if (item.optString("type") != "message") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") parts.add(part.optString("text"))
                    }
                }
                answer = parts.joinToString("\n").trim()
                if (answer.isBlank()) throw Exception("Keine Textantwort erhalten.")
                success = true
            } catch (e: Exception) {
                answer = "Recherche fehlgeschlagen: ${e.localizedMessage}"
            } finally { connection?.disconnect() }
            val ok = success
            ui.post {
                if (!enabled || generation != requestGeneration) return@post
                busy = false
                if (ok) {
                    history.put(JSONObject().put("role", "user").put("content", words))
                    history.put(JSONObject().put("role", "assistant").put("content", answer))
                    saveArchive()
                    refreshTranscript()
                    // Keep the spoken answer shorter; full text remains available in transcript.
                    val spoken = answer.replace(Regex("https?://\\S+"), "Link im Gesprächsverlauf")
                    speak(spoken.take(650))
                } else {
                    status.text = answer
                    if (answer.contains("API 401") || answer.contains("API 429")) stopConversation()
                    else if (!paused) ui.postDelayed(restart, 1700)
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
            if (enabled && !paused) ui.postDelayed(restart, 1000)
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.forLanguageTag("de-CH")
            tts?.setSpeechRate(1.18f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { ui.post { speaking = false; if (enabled && !paused) ui.postDelayed(restart, 550) } }
                @Deprecated("Deprecated Android callback")
                override fun onError(utteranceId: String?) { ui.post { speaking = false; if (enabled && !paused) ui.postDelayed(restart, 550) } }
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


/** Animated, entirely vector-drawn face. No image files, dependencies or permissions. */
private class MiloFaceView(context: Context) : View(context) {
    var mode: Int = 0 // 0 idle, 1 listening, 2 thinking, 3 speaking
    var frame: Int = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Color.rgb(93, 238, 244)
    private val navy = Color.rgb(9, 17, 32)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h * 0.48f
        val r = minOf(w * 0.39f, h * 0.46f)
        val time = frame * 0.25f
        val pulse = (kotlin.math.sin(time * 0.9f) * 0.5f + 0.5f).toFloat()

        // Atmospheric halo and circular robot silhouette
        p.shader = RadialGradient(cx, cy, r * 1.35f,
            intArrayOf(Color.argb(75, 23, 191, 213), Color.argb(26, 22, 88, 139), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.35f, p)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = Color.rgb(13, 30, 52)
        canvas.drawCircle(cx, cy, r * 0.93f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.018f
        p.color = Color.argb(155, 57, 183, 211)
        canvas.drawCircle(cx, cy, r * 0.95f, p)
        p.color = Color.argb(90, 56, 122, 172)
        p.strokeWidth = r * 0.007f
        canvas.drawCircle(cx, cy, r * 1.06f + pulse * r * 0.018f, p)
        p.style = Paint.Style.FILL

        // Small orbiting light nodes
        for (i in 0 until 5) {
            val a = i * (Math.PI * 2 / 5) + time * 0.12
            val x = cx + kotlin.math.cos(a).toFloat() * r * 1.055f
            val y = cy + kotlin.math.sin(a).toFloat() * r * 1.055f
            p.color = Color.argb(100 + (pulse * 80).toInt(), 77, 225, 240)
            canvas.drawCircle(x, y, r * 0.015f, p)
        }

        // Brow: expressive arcs
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.027f
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(170, 68, 171, 197)
        val browY = cy - r * 0.33f
        canvas.drawArc(RectF(cx - r * 0.64f, browY - r * 0.10f, cx - r * 0.13f, browY + r * 0.12f), 195f, 145f, false, p)
        canvas.drawArc(RectF(cx + r * 0.13f, browY - r * 0.10f, cx + r * 0.64f, browY + r * 0.12f), 200f, 145f, false, p)
        p.style = Paint.Style.FILL

        val blink = frame % 79 == 0 || frame % 79 == 1
        val eyeH = if (blink) r * 0.025f else when(mode) {
            1 -> r * 0.29f
            2 -> r * 0.19f + pulse * r * 0.08f
            3 -> r * 0.26f
            else -> r * 0.24f
        }
        val eyeW = r * 0.26f
        val eyeY = cy - r * 0.07f
        for (offset in listOf(-0.39f, 0.39f)) {
            val x = cx + r * offset
            p.color = Color.argb(40, 55, 232, 240)
            canvas.drawRoundRect(RectF(x - eyeW * 0.67f, eyeY - eyeH * 0.72f,
                x + eyeW * 0.67f, eyeY + eyeH * 0.72f), eyeW, eyeW, p)
            p.color = eye
            canvas.drawRoundRect(RectF(x - eyeW * 0.49f, eyeY - eyeH * 0.5f,
                x + eyeW * 0.49f, eyeY + eyeH * 0.5f), eyeW, eyeW, p)
            if (!blink) {
                p.color = Color.WHITE
                canvas.drawCircle(x - eyeW * 0.13f, eyeY - eyeH * 0.16f, r * 0.025f, p)
            }
        }

        // Speaking: animated equalizer mouth; idle: curved smile
        val mouthY = cy + r * 0.42f
        if (mode == 3 || mode == 2) {
            val heights = floatArrayOf(0.10f, 0.19f, 0.27f, 0.19f, 0.10f)
            for (i in heights.indices) {
                val wave = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(time * 2.3 + i * 0.9)).toFloat()
                val bh = r * heights[i] * (if (mode == 3) wave else 0.4f + pulse * 0.4f)
                val bx = cx + (i - 2) * r * 0.14f
                p.color = eye
                canvas.drawRoundRect(RectF(bx - r * 0.035f, mouthY - bh / 2,
                    bx + r * 0.035f, mouthY + bh / 2), r * 0.03f, r * 0.03f, p)
            }
        } else {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = r * 0.032f
            p.color = eye
            canvas.drawArc(RectF(cx - r * 0.25f, mouthY - r * 0.13f,
                cx + r * 0.25f, mouthY + r * 0.13f), 15f, 150f, false, p)
            p.style = Paint.Style.FILL
        }
        // Listening indicator at forehead
        if (mode == 1) {
            p.color = eye
            canvas.drawCircle(cx, cy - r * 0.66f, r * (0.024f + pulse * 0.013f), p)
        }
    }
}
