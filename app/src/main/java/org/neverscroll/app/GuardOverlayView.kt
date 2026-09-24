package org.neverscroll.app

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Consumes drags before they reach the video feed and replays short taps via the service. */
@SuppressLint("ViewConstructor") // Created only by ScrollGuardService with behavior callbacks.
internal class GuardOverlayView(
    context: Context,
    private val appLabel: String,
    private val exitLabel: String,
    private val onExit: () -> Unit,
    private val onTap: (Float, Float) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val chip = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var blockedRecently = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = resources.getString(R.string.guard_announcement, appLabel, exitLabel)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 14f * density
        val top = 12f * density
        val right = (width - 14f * density).coerceAtLeast(left)
        val bottom = top + 52f * density
        chip.set(left, top, right, bottom)
        paint.color = if (blockedRecently) Color.rgb(113, 50, 36) else Color.rgb(25, 43, 35)
        canvas.drawRoundRect(chip, 16f * density, 16f * density, paint)
        paint.color = Color.WHITE
        paint.textSize = 14f * density
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        canvas.drawText(resources.getString(R.string.guard_title), left + 16f * density, top + 22f * density, paint)
        paint.textSize = 12f * density
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        canvas.drawText(resources.getString(R.string.guard_subtitle, appLabel, exitLabel),
            left + 16f * density, top + 40f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.x - downX) > touchSlop ||
                            abs(event.y - downY) > touchSlop)) {
                    dragging = true
                    blockedRecently = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging || abs(event.x - downX) > touchSlop ||
                    abs(event.y - downY) > touchSlop
                dragging = false
                if (wasDragging) {
                    postDelayed({ blockedRecently = false; invalidate() }, 350)
                } else if (event.eventTime - downAt < 700) {
                    if (chip.contains(event.x, event.y)) performClick()
                    else onTap(event.rawX, event.rawY)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
            else -> return true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        onExit()
        return true
    }
}
