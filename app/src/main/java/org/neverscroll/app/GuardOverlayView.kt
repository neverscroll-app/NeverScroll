package org.neverscroll.app

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs

/** Consumes drags before they reach the video feed and replays short taps via the service. */
@SuppressLint("ViewConstructor") // Created only by ScrollGuardService with behavior callbacks.
internal class GuardOverlayView(
    context: Context,
    private val appLabel: String,
    private val exitLabel: String,
    private val interceptBack: Boolean,
    private val onExit: () -> Unit,
    private val onTap: (Float, Float) -> Unit,
    private val onSeek: (Float, Float, Float, Float) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val chip = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L
    private var dragging = false
    private var blockedRecently = false
    private var backHandler: BackHandler? = null
    var seekBounds: Bounds? = null

    init {
        isClickable = true
        if (interceptBack) {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = resources.getString(R.string.guard_announcement, appLabel, exitLabel)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (interceptBack) {
            requestFocus()
            if (Build.VERSION.SDK_INT >= 33) {
                backHandler = BackHandler(this, onExit)
            }
        }
    }

    override fun onDetachedFromWindow() {
        backHandler?.close()
        backHandler = null
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (interceptBack && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onExit()
            return true
        }
        return super.dispatchKeyEvent(event)
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
                downRawX = event.rawX
                downRawY = event.rawY
                downAt = event.eventTime
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.x - downX) > touchSlop ||
                            abs(event.y - downY) > touchSlop)) {
                    dragging = true
                }
                if (dragging && !blockedRecently &&
                    !SeekTargetDetector.allowsHorizontalSwipe(seekBounds,
                        downRawX, downRawY, event.rawX, event.rawY, touchSlop)) {
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
                    if (SeekTargetDetector.allowsHorizontalSwipe(seekBounds,
                            downRawX, downRawY, event.rawX, event.rawY, touchSlop)) {
                        onSeek(downRawX, downRawY, event.rawX, event.rawY)
                    }
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

    @SuppressLint("NewApi") // Instantiated only behind the API 33 check above.
    private class BackHandler(view: View, onExit: () -> Unit) {
        private val dispatcher = view.findOnBackInvokedDispatcher()
        private val callback = OnBackInvokedCallback { onExit() }

        init {
            dispatcher?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        }

        fun close() {
            dispatcher?.unregisterOnBackInvokedCallback(callback)
        }
    }
}
