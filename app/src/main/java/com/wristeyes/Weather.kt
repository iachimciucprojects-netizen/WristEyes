package com.wristeyes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Weather from Open-Meteo (free, no key). The place comes from the watch's last known
 * location, or, if there is none, a rough guess from the public IP address.
 */
class Weather(private val context: Context) {

    fun answer(tomorrow: Boolean): String {
        val place = location() ?: return Lang.t("Не знаю, где ты. Нет интернета?", "I don't know where you are. No internet?")
        val (lat, lon) = place
        val url = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=2&wind_speed_unit=ms", lat, lon)
        val j = runCatching { JSONObject(get(url)) }.getOrNull()
            ?: return Lang.t("Не получилось узнать погоду. Проверь Wi-Fi.", "Couldn't get the weather. Check Wi-Fi.")
        val d = j.getJSONObject("daily")
        if (tomorrow) {
            val max = d.getJSONArray("temperature_2m_max").getDouble(1).roundToInt()
            val min = d.getJSONArray("temperature_2m_min").getDouble(1).roundToInt()
            val rain = d.getJSONArray("precipitation_probability_max").optInt(1, 0)
            val sky = sky(d.getJSONArray("weather_code").getInt(1))
            return Lang.t("Завтра $sky, от ${deg(min)} до ${deg(max)}${rain(rain)}.",
                "Tomorrow $sky, from ${deg(min)} to ${deg(max)}${rain(rain)}.")
        }
        val c = j.getJSONObject("current")
        val now = c.getDouble("temperature_2m").roundToInt()
        val feels = c.getDouble("apparent_temperature").roundToInt()
        val wind = c.getDouble("wind_speed_10m").roundToInt()
        val max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val rain = d.getJSONArray("precipitation_probability_max").optInt(0, 0)
        val sky = sky(c.getInt("weather_code"))
        val feelsPart = if (feels != now) Lang.t(", ощущается как ${deg(feels)}", ", feels like ${deg(feels)}") else ""
        return Lang.t(
            "Сейчас ${deg(now)}, $sky$feelsPart. Ветер $wind м/с. Днём до ${deg(max)}${rain(rain)}.",
            "Now ${deg(now)}, $sky$feelsPart. Wind $wind m/s. Up to ${deg(max)} today${rain(rain)}.")
    }

    private fun deg(t: Int) = if (Lang.ru) (if (t > 0) "+$t" else "$t") + " " +
        Commands.plural(kotlin.math.abs(t).toLong(), "градус", "градуса", "градусов")
    else "$t degrees"

    private fun rain(p: Int) = when {
        p >= 60 -> Lang.t(", скорее всего дождь, возьми зонт", ", rain is likely, take an umbrella")
        p >= 30 -> Lang.t(", возможен дождь", ", maybe some rain")
        else -> ""
    }

    /** WMO weather codes in plain words. */
    private fun sky(code: Int) = when (code) {
        0 -> Lang.t("ясно", "clear sky")
        1, 2 -> Lang.t("малооблачно", "partly cloudy")
        3 -> Lang.t("пасмурно", "overcast")
        45, 48 -> Lang.t("туман", "foggy")
        in 51..57 -> Lang.t("морось", "drizzle")
        in 61..67, in 80..82 -> Lang.t("дождь", "rain")
        in 71..77, 85, 86 -> Lang.t("снег", "snow")
        in 95..99 -> Lang.t("гроза", "thunderstorm")
        else -> Lang.t("облачно", "cloudy")
    }

    private fun location(): Pair<Double, Double>? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = context.getSystemService(LocationManager::class.java)
            val best = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (best != null) return best.latitude to best.longitude
        }
        // No GPS fix on the watch: approximate the city from the IP address.
        return runCatching {
            val j = JSONObject(get("https://ipwho.is/?fields=success,latitude,longitude"))
            if (j.optBoolean("success")) j.getDouble("latitude") to j.getDouble("longitude") else null
        }.getOrNull()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
}
