package org.neverscroll.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

class ScrollGuardService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlay: GuardOverlayView? = null
    private var seekBottomShield: View? = null
    private var replayingGesture = false
    private var evaluationQueued = false
    private var missingCheckQueued = false
    private var exitInProgress = false
    private var activeApp: ProtectedApp? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardSettings.preferences(this).registerOnSharedPreferenceChangeListener(this)
        evaluateCurrentWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || replayingGesture) return
        // System windows can emit events while a video remains active. The active
        // window, rather than the event source package, decides overlay lifetime.
        if (event.packageName?.toString() == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            evaluateCurrentWindow()
            return
        }
        if (!evaluationQueued) {
            evaluationQueued = true
            handler.postDelayed({
                evaluationQueued = false
                evaluateCurrentWindow()
            }, 40)
        }
    }

    private fun evaluateCurrentWindow(confirmMissing: Boolean = false) {
        if (replayingGesture) return
        if (!GuardSettings.enabled(this)) {
            removeOverlay()
            return
        }
        val root = currentRoot() ?: run {
            if (!deferRemoval(confirmMissing)) removeOverlay()
            return
        }
        val app = ProtectedApp.fromPackage(root.packageName?.toString().orEmpty())
        if (app == null || !GuardSettings.appEnabled(this, app)) {
            removeOverlay()
            return
        }
        if (!GuardSettings.compatibilityEnabled(this, app) &&
            !FeedDetector.isShortFeed(app, FeedDetector.collect(root))) {
            if (activeApp != app || !deferRemoval(confirmMissing)) removeOverlay()
            return
        }
        val seekBounds = SeekTargetDetector.find(root, app)
        if (overlay != null && activeApp == app) {
            overlay?.seekBounds = seekBounds
            return
        }
        removeOverlay()
        val uiContext = AppLanguage.localizedContext(this)
        val view = GuardOverlayView(uiContext, app.label,
            exitLabel = uiContext.getString(
                if (app == ProtectedApp.TIKTOK) R.string.exit_recents else R.string.exit_back),
            interceptBack = app == ProtectedApp.TIKTOK,
            onExit = { exitFeed(app) },
            onTap = ::forwardTap,
            onSeek = ::replaySeek)
        view.seekBounds = seekBounds
        val (displayHeight, topInset, bottomInset) = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
            Triple(metrics.bounds.height(), bars.top, bars.bottom)
        } else {
            val displaySize = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            Triple(displaySize.y, legacyBarHeight("status_bar_height"),
                legacyBarHeight("navigation_bar_height"))
        }
        val overlayHeight = (displayHeight - topInset - bottomInset).coerceAtLeast(1)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            if (app == ProtectedApp.TIKTOK) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP
        params.y = topInset
        try {
            windowManager.addView(view, params)
            overlay = view
            activeApp = app
        } catch (_: WindowManager.BadTokenException) {
            activeApp = null
        }
    }

    private fun currentRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != packageName ||
            activeApp != ProtectedApp.TIKTOK || overlay?.hasWindowFocus() != true) return root
        // The focusable TikTok guard can become the active accessibility window.
        // Inspect the still-visible app below it, never the guard's own tree.
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { ProtectedApp.fromPackage(it.packageName?.toString().orEmpty()) ==
                ProtectedApp.TIKTOK } ?: root
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK ||
            activeApp != ProtectedApp.TIKTOK || overlay?.hasWindowFocus() != true) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) exitFeed(ProtectedApp.TIKTOK)
        return true
    }

    private fun deferRemoval(confirmMissing: Boolean): Boolean {
        if (confirmMissing || overlay == null) return false
        if (!missingCheckQueued) {
            missingCheckQueued = true
            handler.postDelayed({
                missingCheckQueued = false
                evaluateCurrentWindow(confirmMissing = true)
            }, 250)
        }
        return true
    }

    private fun legacyBarHeight(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun exitFeed(app: ProtectedApp) {
        if (exitInProgress) return
        exitInProgress = true
        if (app == ProtectedApp.TIKTOK) {
            // TikTok treats Back as navigation to another video. Recents lets
            // the user choose the sending app without advancing the feed.
            if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed({ exitInProgress = false }, 450)
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            // A direct ReVanced link returns to its home screen on the first Back.
            // Leave that screen too, so the user returns to the sending app.
            if (app == ProtectedApp.YOUTUBE_REVANCED &&
                ProtectedApp.fromPackage(currentRoot()?.packageName?.toString().orEmpty()) == app) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            exitInProgress = false
        }, 450)
    }

    private fun forwardTap(screenX: Float, screenY: Float) {
        // Accessibility clicks keep the guard in place. Synthetic taps are only
        // needed for video surfaces and controls without an exposed click action.
        val root = currentRoot()
        if (root != null) {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            val bounds = Rect()
            pending.add(root)
            var candidate: AccessibilityNodeInfo? = null
            var candidateArea = Long.MAX_VALUE
            var visited = 0
            while (pending.isNotEmpty() && visited++ < 300) {
                val node = pending.removeFirst()
                node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser && node.isClickable &&
                    bounds.contains(screenX.toInt(), screenY.toInt())) {
                    val area = bounds.width().toLong() * bounds.height()
                    // Several apps wrap a real button in a clickable container
                    // with identical bounds. Prefer the deeper node on a tie.
                    if (area > 0 && area <= candidateArea) {
                        candidate = node
                        candidateArea = area
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
            }
            try {
                if (candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            } catch (_: IllegalStateException) {
                // A changing target tree can invalidate a node between lookup and click.
            }
        }
        replayTap(screenX, screenY)
    }

    private fun replayTap(screenX: Float, screenY: Float) {
        replayGesture(screenX, screenY, screenX, screenY, 50)
    }

    private fun replaySeek(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (replayingGesture) return
        val view = overlay ?: return
        val bounds = view.seekBounds ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val fullHeight = params.height
        val bottom = params.y + fullHeight
        val guardedHeight = (bounds.top - params.y).coerceIn(1, fullHeight)
        if (guardedHeight == fullHeight) return

        // Keep the visible guard and exit chip over the feed. Only the seek
        // control is exposed to the injected gesture, never the video above it.
        val shieldHeight = (bottom - bounds.bottom).coerceAtLeast(0)
        if (shieldHeight > 0) {
            val shield = View(this).apply {
                isClickable = true
                setOnTouchListener { _, _ -> true }
            }
            val shieldParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                shieldHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                y = bounds.bottom
            }
            try {
                windowManager.addView(shield, shieldParams)
                seekBottomShield = shield
            } catch (_: WindowManager.BadTokenException) {
                return
            }
        }
        try {
            params.height = guardedHeight
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            params.height = fullHeight
            removeSeekBottomShield()
            return
        }
        replayingGesture = true
        handler.postDelayed({
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            val accepted = dispatchGesture(gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) =
                        finishSeekReplay(view, fullHeight)
                    override fun onCancelled(gestureDescription: GestureDescription?) =
                        finishSeekReplay(view, fullHeight)
                }, handler)
            if (!accepted) finishSeekReplay(view, fullHeight)
        }, 16)
    }

    private fun finishSeekReplay(view: GuardOverlayView, fullHeight: Int) {
        handler.postDelayed({
            if (overlay === view) {
                val params = view.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.height = fullHeight
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: IllegalArgumentException) {
                        // The display or activity may have changed during replay.
                    }
                }
            }
            removeSeekBottomShield()
            replayingGesture = false
            evaluateCurrentWindow()
        }, 16)
    }

    private fun removeSeekBottomShield() {
        seekBottomShield?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: IllegalArgumentException) {
                // The window may have been removed during a display change.
            }
        }
        seekBottomShield = null
    }

    private fun replayGesture(startX: Float, startY: Float, endX: Float, endY: Float,
                              durationMs: Long) {
        if (replayingGesture) return
        replayingGesture = true
        removeOverlay()
        handler.postDelayed({
            val path = Path().apply {
                moveTo(startX, startY)
                if (startX != endX || startY != endY) lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = dispatchGesture(gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finishReplay()
                    override fun onCancelled(gestureDescription: GestureDescription?) = finishReplay()
                }, handler)
            if (!accepted) finishReplay()
        }, 32)
    }

    private fun finishReplay() {
        handler.postDelayed({
            replayingGesture = false
            evaluateCurrentWindow()
        }, 80)
    }

    private fun removeOverlay() {
        removeSeekBottomShield()
        overlay?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: IllegalArgumentException) {
                // The window may already have been removed during a display change.
            }
        }
        overlay = null
        activeApp = null
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (AppLanguage.isLanguagePreference(key)) removeOverlay()
        evaluateCurrentWindow()
    }

    override fun onInterrupt() {
        removeOverlay()
        handler.postDelayed({ evaluateCurrentWindow() }, 200)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeOverlay()
        evaluateCurrentWindow()
    }

    override fun onDestroy() {
        GuardSettings.preferences(this).unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }
}
