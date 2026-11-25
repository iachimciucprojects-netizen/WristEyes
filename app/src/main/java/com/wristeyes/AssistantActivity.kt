package com.wristeyes

import android.Manifest
import android.app.Activity
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
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import kotlin.concurrent.thread

/**
 * Main screen: the WristEyes face. Tap it to talk, long-press for connection settings.
 * Listens offline with Vosk, answers simple commands and speaks the answer.
 * On first use it downloads the voice model and asks the owner's name.
 */
class AssistantActivity : Activity(), RecognitionListener {
    private lateinit var face: EyesView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var answered = false
    private var lastPartial = ""
    /** True while we are listening for the owner's name (free speech, not commands). */
    private var askingName = false
    /** Start listening as soon as the current spoken line ends (so the mic doesn't hear the watch). */
    private var listenAfterSpeech = false
    private var pendingGreeting: String? = null
    private lateinit var commands: Commands
    private val backToIdle = Runnable { if (!busy) face.mood = EyesView.Mood.IDLE }
    private val tapHint get() = Lang.t("Нажми на меня", "Tap me")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        commands = Commands(this, SensorReader(this))

        val size = resources.displayMetrics.widthPixels
        face = EyesView(this).apply {
            setOnClickListener { if (!busy) onTap() }
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
        show("", tapHint)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Lang.locale
                ttsReady = true
                runOnUiThread { pendingGreeting?.let { pendingGreeting = null; speak(it) } }
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = runOnUiThread { if (face.mood != EyesView.Mood.CONFUSED) face.mood = EyesView.Mood.SPEAKING }
                override fun onDone(id: String?) = runOnUiThread { afterSpeech() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = runOnUiThread { afterSpeech() }
            })
        }

        if (!WatchService.running) WatchService.start(this)
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)

        greetIfTime()
    }

    override fun onDestroy() {
        speech?.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen stops the microphone.
        listenAfterSpeech = false
        if (busy) { speech?.cancel(); busy = false; askingName = false; face.mood = EyesView.Mood.IDLE; show("", tapHint) }
    }

    /** "Доброе утро, Богдан!" when the app opens, at most once per part of the day. */
    private fun greetIfTime() {
        val name = Owner.name(this)
        if (name.isEmpty() || !Owner.shouldGreet(this)) return
        val g = DayPart.now().greeting(name)
        show("", g)
        face.mood = EyesView.Mood.HAPPY
        if (ttsReady) speak(g) else pendingGreeting = g
    }

    // ------------------------------------------------------------ tap

    private fun onTap() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        tts?.stop()
        face.removeCallbacks(backToIdle)
        if (!VoskModel.installed(this)) { downloadModel(); return }
        if (Owner.name(this).isEmpty()) { askName(); return }
        listen()
    }

    /** First tap: the voice model is fetched once (~45 MB), with progress on screen. */
    private fun downloadModel() {
        busy = true
        face.mood = EyesView.Mood.THINKING
        show("", Lang.t("Скачиваю голос… 0%", "Downloading voice… 0%"))
        thread {
            val ok = runCatching {
                VoskModel.download(this) { p -> runOnUiThread { answer.text = Lang.t("Скачиваю голос… $p%", "Downloading voice… $p%") } }
            }.isSuccess
            runOnUiThread {
                busy = false
                if (ok) { face.mood = EyesView.Mood.HAPPY; show("", Lang.t("Готово! Нажми ещё раз", "Done! Tap me again")) }
                else { face.mood = EyesView.Mood.CONFUSED; show("", Lang.t("Не скачалось. Проверь Wi-Fi и нажми ещё раз", "Download failed. Check Wi-Fi and tap again")) }
                face.postDelayed(backToIdle, 3000)
            }
        }
    }

    /** Asks the name out loud, then listens with free speech once the question is spoken. */
    private fun askName() {
        askingName = true
        val q = Lang.t("Привет! Как тебя зовут?", "Hi! What's your name?")
        show("", q)
        face.mood = EyesView.Mood.HAPPY
        if (ttsReady && speak(q)) listenAfterSpeech = true else listen()
    }

    private fun afterSpeech() {
        if (listenAfterSpeech) { listenAfterSpeech = false; listen(); return }
        settle()
    }

    private fun listen() {
        busy = true
        answered = false
        lastPartial = ""
        face.mood = EyesView.Mood.THINKING
        if (!VoskModel.loaded) show("", Lang.t("Просыпаюсь…", "Waking up…"))
        thread {
            val model = VoskModel.get(this)
            runOnUiThread {
                if (model == null) {
                    busy = false
                    face.mood = EyesView.Mood.CONFUSED
                    show("", Lang.t("Нет модели речи. Нажми ещё раз", "No voice model. Tap again"))
                    return@runOnUiThread
                }
                try {
                    speech?.shutdown()
                    val rec = if (askingName) VoskModel.freeRecognizer(model) else VoskModel.recognizer(model)
                    speech = SpeechService(rec, 16000f).also { it.startListening(this, 8000) }
                    face.mood = EyesView.Mood.LISTENING
                    if (!askingName) show("", Lang.t("Слушаю…", "Listening…"))
                } catch (e: Exception) {
                    busy = false
                    face.mood = EyesView.Mood.CONFUSED
                    show("", Lang.t("Микрофон занят", "Microphone is busy"))
                }
            }
        }
    }

    // ---------------------------------------------------- Vosk callbacks

    override fun onPartialResult(hypothesis: String?) {
        val p = field(hypothesis, "partial")
        if (p.isNotEmpty()) { lastPartial = p; heard.text = p.replace("[unk]", "…"); if (!askingName) answer.text = "" }
    }

    override fun onResult(hypothesis: String?) {
        val text = field(hypothesis, "text")
        if (text.isNotEmpty()) finish(text)
    }

    override fun onFinalResult(hypothesis: String?) = finish(field(hypothesis, "text"))

    override fun onError(e: Exception?) {
        busy = false
        askingName = false
        face.mood = EyesView.Mood.CONFUSED
        show("", Lang.t("Ошибка микрофона", "Microphone error"))
    }

    override fun onTimeout() = finish(lastPartial)

    // ---------------------------------------------------------- answer

    private fun finish(text: String) {
        if (answered) return
        answered = true
        speech?.stop()
        if (askingName) { nameHeard(text); return }
        if (text.replace("[unk]", "").isBlank()) {
            busy = false
            face.mood = EyesView.Mood.SLEEPY
            show("", Lang.t("Не услышал. Нажми ещё раз", "I didn't hear you. Tap again"))
            face.postDelayed(backToIdle, 3000)
            return
        }
        face.mood = EyesView.Mood.THINKING
        show(text, "")
        thread {
            val reply = commands.handle(text) { p -> runOnUiThread { answer.text = p } }
            runOnUiThread {
                busy = false
                if (reply.askName) { askName(); return@runOnUiThread }
                face.mood = if (reply.confused) EyesView.Mood.CONFUSED else EyesView.Mood.SPEAKING
                show(text, reply.text)
                if (!speak(reply.text)) face.postDelayed({ settle() }, 2500)
            }
        }
    }

    private fun nameHeard(text: String) {
        askingName = false
        busy = false
        val name = Owner.parseName(text)
        if (name.isEmpty()) {
            face.mood = EyesView.Mood.CONFUSED
            val r = Lang.t("Не расслышал имя. Нажми и скажи ещё раз.", "I didn't catch your name. Tap and say it again.")
            show("", r)
            if (!speak(r)) face.postDelayed({ settle() }, 2500)
            return
        }
        Owner.setName(this, name)
        Owner.shouldGreet(this)   // counts as today's greeting
        face.mood = EyesView.Mood.HAPPY
        val r = Lang.t("Приятно познакомиться, $name! Теперь спрашивай что угодно.",
            "Nice to meet you, $name! Now ask me anything.")
        show("«$name»", r)
        if (!speak(r)) face.postDelayed({ settle() }, 2500)
    }

    private fun speak(text: String): Boolean =
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer") == TextToSpeech.SUCCESS

    /** After speaking: a short happy face, then back to idle. */
    private fun settle() {
        if (busy) return
        if (face.mood != EyesView.Mood.CONFUSED) face.mood = EyesView.Mood.HAPPY
        face.removeCallbacks(backToIdle)
        face.postDelayed(backToIdle, 1600)
    }

    private fun show(h: String, a: String) {
        heard.text = when {
            h.isEmpty() -> ""
            h.startsWith("«") -> h
            else -> "«${h.replace("[unk]", "…")}»"
        }
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
