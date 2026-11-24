package com.wristeyes

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Big text on the whole screen; closes itself after a few seconds or on tap. */
class MessageActivity : Activity() {
    private lateinit var label: TextView
    private val close = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (resources.displayMetrics.widthPixels * 0.14).toInt()
        label = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { finish() }
        }
        setContentView(label)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent)
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    private fun render(i: Intent) {
        val text = i.getStringExtra(EXTRA_TEXT).orEmpty()
        label.text = text
        label.textSize = when {
            text.length < 12 -> 30f
            text.length < 40 -> 22f
            text.length < 100 -> 17f
            else -> 14f
        }
        label.removeCallbacks(close)
        label.postDelayed(close, i.getIntExtra(EXTRA_SECONDS, 8) * 1000L)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_SECONDS = "seconds"
        @Volatile var visible = false
    }
}
