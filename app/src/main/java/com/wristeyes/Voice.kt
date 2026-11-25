package com.wristeyes

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Voice language follows the watch language: Russian if the watch is in Russian,
 * English otherwise.
 */
object Lang {
    val ru: Boolean get() = Locale.getDefault().language == "ru"

    /** Picks the Russian or English variant of a string. */
    fun t(ru: String, en: String) = if (this.ru) ru else en

    val locale: Locale get() = if (ru) Locale("ru") else Locale.US

    /** Small Vosk models, ~45 MB each. */
    val modelUrl: String get() = if (ru)
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    else
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    val modelFolder: String get() = if (ru) "vosk-model-ru" else "vosk-model-en"
}

/** Loads the Vosk model once and keeps it in memory; downloads it on first use. */
object VoskModel {
    private var model: Model? = null
    private var modelLang: String? = null
    val loaded get() = model != null && modelLang == Lang.modelFolder

    fun dir(c: Context): File = File(c.filesDir, Lang.modelFolder)

    fun installed(c: Context): Boolean {
        val d = dir(c)
        return File(d, "am").exists() || File(d, "conf").exists()
    }

    /** Recognizer limited to the command words; falls back to free speech if the model can't do grammars. */
    fun recognizer(model: Model): Recognizer {
        val grammar = org.json.JSONArray(Commands.vocabulary() + "[unk]").toString()
        return runCatching { Recognizer(model, 16000f, grammar) }.getOrElse { Recognizer(model, 16000f) }
    }

    /** Free speech, used to hear the owner's name. */
    fun freeRecognizer(model: Model) = Recognizer(model, 16000f)

    @Synchronized
    fun get(c: Context): Model? {
        if (loaded) return model
        if (!installed(c)) return null
        model?.close()
        return runCatching { Model(dir(c).path) }.getOrNull()?.also { model = it; modelLang = Lang.modelFolder }
    }

    /**
     * Downloads and unpacks the model for the current language.
     * [progress] gets 0..100. Blocks; call off the main thread.
     */
    @Synchronized
    fun download(c: Context, progress: (Int) -> Unit) {
        if (installed(c)) return
        val zip = File(c.cacheDir, "model.zip")
        val conn = URL(Lang.modelUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // Download is 90% of the work, unpacking the last 10%.
                        val p = if (total > 0) (done * 90 / total).toInt() else 0
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val tmp = File(c.filesDir, "model-unpacking")
        tmp.deleteRecursively()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                // Drop the top folder ("vosk-model-small-ru-0.22/...").
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(tmp, rel)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
            }
        }
        zip.delete()
        progress(100)
        val target = dir(c)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IllegalStateException("could not move the model")
    }
}
