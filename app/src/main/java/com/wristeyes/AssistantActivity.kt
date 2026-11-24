package com.wristeyes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Offline voice assistant: listens with Vosk, answers simple commands,
 * speaks the answer. Tap the screen to ask again.
 */
class AssistantActivity : Activity(), RecognitionListener {
    private lateinit var eyes: TextView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var busy = false
    private var answered = false
    private var lastPartial = ""
    private lateinit var commands: Commands

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        commands = Commands(this, SensorReader(this))
        val pad = (resources.displayMetrics.widthPixels * 0.13).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(Color.BLACK)
            setOnClickListener { if (!busy) listen() }
        }
        eyes = text(34f).also { column.addView(it) }
        status = text(13f, Color.rgb(160, 150, 255)).also { column.addView(it) }
        heard = text(15f, Color.rgb(200, 200, 200)).also { column.addView(it) }
        answer = text(16f).apply { setTypeface(typeface, Typeface.BOLD) }.also { column.addView(it) }
        setContentView(column)

        tts = TextToSpeech(this) { tts?.language = Locale("ru") }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            listen()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else show("🙈", "Нужен доступ к микрофону", "", "")
    }

    override fun onDestroy() {
        speech?.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun listen() {
        busy = true
        answered = false
        lastPartial = ""
        tts?.stop()
        show("👀", "Загружаю…", "", "")
        thread {
            val model = VoskModel.get(this)
            runOnUiThread {
                if (model == null) {
                    busy = false
                    show("😵", "Нет модели речи", "", "Скопируй модель в\n${VoskModel.dir(this).path}")
                    return@runOnUiThread
                }
                try {
                    speech?.shutdown()
                    speech = SpeechService(VoskModel.recognizer(model), 16000f).also { it.startListening(this, 8000) }
                    show("👂", "Слушаю… говори", "", "")
                } catch (e: Exception) {
                    busy = false
                    show("😵", "Микрофон занят", "", e.message ?: "")
                }
            }
        }
    }

    // ---------------------------------------------------- Vosk callbacks

    override fun onPartialResult(hypothesis: String?) {
        val p = field(hypothesis, "partial")
        if (p.isNotEmpty()) { heard.text = p; lastPartial = p }
    }

    override fun onResult(hypothesis: String?) {
        val text = field(hypothesis, "text")
        if (text.isNotEmpty()) finish(text)
    }

    override fun onFinalResult(hypothesis: String?) {
        // Arrives after stop() or a timeout; only matters if onResult had nothing.
        finish(field(hypothesis, "text"))
    }

    override fun onError(e: Exception?) {
        busy = false
        show("😵", "Ошибка", "", e?.message ?: "")
    }

    override fun onTimeout() {
        finish(lastPartial)
    }

    // ---------------------------------------------------------- answer

    private fun finish(text: String) {
        if (answered) return
        answered = true
        speech?.stop()
        if (text.isEmpty()) {
            busy = false
            show("😴", "Не услышал. Нажми, чтобы спросить ещё", "", "")
            return
        }
        show("🤔", "Думаю…", "«$text»", "")
        thread {
            val reply = commands.handle(text) { p -> runOnUiThread { status.text = p } }
            runOnUiThread {
                busy = false
                show("😊", "Нажми, чтобы спросить ещё", "«$text»", reply)
                answer.textSize = if (reply.length > 60) 13f else 16f
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "answer")
            }
        }
    }

    private fun show(e: String, s: String, h: String, a: String) {
        eyes.text = e
        status.text = s
        heard.text = h
        answer.text = a
    }

    private fun field(json: String?, key: String) =
        runCatching { JSONObject(json ?: "").optString(key) }.getOrDefault("").trim()

    private fun text(size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 6, 0, 6)
    }
}

/** Loads the Vosk model once and keeps it in memory. */
object VoskModel {
    private var model: Model? = null

    /** The app's private folder; see README for how to copy the model there. */
    fun dir(c: Context): File = File(c.filesDir, "vosk-model-ru")

    /** Recognizer limited to the command words; falls back to free speech if the model can't do grammars. */
    fun recognizer(model: Model): Recognizer {
        val grammar = org.json.JSONArray(Commands.VOCABULARY + "[unk]").toString()
        return runCatching { Recognizer(model, 16000f, grammar) }.getOrElse { Recognizer(model, 16000f) }
    }

    @Synchronized
    fun get(c: Context): Model? {
        model?.let { return it }
        val d = dir(c)
        if (!File(d, "am").exists() && !File(d, "conf").exists()) return null
        return runCatching { Model(d.path) }.getOrNull()?.also { model = it }
    }
}
