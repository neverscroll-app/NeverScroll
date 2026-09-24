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
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class ScrollGuardService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlay: GuardOverlayView? = null
    private var replayingTap = false
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
        if (event == null || replayingTap) return
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
        if (replayingTap || !GuardSettings.enabled(this)) {
            removeOverlay()
            return
        }
        val root = rootInActiveWindow ?: run {
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
        if (overlay != null && activeApp == app) return
        removeOverlay()
        val uiContext = AppLanguage.localizedContext(this)
        val view = GuardOverlayView(uiContext, app.label,
            exitLabel = uiContext.getString(
                if (app == ProtectedApp.TIKTOK) R.string.exit_recents else R.string.exit_back),
            onExit = { exitFeed(app) },
            onTap = ::forwardTap)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
                ProtectedApp.fromPackage(rootInActiveWindow?.packageName?.toString().orEmpty()) == app) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            exitInProgress = false
        }, 450)
    }

    private fun forwardTap(screenX: Float, screenY: Float) {
        // Accessibility clicks keep the guard in place. Synthetic taps are only
        // needed for video surfaces and controls without an exposed click action.
        val root = rootInActiveWindow
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
        if (replayingTap) return
        replayingTap = true
        removeOverlay()
        handler.postDelayed({
            val path = Path().apply { moveTo(screenX, screenY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
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
            replayingTap = false
            evaluateCurrentWindow()
        }, 80)
    }

    private fun removeOverlay() {
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
