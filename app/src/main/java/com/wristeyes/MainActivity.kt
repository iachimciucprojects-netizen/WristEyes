package com.wristeyes

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shows where the API listens and the token to use; starts and stops the service. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.widthPixels * 0.12).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "👀 WristEyes"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        column.addView(status)
        toggle = Button(this).apply {
            setOnClickListener {
                if (WatchService.running) WatchService.stop(this@MainActivity) else WatchService.start(this@MainActivity)
                postDelayed({ refresh() }, 400)
            }
        }
        column.addView(toggle)
        setContentView(ScrollView(this).apply { addView(column); setBackgroundColor(Color.BLACK) })

        requestPermissions(
            arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS),
            1,
        )
        if (!WatchService.running) WatchService.start(this)
    }

    override fun onResume() {
        super.onResume()
        status.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val on = WatchService.running
        status.text = if (on) {
            "Listening\n${WatchService.localIp() ?: "no Wi-Fi"}:${WatchService.PORT}\n\nToken\n${Prefs.token(this)}"
        } else {
            "Stopped"
        }
        toggle.text = if (on) "Stop" else "Start"
    }
}
