package com.wristeyes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps the local API running in the background: HTTP server, Wi-Fi kept up,
 * and the service announced on the network as _wristeyes._tcp.
 */
class WatchService : Service() {
    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private lateinit var sensors: SensorReader
    private var tts: TextToSpeech? = null
    private val ttsReady = CountDownLatch(1)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        sensors = SensorReader(this)
        createChannels()
        startForeground(ID_SERVICE, serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristEyes:api").apply { acquire() }
        keepWifiUp()

        server = HttpServer(PORT, Prefs.token(this), ::route).also { it.start() }
        announce()
    }

    override fun onDestroy() {
        running = false
        server?.stop()
        nsdListener?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        wifiCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        wakeLock?.release()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- routes

    private fun route(r: Request): Response = when ("${r.method} ${r.path}") {
        "GET /info" -> Response.ok(info())
        "GET /sensors" -> Response.ok(JSONObject().put("ok", true).put("sensors", sensors.available()).put("hardware", sensors.allHardware()))
        "GET /sensor" -> readSensor(r.query["type"] ?: "", r.query["timeout"]?.toLongOrNull())
        "POST /vibrate" -> vibrate(r.json.optLong("ms", 400), r.json.optInt("times", 1))
        "POST /notify" -> notifyUser(r.json.optString("title", "WristEyes"), r.json.optString("text"))
        "POST /show" -> show(r.json.optString("text"), r.json.optInt("seconds", 8))
        "POST /say" -> say(r.json.optString("text"), r.json.optString("lang", ""))
        else -> Response.error(404, "no route ${r.method} ${r.path}")
    }

    private fun info(): JSONObject {
        val bm = getSystemService(BatteryManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("ok", true)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("build", Build.DISPLAY)
            .put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .put("screen_on", pm.isInteractive)
            .put("uptime_min", SystemClock.elapsedRealtime() / 60000)
            .put("ip", localIp())
            .put("app_version", BuildConfigCompat.versionName(this))
    }

    private fun readSensor(type: String, timeout: Long?): Response {
        val needs = when (type) {
            "heart_rate" -> Manifest.permission.BODY_SENSORS
            "steps" -> Manifest.permission.ACTIVITY_RECOGNITION
            else -> null
        }
        if (needs != null && checkSelfPermission(needs) != PackageManager.PERMISSION_GRANTED) {
            return Response.error(403, "permission $needs not granted: open WristEyes on the watch and allow it")
        }
        val t = timeout ?: if (type == "heart_rate") 30 else 5
        return try {
            Response.ok(sensors.read(type, t.coerceIn(1, 60)))
        } catch (e: IllegalArgumentException) {
            Response.error(400, e.message ?: "bad sensor")
        } catch (e: IllegalStateException) {
            Response.error(503, e.message ?: "sensor unavailable")
        }
    }

    private fun vibrate(ms: Long, times: Int): Response {
        val v = getSystemService(Vibrator::class.java)
        val n = times.coerceIn(1, 10)
        val on = ms.coerceIn(20, 3000)
        val timings = LongArray(n * 2) { if (it % 2 == 0) (if (it == 0) 0 else 250) else on }
        v.vibrate(VibrationEffect.createWaveform(timings, -1))
        return Response.ok()
    }

    private fun notifyUser(title: String, text: String): Response {
        if (text.isBlank()) return Response.error(400, "text is required")
        val n = Notification.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_eyes)
            .setContentTitle(title.take(60))
            .setContentText(text.take(500))
            .setStyle(Notification.BigTextStyle().bigText(text.take(500)))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(nextId++, n)
        return Response.ok()
    }

    /** Full-screen text. Uses a full-screen intent so it also works while the app is in the background. */
    private fun show(text: String, seconds: Int): Response {
        if (text.isBlank()) return Response.error(400, "text is required")
        val intent = Intent(this, MessageActivity::class.java)
            .putExtra(MessageActivity.EXTRA_TEXT, text.take(300))
            .putExtra(MessageActivity.EXTRA_SECONDS, seconds.coerceIn(2, 60))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_eyes)
            .setContentTitle("WristEyes")
            .setContentText(text.take(120))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setFullScreenIntent(pi, true)
            .setTimeoutAfter(seconds.coerceIn(2, 60) * 1000L)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_SHOW, n)
        // Without "display over other apps" Android only shows the notification
        // while the screen is on; with it, the text opens full screen right away.
        val canOverlay = android.provider.Settings.canDrawOverlays(this)
        if (canOverlay || MessageActivity.visible) runCatching { startActivity(intent) }
        return Response.ok(JSONObject().put("ok", true).put("full_screen", canOverlay || MessageActivity.visible))
    }

    private fun say(text: String, lang: String): Response {
        if (text.isBlank()) return Response.error(400, "text is required")
        val engine = tts ?: TextToSpeech(this) { ttsReady.countDown() }.also { tts = it }
        if (!ttsReady.await(8, TimeUnit.SECONDS)) return Response.error(503, "text-to-speech engine did not start")
        val locale = if (lang.isBlank()) Locale.getDefault() else Locale.forLanguageTag(lang)
        val res = engine.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            return Response.error(503, "voice for '${locale.toLanguageTag()}' is not installed on the watch")
        }
        engine.speak(text.take(500), TextToSpeech.QUEUE_FLUSH, null, "wristeyes")
        return Response.ok()
    }

    // ------------------------------------------------------------- plumbing

    /** Wear OS turns Wi-Fi off when a phone is connected over Bluetooth; asking for it keeps it on. */
    private fun keepWifiUp() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cm.bindProcessToNetwork(network) }
        }
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            cb,
        )
        wifiCallback = cb
    }

    private fun announce() {
        val info = NsdServiceInfo().apply {
            serviceName = "WristEyes ${Build.MODEL}"
            serviceType = "_wristeyes._tcp"
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        nsdListener = l
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "API running", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "On-screen text", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_eyes)
            .setContentTitle("WristEyes")
            .setContentText("Listening on port $PORT")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8765
        private const val CH_SERVICE = "service"
        private const val CH_MESSAGES = "messages"
        private const val CH_ALERTS = "alerts"
        private const val ID_SERVICE = 1
        private const val ID_SHOW = 2
        private var nextId = 100
        @Volatile var running = false

        fun localIp(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}

object BuildConfigCompat {
    fun versionName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
}

object Prefs {
    /** Random token created on first launch; it guards every API call. */
    fun token(c: Context): String {
        val p = c.getSharedPreferences("wristeyes", Context.MODE_PRIVATE)
        p.getString("token", null)?.let { return it }
        val bytes = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString("token", t).apply()
        return t
    }
}
