package org.neverscroll.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/** Finds only the playback scrubber, never an arbitrary horizontal control. */
internal object SeekTargetDetector {
    fun matches(app: ProtectedApp, id: String, className: String, bounds: Bounds,
                screen: Bounds): Boolean {
        if (screen.width <= 0 || screen.height <= 0 ||
            bounds.width < screen.width * 0.65 || bounds.height <= 0 ||
            bounds.height > screen.height * 0.12 ||
            bounds.top < screen.top + screen.height * 0.75) return false

        return when (app) {
            ProtectedApp.YOUTUBE, ProtectedApp.YOUTUBE_REVANCED ->
                className == "android.widget.SeekBar"
            ProtectedApp.INSTAGRAM -> id.endsWith(":id/clips_expanded_touch_view")
            ProtectedApp.TIKTOK -> id.endsWith(":id/video_seek_bar")
        }
    }

    fun find(root: AccessibilityNodeInfo, app: ProtectedApp): Bounds? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val screen = Bounds.from(rect)
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 300) {
            val node = pending.removeFirst()
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(rect)
            val bounds = Bounds.from(rect)
            if (matches(app, node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty(),
                    node.className?.toString().orEmpty(), bounds, screen)) return bounds
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
        }
        return null
    }

    fun allowsHorizontalSwipe(target: Bounds?, startX: Float, startY: Float,
                              endX: Float, endY: Float, touchSlop: Int): Boolean {
        if (target == null || !contains(target, startX, startY) ||
            !contains(target, endX, endY)) return false
        val dx = abs(endX - startX)
        val dy = abs(endY - startY)
        return dx > touchSlop && dx > dy * 2 && dy <= touchSlop * 2
    }

    private fun contains(bounds: Bounds, x: Float, y: Float) =
        x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
}
