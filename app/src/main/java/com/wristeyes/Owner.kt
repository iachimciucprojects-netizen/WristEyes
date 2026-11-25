package com.wristeyes

import android.content.Context
import java.util.Calendar

/** The watch owner's name, said in answers once it is known. */
object Owner {
    private fun prefs(c: Context) = c.getSharedPreferences("wristeyes", Context.MODE_PRIVATE)

    fun name(c: Context): String = prefs(c).getString("owner_name", "") ?: ""

    fun setName(c: Context, name: String) = prefs(c).edit().putString("owner_name", name.trim()).apply()

    /**
     * Pulls a name out of what was heard: "меня зовут богдан" -> "Богдан",
     * "my name is anna" -> "Anna", "богдан" -> "Богдан".
     */
    fun parseName(heard: String): String {
        var t = heard.lowercase().trim()
        for (p in listOf("меня зовут", "моё имя", "мое имя", "my name is", "call me", "i am", "i'm", "это", "я")) {
            val i = t.indexOf("$p ")
            if (i >= 0 && (i == 0 || t[i - 1] == ' ')) { t = t.substring(i + p.length + 1); break }
        }
        val words = t.split(" ").filter { it.isNotBlank() && it != "[unk]" }.take(2)
        return words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** Last time the face greeted on its own, so it doesn't repeat itself every time the app opens. */
    fun shouldGreet(c: Context): Boolean {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val lastPart = p.getString("greet_part", "")
        val lastAt = p.getLong("greet_at", 0)
        val part = DayPart.now().name
        if (part == lastPart && now - lastAt < 3 * 3600_000L) return false
        p.edit().putString("greet_part", part).putLong("greet_at", now).apply()
        return true
    }
}

enum class DayPart {
    MORNING, DAY, EVENING, NIGHT;

    /** "Доброе утро, Богдан!" / "Good evening, Anna!" */
    fun greeting(name: String): String {
        val n = if (name.isEmpty()) "" else ", $name"
        return when (this) {
            MORNING -> Lang.t("Доброе утро$n!", "Good morning$n!")
            DAY -> if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 12..14)
                Lang.t("Добрый день$n! Самое время пообедать.", "Good afternoon$n! Time for lunch.")
            else Lang.t("Добрый день$n!", "Good afternoon$n!")
            EVENING -> Lang.t("Добрый вечер$n!", "Good evening$n!")
            NIGHT -> Lang.t("Доброй ночи$n! Уже поздно.", "Hi$n! It's late, you should rest.")
        }
    }

    companion object {
        fun now(): DayPart = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> MORNING
            in 12..16 -> DAY
            in 17..22 -> EVENING
            else -> NIGHT
        }
    }
}
