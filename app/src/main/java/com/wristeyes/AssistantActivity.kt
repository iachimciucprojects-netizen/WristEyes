package com.wristeyes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
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
 * Main screen: the WristEyes face. Tap it to talk, long-press for connection settings.
 * Listens offline with Vosk, answers simple commands and speaks the answer.
 */
class AssistantActivity : Activity(), RecognitionListener {
    private lateinit var face: EyesView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var busy = false
    private var answered = false
    private var lastPartial = ""
    private lateinit var commands: Commands
    private val backToIdle = Runnable { if (!busy) face.mood = EyesView.Mood.IDLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        commands = Commands(this, SensorReader(this))

        val size = resources.displayMetrics.widthPixels
        face = EyesView(this).apply {
            setOnClickListener { if (!busy) listen() }
            setOnLongClickListener {
                startActivity(Intent(this@AssistantActivity, MainActivity::class.java)); true
            }
        }
        heard = caption(13f, Color.rgb(170, 160, 230)).apply { maxLines = 2 }
        answer = caption(15f, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 3 }

        setContentView(FrameLayout(this).apply {
            addView(face, FrameLayout.LayoutParams(-1, -1))
            addView(heard, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                topMargin = (size * 0.10).toInt(); leftMargin = (size * 0.2).toInt(); rightMargin = leftMargin
            })
            addView(answer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = (size * 0.09).toInt(); leftMargin = (size * 0.15).toInt(); rightMargin = leftMargin
            })
        })
        say("", "Нажми на меня")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru")
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = runOnUiThread { if (face.mood != EyesView.Mood.CONFUSED) face.mood = EyesView.Mood.SPEAKING }
                override fun onDone(id: String?) = runOnUiThread { settle() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = runOnUiThread { settle() }
            })
        }

        if (!WatchService.running) WatchService.start(this)
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onDestroy() {
        speech?.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen stops the microphone.
        if (busy) { speech?.cancel(); busy = false; face.mood = EyesView.Mood.IDLE; say("", "Нажми на меня") }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        busy = true
        answered = false
        lastPartial = ""
        tts?.stop()
        face.removeCallbacks(backToIdle)
        face.mood = EyesView.Mood.THINKING
        say("", if (VoskModel.loaded) "" else "Просыпаюсь…")
        thread {
            val model = VoskModel.get(this)
            runOnUiThread {
                if (model == null) {
                    busy = false
                    face.mood = EyesView.Mood.CONFUSED
                    say("", "Нет модели речи — смотри README")
                    return@runOnUiThread
                }
                try {
                    speech?.shutdown()
                    speech = SpeechService(VoskModel.recognizer(model), 16000f).also { it.startListening(this, 8000) }
                    face.mood = EyesView.Mood.LISTENING
                    say("", "Слушаю…")
                } catch (e: Exception) {
                    busy = false
                    face.mood = EyesView.Mood.CONFUSED
                    say("", "Микрофон занят")
                }
            }
        }
    }

    // ---------------------------------------------------- Vosk callbacks

    override fun onPartialResult(hypothesis: String?) {
        val p = field(hypothesis, "partial")
        if (p.isNotEmpty()) { lastPartial = p; heard.text = p; answer.text = "" }
    }

    override fun onResult(hypothesis: String?) {
        val text = field(hypothesis, "text")
        if (text.isNotEmpty()) finish(text)
    }

    override fun onFinalResult(hypothesis: String?) = finish(field(hypothesis, "text"))

    override fun onError(e: Exception?) {
        busy = false
        face.mood = EyesView.Mood.CONFUSED
        say("", "Ошибка микрофона")
    }

    override fun onTimeout() = finish(lastPartial)

    // ---------------------------------------------------------- answer

    private fun finish(text: String) {
        if (answered) return
        answered = true
        speech?.stop()
        if (text.isEmpty()) {
            busy = false
            face.mood = EyesView.Mood.SLEEPY
            say("", "Не услышал. Нажми ещё раз")
            face.postDelayed(backToIdle, 3000)
            return
        }
        face.mood = EyesView.Mood.THINKING
        say(text, "")
        thread {
            val reply = commands.handle(text) { p -> runOnUiThread { answer.text = p } }
            runOnUiThread {
                busy = false
                val confused = reply.startsWith("Не понял")
                face.mood = if (confused) EyesView.Mood.CONFUSED else EyesView.Mood.SPEAKING
                say(text, reply)
                val ok = tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "answer") == TextToSpeech.SUCCESS
                if (!ok) face.postDelayed({ settle() }, 2500)
            }
        }
    }

    /** After speaking: a short happy face, then back to idle. */
    private fun settle() {
        if (busy) return
        if (face.mood != EyesView.Mood.CONFUSED) face.mood = EyesView.Mood.HAPPY
        face.removeCallbacks(backToIdle)
        face.postDelayed(backToIdle, 1600)
    }

    private fun say(h: String, a: String) {
        heard.text = if (h.isEmpty()) "" else "«$h»"
        answer.text = a
        answer.textSize = if (a.length > 60) 12f else 15f
    }

    private fun field(json: String?, key: String) =
        runCatching { JSONObject(json ?: "").optString(key) }.getOrDefault("").trim()

    private fun caption(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
}

/** Loads the Vosk model once and keeps it in memory. */
object VoskModel {
    private var model: Model? = null
    val loaded get() = model != null

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
