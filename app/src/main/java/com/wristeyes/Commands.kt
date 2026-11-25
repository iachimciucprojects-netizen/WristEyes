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

/** What the assistant answers, and how the face should react. */
data class Reply(val text: String, val confused: Boolean = false, val askName: Boolean = false)

/**
 * Offline voice commands in Russian or English (follows the watch language).
 * No AI: a phrase is matched by keywords and answered from the watch itself.
 */
class Commands(private val context: Context, private val sensors: SensorReader) {

    private val weather = Weather(context)

    /**
     * Turns a recognized phrase into a spoken answer. May block for a while
     * (heart rate takes up to 30 s, weather needs the network), so call it off the main thread.
     * [progress] receives short status lines such as "Меряю пульс…".
     */
    fun handle(phrase: String, progress: (String) -> Unit): Reply {
        val t = phrase.lowercase(Locale.ROOT).replace('ё', 'е').replace("[unk]", "").trim()
        if (t.isEmpty()) return Reply(Lang.t("Я ничего не услышал.", "I didn't hear anything."), confused = true)
        val name = Owner.name(context)
        val r = route(t, name, progress)
        return if (name.isEmpty() || r.askName || r.text.contains(name)) r else r.copy(text = personal(name, r.text))
    }

    /** "Богдан, пульс 68…" */
    private fun personal(name: String, text: String): String {
        val first = text.substringBefore(' ')
        // Keep capitals on names like "WristEyes", lower the first letter of ordinary words.
        val body = if (first.length > 1 && first.drop(1).any { it.isUpperCase() }) text
            else text.replaceFirstChar { it.lowercase() }
        return "$name, $body"
    }

    private fun route(t: String, name: String, progress: (String) -> Unit): Reply = when {
        // Name first: "как меня зовут" must not be read as "как тебя зовут".
        has(t, "как меня зовут", "what's my name", "what is my name", "who am i") ->
            Reply(if (name.isEmpty()) Lang.t("Я ещё не знаю. Скажи «меня зовут».", "I don't know yet. Say \"my name is\".")
                  else Lang.t("Тебя зовут $name.", "Your name is $name."))
        has(t, "меня зовут", "мое имя", "my name", "call me") -> {
            val heard = Owner.parseName(t)
            if (heard.isEmpty()) Reply(Lang.t("Как тебя зовут?", "What's your name?"), askName = true)
            else { Owner.setName(context, heard); Reply(Lang.t("Приятно познакомиться, $heard!", "Nice to meet you, $heard!")) }
        }
        has(t, "таймер", "засеки", "timer") -> timer(t)
        has(t, "погод", "на улице", "холодно", "тепло", "жарко", "дождь", "градус", "weather", "temperature", "outside", "rain", "cold", "warm") -> {
            progress(Lang.t("Смотрю погоду…", "Checking the weather…"))
            Reply(weather.answer(tomorrow = has(t, "завтра", "tomorrow")))
        }
        has(t, "пульс", "сердц", "heart", "pulse") -> heartRate(progress)
        has(t, "шаг", "step") -> steps()
        has(t, "который час", "сколько времени", "время", "what time", "time is it", "the time") ->
            Reply(Lang.t("Сейчас ${time()}.", "It's ${time()}."))
        has(t, "какое число", "какое сегодня", "дата", "день недели", "какой сегодня день", "date", "what day", "today") ->
            Reply(Lang.t("Сегодня ${date()}.", "Today is ${date()}."))
        has(t, "батаре", "заряд", "batter", "charge") -> battery()
        has(t, "высот", "давлен", "altitude", "pressure", "height") -> altitude()
        has(t, "компас", "север", "направлен", "сторона света", "compass", "north", "direction", "facing") -> compass()
        has(t, "вибр", "vibrat", "buzz") -> { buzz(); Reply(Lang.t("Вот так.", "Like this.")) }
        has(t, "спокойной ночи", "good night", "goodnight") -> goodNight(name)
        has(t, "доброе утро", "добрый день", "добрый вечер", "доброй ночи", "привет", "здравствуй",
            "good morning", "good afternoon", "good evening", "hello", "hi ", "hey") -> greeting(t, name)
        has(t, "как дела", "как ты", "how are you") -> Reply(Lang.t("Отлично, работаю.", "Great, I'm working."))
        has(t, "как тебя зовут", "кто ты", "твое имя", "your name", "who are you") ->
            Reply(Lang.t("Я WristEyes, глаза на твоём запястье.", "I'm WristEyes, the eyes on your wrist."))
        has(t, "спасибо", "thank") -> Reply(Lang.t("Пожалуйста!", "You're welcome!"))
        has(t, "что ты умеешь", "помощь", "команды", "помоги", "what can you do", "help") -> Reply(help())
        t == "hi" -> greeting(t, name)
        else -> Reply(Lang.t("Не понял. Скажи «что ты умеешь».", "Sorry, I didn't get that. Say \"what can you do\"."), confused = true)
    }

    private fun has(t: String, vararg keys: String) = keys.any { t.contains(it) }

    // ------------------------------------------------------------ greetings

    private fun greeting(t: String, name: String): Reply {
        val part = DayPart.now()
        val said = when {
            has(t, "утро", "morning") -> DayPart.MORNING
            has(t, "вечер", "evening") -> DayPart.EVENING
            has(t, "доброй ночи") -> DayPart.NIGHT
            has(t, "день", "afternoon") -> DayPart.DAY
            else -> null
        }
        val n = if (name.isEmpty()) "" else ", $name"
        // A little joke if the greeting doesn't match the clock.
        if (said != null && said != part) {
            val real = when (part) {
                DayPart.MORNING -> Lang.t("утро", "morning")
                DayPart.DAY -> Lang.t("день", "afternoon")
                DayPart.EVENING -> Lang.t("вечер", "evening")
                DayPart.NIGHT -> Lang.t("ночь", "night")
            }
            return Reply(Lang.t("Привет$n! Вообще-то сейчас $real, ${time()}.", "Hi$n! Actually it's $real, ${time()}."))
        }
        return Reply(part.greeting(name))
    }

    private fun goodNight(name: String): Reply {
        val n = if (name.isEmpty()) "" else ", $name"
        return Reply(when (DayPart.now()) {
            DayPart.NIGHT, DayPart.EVENING -> Lang.t("Спокойной ночи$n! Сладких снов.", "Good night$n! Sweet dreams.")
            else -> Lang.t("Уже спать? Ну хорошо, спокойной ночи$n!", "Going to sleep already? Good night$n!")
        })
    }

    // ------------------------------------------------------------ watch data

    private fun time() = SimpleDateFormat(if (Lang.ru) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date())

    private fun date() = SimpleDateFormat(if (Lang.ru) "EEEE, d MMMM" else "EEEE, MMMM d", Lang.locale).format(Date())

    private fun heartRate(progress: (String) -> Unit): Reply {
        progress(Lang.t("Меряю пульс…", "Measuring your pulse…"))
        return try {
            val bpm = sensors.read("heart_rate", 30).getInt("bpm")
            Reply(Lang.t("Пульс $bpm ударов в минуту.", "Your heart rate is $bpm beats per minute."))
        } catch (e: Exception) {
            Reply(Lang.t("Не получилось измерить пульс. Часы на руке?", "Couldn't measure your pulse. Is the watch on your wrist?"), confused = true)
        }
    }

    private fun steps(): Reply = try {
        // The step counter only knows steps since the watch was switched on.
        val n = sensors.read("steps", 5).getLong("steps_since_boot")
        Reply(Lang.t("С момента включения часов $n ${plural(n, "шаг", "шага", "шагов")}.",
            "$n steps since the watch was switched on."))
    } catch (e: Exception) {
        Reply(Lang.t("Не могу прочитать шаги.", "I can't read the steps."), confused = true)
    }

    private fun battery(): Reply {
        val bm = context.getSystemService(BatteryManager::class.java)
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return Reply(if (Lang.ru)
            "Заряд $p ${plural(p.toLong(), "процент", "процента", "процентов")}" + if (bm.isCharging) ", заряжаюсь." else "."
        else "Battery is at $p percent" + if (bm.isCharging) ", charging." else ".")
    }

    private fun altitude(): Reply = try {
        val r = sensors.read("pressure", 5)
        val m = r.getDouble("altitude_m").toInt()
        val hpa = r.getDouble("hpa").toInt()
        Reply(Lang.t("Высота примерно $m метров, давление $hpa гектопаскалей.",
            "Altitude is about $m meters, pressure $hpa hectopascals."))
    } catch (e: Exception) {
        Reply(Lang.t("Барометр не отвечает.", "The barometer isn't answering."), confused = true)
    }

    private fun compass(): Reply = try {
        val r = sensors.read("compass", 5)
        val code = r.getString("direction")
        val deg = r.getInt("heading_deg")
        if (Lang.ru) {
            val dir = mapOf("N" to "север", "NE" to "северо-восток", "E" to "восток", "SE" to "юго-восток",
                "S" to "юг", "SW" to "юго-запад", "W" to "запад", "NW" to "северо-запад")[code]
            Reply("Ты смотришь на $dir, $deg градусов.")
        } else {
            val dir = mapOf("N" to "north", "NE" to "north-east", "E" to "east", "SE" to "south-east",
                "S" to "south", "SW" to "south-west", "W" to "west", "NW" to "north-west")[code]
            Reply("You're facing $dir, $deg degrees.")
        }
    } catch (e: Exception) {
        Reply(Lang.t("Компас не отвечает.", "The compass isn't answering."), confused = true)
    }

    private fun timer(t: String): Reply {
        val seconds = Durations.parseSeconds(t)
        if (seconds <= 0) return Reply(Lang.t("На сколько поставить таймер? Скажи, например, «таймер на пять минут».",
            "For how long? Say, for example, \"timer for five minutes\"."), confused = true)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "WristEyes")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Reply(Lang.t("Таймер на ${Durations.say(seconds)}.", "Timer set for ${Durations.say(seconds)}."))
        } catch (e: Exception) {
            Reply(Lang.t("На часах нет приложения таймера.", "There's no timer app on this watch."), confused = true)
        }
    }

    private fun buzz() {
        context.getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
    }

    private fun help() = Lang.t(
        "Спроси пульс, шаги, время, дату, заряд, высоту, компас или погоду. Или скажи «таймер на пять минут».",
        "Ask for your heart rate, steps, time, date, battery, altitude, compass or the weather. Or say \"timer for five minutes\".")

    companion object {
        /**
         * Every word the recognizer may hear. Limiting Vosk to these words makes
         * short commands far more accurate than free speech ("пульс" instead of
         * "пользуясь"). Anything else comes out as [unk].
         */
        fun vocabulary(): List<String> = (if (Lang.ru) RU_WORDS else EN_WORDS).split(" ")

        private const val RU_WORDS =
            "какой какая какое какие у меня мой моя сейчас скажи покажи сколько который это " +
            "пульс сердце сердцебиение шаги шагов шага прошел время час часа часов дата число " +
            "сегодня завтра день недели заряд батарея батареи высота высоту давление компас север " +
            "направление сторона света где таймер засеки поставь поставить на и вибрация вибрируй " +
            "привет здравствуй добрый доброе доброй вечер утро ночи спокойной как дела ты тебя меня зовут " +
            "кто твое мое имя спасибо что умеешь помощь команды помоги погода погоду погоды будет " +
            "улице холодно тепло жарко дождь градусов " +
            "полчаса полтора полторы секунд секунду секунды " +
            "минут минуту минуты ноль один одна одну два две три четыре пять шесть семь восемь " +
            "девять десять одиннадцать двенадцать тринадцать четырнадцать пятнадцать шестнадцать " +
            "семнадцать восемнадцать девятнадцать двадцать тридцать сорок пятьдесят шестьдесят " +
            "семьдесят восемьдесят девяносто сто"

        private const val EN_WORDS =
            "what what's is my the a an it's me tell show how much many now today tomorrow " +
            "heart rate pulse beat steps step time date day battery charge level altitude height pressure " +
            "compass north direction facing which way where am i timer set for start vibrate buzz " +
            "hello hi hey good morning afternoon evening night are you doing your name who call " +
            "thanks thank can do help commands weather temperature outside cold warm hot rain it will be " +
            "half hour hours minute minutes second seconds and " +
            "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen " +
            "sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred"

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

/** Spoken durations to seconds: "двадцать пять минут", "полчаса", "five minutes", "half an hour", "1 hour 30 minutes". */
object Durations {
    private val words = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70,
        "восемьдесят" to 80, "девяносто" to 90, "сто" to 100,
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )

    fun parseSeconds(text: String): Int {
        val t = text.replace("полчаса", "30 минут").replace("полторы минуты", "90 секунд")
            .replace("полтора часа", "90 минут").replace("half an hour", "30 minutes")
            .replace("half hour", "30 minutes")
        var total = 0
        var number: Int? = null
        for (tok in t.split(Regex("[^\\p{L}\\d]+")).filter { it.isNotEmpty() }) {
            val n = tok.toIntOrNull() ?: words[tok]
            when {
                n != null -> number = (number ?: 0) + n
                tok.startsWith("секунд") || tok.startsWith("second") -> { total += (number ?: 1); number = null }
                tok.startsWith("минут") || tok.startsWith("minute") -> { total += (number ?: 1) * 60; number = null }
                (tok.startsWith("час") && tok != "часы") || tok.startsWith("hour") -> { total += (number ?: 1) * 3600; number = null }
            }
        }
        return total
    }

    fun say(seconds: Int): String {
        val parts = mutableListOf<String>()
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        if (Lang.ru) {
            if (h > 0) parts += "$h ${Commands.plural(h.toLong(), "час", "часа", "часов")}"
            if (m > 0) parts += "$m ${Commands.plural(m.toLong(), "минуту", "минуты", "минут")}"
            if (s > 0) parts += "$s ${Commands.plural(s.toLong(), "секунду", "секунды", "секунд")}"
        } else {
            if (h > 0) parts += "$h hour" + if (h > 1) "s" else ""
            if (m > 0) parts += "$m minute" + if (m > 1) "s" else ""
            if (s > 0) parts += "$s second" + if (s > 1) "s" else ""
        }
        return parts.joinToString(" ")
    }
}
