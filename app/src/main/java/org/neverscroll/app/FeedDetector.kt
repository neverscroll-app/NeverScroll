package org.neverscroll.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

internal enum class ProtectedApp(val label: String, val preferenceKey: String) {
    YOUTUBE("YouTube Shorts", "youtube_enabled"),
    YOUTUBE_REVANCED("YouTube ReVanced", "youtube_revanced_enabled"),
    INSTAGRAM("Instagram Reels", "instagram_enabled"),
    TIKTOK("TikTok", "tiktok_enabled");

    companion object {
        fun fromPackage(packageName: String): ProtectedApp? = when (packageName) {
            "com.google.android.youtube" -> YOUTUBE
            "app.revanced.android.youtube" -> YOUTUBE_REVANCED
            "com.instagram.android" -> INSTAGRAM
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> TIKTOK
            else -> null
        }
    }
}

internal enum class VideoAction { LIKE, COMMENT, SHARE }

internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    companion object {
        fun from(rect: Rect) = Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/** A bounded, transient summary of the active accessibility tree. Nothing is persisted. */
internal data class ScreenSignals(
    val ids: Set<String>,
    val labels: Set<String>,
    val selectedLabels: Set<String> = emptySet(),
    val rightRailActions: Set<VideoAction> = emptySet(),
) {
    fun idContains(term: String) = ids.any { term in it }
    fun labelContains(term: String) = labels.any { term in it }
    fun selectedLabelContains(term: String) = selectedLabels.any { term in it }
}

internal object FeedDetector {
    private val engagement = listOf("like", "comment", "share", "нравится", "комментар", "поделиться")

    fun isShortFeed(app: ProtectedApp, signals: ScreenSignals): Boolean {
        val hasEngagement = engagement.any(signals::labelContains)
        return when (app) {
            ProtectedApp.YOUTUBE, ProtectedApp.YOUTUBE_REVANCED ->
                signals.idContains("reel_watch") ||
                    signals.idContains("shorts_player") ||
                    (signals.rightRailActions.size >= 2 &&
                        (signals.labelContains("shorts") ||
                            signals.idContains("reel_player_page_container")))

            ProtectedApp.INSTAGRAM ->
                signals.idContains("reel_viewer") ||
                    signals.idContains("clips_viewer") ||
                    (hasEngagement && signals.selectedLabelContains("reels")) ||
                    signals.rightRailActions.size >= 2

            ProtectedApp.TIKTOK ->
                (signals.labelContains("for you") || signals.labelContains("для вас") ||
                    signals.idContains("feed_video")) && hasEngagement ||
                    signals.rightRailActions.size >= 2
        }
    }

    /** Full-screen short-video controls form a vertical rail, unlike post actions. */
    fun rightRailAction(screen: Bounds, control: Bounds, label: String): VideoAction? {
        if (screen.width <= 0 || screen.height <= screen.width ||
            control.width <= 0 || control.height <= 0 ||
            control.width > screen.width * 0.34 ||
            control.centerX < screen.left + screen.width * 0.70 ||
            control.centerY < screen.top + screen.height * 0.18 ||
            control.centerY > screen.top + screen.height * 0.91) return null

        return when {
            ("like" in label && "dislike" !in label) || "нравится" in label -> VideoAction.LIKE
            "comment" in label || "комментар" in label -> VideoAction.COMMENT
            "share" in label || "поделиться" in label -> VideoAction.SHARE
            else -> null
        }
    }

    /** At least two different actions must line up vertically, not in a post's action row. */
    fun verticalRailActions(screen: Bounds, controls: List<Pair<Bounds, String>>): Set<VideoAction> {
        val candidates = controls.mapNotNull { (bounds, label) ->
            rightRailAction(screen, bounds, label.lowercase(Locale.ROOT))?.let { it to bounds }
        }
        val actions = mutableSetOf<VideoAction>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val (firstAction, firstBounds) = candidates[i]
                val (secondAction, secondBounds) = candidates[j]
                val dx = abs(firstBounds.centerX - secondBounds.centerX)
                val dy = abs(firstBounds.centerY - secondBounds.centerY)
                if (firstAction != secondAction && dx <= screen.width * 0.12 &&
                    dy > maxOf(dx, firstBounds.height, secondBounds.height) &&
                    dy <= screen.height * 0.35) {
                    actions.add(firstAction)
                    actions.add(secondAction)
                }
            }
        }
        return actions
    }

    fun collect(root: AccessibilityNodeInfo): ScreenSignals {
        val ids = mutableSetOf<String>()
        val labels = mutableSetOf<String>()
        val selectedLabels = mutableSetOf<String>()
        val actionControls = mutableListOf<Pair<Bounds, String>>()
        val rootRect = Rect().also(root::getBoundsInScreen)
        val screen = Bounds.from(rootRect)
        val nodeRect = Rect()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 300) {
            val node = pending.removeFirst()
            if (!node.isVisibleToUser) continue
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val description = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (id.isNotEmpty()) ids.add(id)
            if (text.isNotEmpty()) labels.add(text)
            if (description.isNotEmpty()) labels.add(description)
            node.getBoundsInScreen(nodeRect)
            actionControls.add(Bounds.from(nodeRect) to "$id $text $description")
            if (node.isSelected) {
                if (text.isNotEmpty()) selectedLabels.add(text)
                if (description.isNotEmpty()) selectedLabels.add(description)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(pending::add)
            }
        }
        return ScreenSignals(ids, labels, selectedLabels,
            verticalRailActions(screen, actionControls))
    }
}
