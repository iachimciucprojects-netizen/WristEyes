package com.wristeyes

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Connection settings, opened with a long press on the face: where the API
 * listens, the token for the MCP server, and a switch to stop it.
 */
class MainActivity : Activity() {
    private lateinit var address: TextView
    private lateinit var token: TextView
    private lateinit var toggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = resources.displayMetrics.widthPixels
        val pad = (size * 0.14).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, (size * 0.12).toInt(), pad, (size * 0.2).toInt())
        }
        column.addView(label("Подключение", 16f, Color.WHITE, bold = true))
        column.addView(label("Адрес", 11f, Color.rgb(150, 140, 220)).apply { setPadding(0, 18, 0, 0) })
        address = label("", 14f, Color.WHITE).also { column.addView(it) }
        column.addView(label("Токен для MCP", 11f, Color.rgb(150, 140, 220)).apply { setPadding(0, 14, 0, 0) })
        token = label("", 12f, Color.rgb(200, 200, 210)).apply { setTextIsSelectable(true) }.also { column.addView(it) }
        toggle = label("", 14f, Color.WHITE, bold = true).apply {
            setPadding(0, 22, 0, 22)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.rgb(60, 48, 140))
            }
            setOnClickListener {
                if (WatchService.running) WatchService.stop(this@MainActivity) else WatchService.start(this@MainActivity)
                postDelayed({ refresh() }, 400)
            }
        }
        column.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 26 })
        column.addView(label("👀  Назад", 14f, Color.WHITE, bold = true).apply {
            setPadding(0, 22, 0, 22)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.rgb(108, 99, 255))
            }
            setOnClickListener { backToFace() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
        setContentView(ScrollView(this).apply {
            addView(column)
            setBackgroundColor(Color.BLACK)
        })
    }

    override fun onResume() {
        super.onResume()
        address.postDelayed({ refresh() }, 300)
    }

    // targetSdk 36: the system back gesture goes through OnBackInvokedDispatcher, not onBackPressed.
    private val backCallback by lazy { android.window.OnBackInvokedCallback { backToFace() } }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = backToFace()   // Wear OS 3 (Android 11)

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
    }

    override fun onStop() {
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        super.onStop()
    }

    /** Always lands on the face, even if Android already closed it in the background. */
    private fun backToFace() {
        startActivity(Intent(this, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun refresh() {
        val on = WatchService.running
        address.text = if (on) "${WatchService.localIp() ?: "нет Wi-Fi"}:${WatchService.PORT}" else "выключено"
        token.text = Prefs.token(this)
        toggle.text = if (on) "Выключить" else "Включить"
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
