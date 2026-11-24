package com.wristeyes

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline voice commands in Russian. No AI: a phrase is matched by keywords
 * and answered from the watch itself.
 */
class Commands(private val context: Context, private val sensors: SensorReader) {

    /**
     * Turns a recognized phrase into a spoken answer. May block for a while
     * (heart rate takes up to 30 s), so call it off the main thread.
     * [progress] receives short status lines such as "Меряю пульс…".
     */
    fun handle(phrase: String, progress: (String) -> Unit): String {
        val t = phrase.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (t.isEmpty()) return "Я ничего не услышал."
        return when {
            has(t, "таймер", "засеки") -> timer(t)
            has(t, "пульс", "сердц") -> heartRate(progress)
            has(t, "шаг") -> steps()
            has(t, "который час", "сколько времени", "время") -> "Сейчас ${time()}."
            has(t, "какое число", "какое сегодня", "дата", "день недели", "какой сегодня день") -> "Сегодня ${date()}."
            has(t, "батаре", "заряд") -> battery()
            has(t, "высот", "давлен") -> altitude()
            has(t, "компас", "север", "направлен", "сторона света") -> compass()
            has(t, "вибр") -> { buzz(); "Вот так." }
            has(t, "привет", "здравствуй", "добрый") -> "Привет! Я слушаю."
            has(t, "как дела", "как ты") -> "Отлично, работаю."
            has(t, "как тебя зовут", "кто ты", "твое имя") -> "Я WristEyes, глаза на твоём запястье."
            has(t, "спасибо") -> "Пожалуйста!"
            has(t, "что ты умеешь", "помощь", "команды", "помоги") -> HELP
            else -> "Не понял. Скажи «что ты умеешь»."
        }
    }

    private fun has(t: String, vararg keys: String) = keys.any { t.contains(it) }

    private fun time() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun date() = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())

    private fun heartRate(progress: (String) -> Unit): String {
        progress("Меряю пульс…")
        return try {
            "Пульс ${sensors.read("heart_rate", 30).getInt("bpm")} ударов в минуту."
        } catch (e: Exception) {
            "Не получилось измерить пульс. Часы на руке?"
        }
    }

    private fun steps(): String = try {
        // The step counter only knows steps since the watch was switched on.
        val n = sensors.read("steps", 5).getLong("steps_since_boot")
        "С момента включения часов $n ${plural(n, "шаг", "шага", "шагов")}."
    } catch (e: Exception) {
        "Не могу прочитать шаги."
    }

    private fun battery(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Заряд $p ${plural(p.toLong(), "процент", "процента", "процентов")}" + if (bm.isCharging) ", заряжаюсь." else "."
    }

    private fun altitude(): String = try {
        val r = sensors.read("pressure", 5)
        "Высота примерно ${r.getDouble("altitude_m").toInt()} метров, давление ${r.getDouble("hpa").toInt()} гектопаскалей."
    } catch (e: Exception) {
        "Барометр не отвечает."
    }

    private fun compass(): String = try {
        val r = sensors.read("compass", 5)
        val dir = mapOf("N" to "север", "NE" to "северо-восток", "E" to "восток", "SE" to "юго-восток",
            "S" to "юг", "SW" to "юго-запад", "W" to "запад", "NW" to "северо-запад")[r.getString("direction")]
        "Ты смотришь на $dir, ${r.getInt("heading_deg")} градусов."
    } catch (e: Exception) {
        "Компас не отвечает."
    }

    private fun timer(t: String): String {
        val seconds = Durations.parseSeconds(t)
        if (seconds <= 0) return "На сколько поставить таймер? Скажи, например, «таймер на пять минут»."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "WristEyes")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Таймер на ${Durations.say(seconds)}."
        } catch (e: Exception) {
            "На часах нет приложения таймера."
        }
    }

    private fun buzz() {
        context.getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
    }

    companion object {
        /**
         * Every word the recognizer may hear. Limiting Vosk to these words makes
         * short commands far more accurate than free speech ("пульс" instead of
         * "пользуясь"). Anything else comes out as [unk].
         */
        val VOCABULARY: List<String> = (
            "какой какая какое какие у меня мой моя сейчас скажи покажи сколько который это " +
            "пульс сердце сердцебиение шаги шагов шага прошел время час часа часов дата число " +
            "сегодня день недели заряд батарея батареи высота высоту давление компас север " +
            "направление сторона света где таймер засеки поставь поставить на и вибрация вибрируй " +
            "привет здравствуй добрый вечер утро как дела ты тебя зовут кто твое имя спасибо " +
            "что умеешь помощь команды помоги полчаса полтора полторы секунд секунду секунды " +
            "минут минуту минуты ноль один одна одну два две три четыре пять шесть семь восемь " +
            "девять десять одиннадцать двенадцать тринадцать четырнадцать пятнадцать шестнадцать " +
            "семнадцать восемнадцать девятнадцать двадцать тридцать сорок пятьдесят шестьдесят " +
            "семьдесят восемьдесят девяносто сто"
        ).split(" ")

        const val HELP = "Спроси пульс, шаги, время, дату, заряд, высоту или компас. " +
            "Или скажи «таймер на пять минут»."

        fun plural(n: Long, one: String, few: String, many: String): String {
            val a = n % 100
            val b = n % 10
            return when {
                a in 11..14 -> many
                b == 1L -> one
                b in 2..4 -> few
                else -> many
            }
        }
    }
}

/** Russian spoken durations ("двадцать пять минут", "полчаса", "1 час 30 минут") to seconds. */
object Durations {
    private val words = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70,
        "восемьдесят" to 80, "девяносто" to 90, "сто" to 100,
    )

    fun parseSeconds(text: String): Int {
        val t = text.replace("полчаса", "30 минут").replace("полторы минуты", "90 секунд")
            .replace("полтора часа", "90 минут")
        var total = 0
        var number: Int? = null
        for (tok in t.split(Regex("[^\\p{L}\\d]+")).filter { it.isNotEmpty() }) {
            val n = tok.toIntOrNull() ?: words[tok]
            when {
                n != null -> number = (number ?: 0) + n
                tok.startsWith("секунд") -> { total += (number ?: 1); number = null }
                tok.startsWith("минут") -> { total += (number ?: 1) * 60; number = null }
                tok.startsWith("час") && tok != "часы" -> { total += (number ?: 1) * 3600; number = null }
            }
        }
        return total
    }

    fun say(seconds: Int): String {
        val parts = mutableListOf<String>()
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        if (h > 0) parts += "$h ${Commands.plural(h.toLong(), "час", "часа", "часов")}"
        if (m > 0) parts += "$m ${Commands.plural(m.toLong(), "минуту", "минуты", "минут")}"
        if (s > 0) parts += "$s ${Commands.plural(s.toLong(), "секунду", "секунды", "секунд")}"
        return parts.joinToString(" ")
    }
}
