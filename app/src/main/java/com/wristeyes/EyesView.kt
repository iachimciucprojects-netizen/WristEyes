package com.wristeyes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The WristEyes character: two big eyes that blink, look around and react.
 * Everything is drawn on a Canvas and eased toward targets every frame,
 * so state changes always animate smoothly.
 */
class EyesView(context: Context) : View(context) {

    enum class Mood { IDLE, LISTENING, THINKING, SPEAKING, HAPPY, CONFUSED, SLEEPY }

    var mood: Mood = Mood.IDLE
        set(value) {
            if (field != value) moodSince = SystemClock.uptimeMillis()
            field = value
        }

    private var moodSince = SystemClock.uptimeMillis()

    // Eased values (current) and where they are heading (target).
    private var lookX = 0f; private var lookY = 0f
    private var targetLookX = 0f; private var targetLookY = 0f
    private var openness = 1f          // 1 = open, 0 = closed (blink)
    private var ears = 0f              // 0 = hidden, 1 = fully out
    private var mouth = 0f             // 0 = smile line, 1 = wide open
    private var happy = 0f             // 1 = ^ ^ eyes
    private var squint = 0f            // confused: one eye smaller
    private var pupilScale = 1f

    private var nextBlink = 0L
    private var blinkStart = -1L
    private var nextWander = 0L

    private val lavender = Color.rgb(237, 233, 254)
    private val ink = Color.rgb(30, 27, 75)
    private val accent = Color.rgb(139, 124, 255)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) / 2f
        glow.shader = RadialGradient(w / 2f, h * 0.44f, r * 1.05f,
            intArrayOf(Color.rgb(46, 34, 110), Color.rgb(20, 15, 50), Color.BLACK),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        update(now)

        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2f
        val cy = h * 0.42f

        canvas.drawRect(0f, 0f, w, h, glow)

        // Listening pulse ring.
        if (ears > 0.05f) {
            val t = ((now - moodSince) % 1400) / 1400f
            stroke.color = accent
            stroke.alpha = ((1 - t) * 120 * ears).toInt()
            stroke.strokeWidth = s * 0.012f
            canvas.drawCircle(cx, cy, s * (0.30f + 0.12f * t), stroke)
            stroke.alpha = 255
        }

        drawEars(canvas, cx, cy, s, now)

        val eyeW = s * 0.17f
        val eyeH = s * 0.21f
        val gap = s * 0.115f
        drawEye(canvas, cx - gap - eyeW / 2, cy, eyeW, eyeH, left = true)
        drawEye(canvas, cx + gap + eyeW / 2, cy, eyeW, eyeH, left = false)

        drawMouth(canvas, cx, cy + s * 0.2f, s, now)

        if (mood == Mood.THINKING) drawDots(canvas, cx, cy + s * 0.2f, s, now)
        if (mood == Mood.CONFUSED) {
            fill.color = accent
            fill.textSize = s * 0.12f
            fill.textAlign = Paint.Align.CENTER
            canvas.drawText("?", cx + s * 0.27f, cy - s * 0.13f, fill)
        }

        postInvalidateOnAnimation()
    }

    /** Draws one eye centered at (cxEye, cy). */
    private fun drawEye(c: Canvas, cxEye: Float, cy: Float, ew: Float, eh: Float, left: Boolean) {

        var open = openness
        if (mood == Mood.SLEEPY) open = min(open, 0.35f)
        if (mood == Mood.CONFUSED && left) open *= (1 - 0.45f * squint)
        val hNow = max(eh * open, eh * 0.06f)

        if (happy > 0.5f) {
            // ^ ^ eyes
            stroke.color = lavender
            stroke.strokeWidth = ew * 0.22f
            path.reset()
            path.moveTo(cxEye - ew * 0.45f, cy + eh * 0.12f)
            path.quadTo(cxEye, cy - eh * 0.55f, cxEye + ew * 0.45f, cy + eh * 0.12f)
            c.drawPath(path, stroke)
            return
        }

        fill.color = lavender
        rect.set(cxEye - ew / 2, cy - hNow / 2, cxEye + ew / 2, cy + hNow / 2)
        c.drawRoundRect(rect, ew / 2, min(ew, hNow) / 2, fill)

        if (open > 0.25f) {
            val pr = ew * 0.30f * pupilScale
            val px = cxEye + lookX * ew * 0.22f
            val py = cy + lookY * hNow * 0.22f
            c.save()
            c.clipRect(rect)
            fill.color = ink
            c.drawCircle(px, py, pr, fill)
            fill.color = Color.WHITE
            c.drawCircle(px - pr * 0.35f, py - pr * 0.4f, pr * 0.32f, fill)
            c.restore()
        }
    }

    private fun drawEars(c: Canvas, cx: Float, cy: Float, s: Float, now: Long) {
        if (ears < 0.02f) return
        val wiggle = sin((now - moodSince) / 160.0).toFloat() * 0.06f * ears
        val r = s * 0.075f * ears
        val dx = s * 0.36f
        fill.color = accent
        fill.alpha = (255 * ears).toInt()
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * (dx + wiggle * s * 0.1f)
            rect.set(ex - r * 0.8f, cy - r * 1.3f, ex + r * 0.8f, cy + r * 1.3f)
            c.drawOval(rect, fill)
            fill.color = ink
            rect.inset(r * 0.35f, r * 0.45f)
            c.drawOval(rect, fill)
            fill.color = accent
        }
        fill.alpha = 255
    }

    private fun drawMouth(c: Canvas, cx: Float, my: Float, s: Float, now: Long) {
        stroke.color = lavender
        stroke.strokeWidth = s * 0.018f
        when {
            mood == Mood.THINKING -> {}
            mouth > 0.05f -> {
                fill.color = lavender
                val mw = s * 0.07f
                val mh = s * 0.012f + s * 0.06f * mouth
                rect.set(cx - mw / 2, my - mh / 2, cx + mw / 2, my + mh / 2)
                c.drawRoundRect(rect, mw / 2, mh / 2, fill)
                fill.color = ink
                rect.inset(mw * 0.2f, mh * 0.25f)
                if (mh > s * 0.03f) c.drawRoundRect(rect, mw / 2, mh / 2, fill)
            }
            mood == Mood.CONFUSED || mood == Mood.SLEEPY -> {
                c.drawLine(cx - s * 0.04f, my, cx + s * 0.04f, my, stroke)
            }
            else -> {
                val smile = if (happy > 0.3f) s * 0.045f else s * 0.025f
                path.reset()
                path.moveTo(cx - s * 0.05f, my - smile / 3)
                path.quadTo(cx, my + smile, cx + s * 0.05f, my - smile / 3)
                c.drawPath(path, stroke)
            }
        }
    }

    private fun drawDots(c: Canvas, cx: Float, my: Float, s: Float, now: Long) {
        val t = (now - moodSince) / 220
        fill.color = accent
        for (i in 0..2) {
            val active = (t % 3).toInt() == i
            fill.alpha = if (active) 255 else 90
            c.drawCircle(cx + (i - 1) * s * 0.05f, my, s * (if (active) 0.016f else 0.012f), fill)
        }
        fill.alpha = 255
    }

    private fun update(now: Long) {
        // Where to look.
        when (mood) {
            Mood.LISTENING -> { targetLookX = 0f; targetLookY = 0.1f }
            Mood.THINKING -> { targetLookX = 0.7f; targetLookY = -0.8f }
            Mood.SLEEPY -> { targetLookX = 0f; targetLookY = 0.6f }
            else -> if (now > nextWander) {
                targetLookX = Random.nextFloat() * 1.6f - 0.8f
                targetLookY = Random.nextFloat() * 1.0f - 0.5f
                if (Random.nextInt(3) == 0) { targetLookX = 0f; targetLookY = 0f }
                nextWander = now + 1200 + Random.nextLong(2200)
            }
        }
        lookX += (targetLookX - lookX) * 0.12f
        lookY += (targetLookY - lookY) * 0.12f

        // Blink every few seconds (not while happy or sleepy).
        if (nextBlink == 0L) nextBlink = now + 2500
        if (blinkStart < 0 && now > nextBlink && mood != Mood.SLEEPY) blinkStart = now
        openness = if (blinkStart >= 0) {
            val p = (now - blinkStart) / 160f
            if (p >= 1f) {
                blinkStart = -1
                nextBlink = now + 2200 + Random.nextLong(3000)
                1f
            } else abs(1 - 2 * p).coerceIn(0.05f, 1f)
        } else 1f

        ears += ((if (mood == Mood.LISTENING) 1f else 0f) - ears) * 0.15f
        happy += ((if (mood == Mood.HAPPY) 1f else 0f) - happy) * 0.2f
        squint += ((if (mood == Mood.CONFUSED) 1f else 0f) - squint) * 0.15f
        pupilScale += ((if (mood == Mood.LISTENING) 1.15f else 1f) - pupilScale) * 0.15f
        val talk = if (mood == Mood.SPEAKING) (0.5f + 0.5f * sin((now - moodSince) / 85.0).toFloat()) * (0.6f + 0.4f * sin((now - moodSince) / 230.0).toFloat()) else 0f
        mouth += (talk - mouth) * 0.35f
    }
}
