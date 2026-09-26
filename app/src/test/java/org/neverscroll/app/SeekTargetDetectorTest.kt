package org.neverscroll.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekTargetDetectorTest {
    private val screen = Bounds(0, 141, 1272, 2582)

    @Test fun youtubeSeekBarAtBottomIsASeekTarget() {
        val bar = Bounds(0, 2510, 1272, 2652)
        assertTrue(SeekTargetDetector.matches(ProtectedApp.YOUTUBE, "",
            "android.widget.SeekBar", bar, screen))
        assertTrue(SeekTargetDetector.matches(ProtectedApp.YOUTUBE_REVANCED, "",
            "android.widget.SeekBar", bar, screen))
        assertFalse(SeekTargetDetector.matches(ProtectedApp.YOUTUBE, "",
            "android.widget.SeekBar", Bounds(0, 1000, 1272, 1142), screen))
    }

    @Test fun instagramAndTikTokUseTheirOwnBottomControls() {
        assertTrue(SeekTargetDetector.matches(ProtectedApp.INSTAGRAM,
            "com.instagram.android:id/clips_expanded_touch_view", "android.view.View",
            Bounds(0, 2493, 1272, 2611), screen))
        assertTrue(SeekTargetDetector.matches(ProtectedApp.TIKTOK,
            "com.zhiliaoapp.musically:id/video_seek_bar", "android.widget.LinearLayout",
            Bounds(0, 2534, 1272, 2617), screen))
        assertFalse(SeekTargetDetector.matches(ProtectedApp.TIKTOK,
            "com.zhiliaoapp.musically:id/video_seek_bar", "android.widget.LinearLayout",
            Bounds(0, 500, 1272, 583), screen))
    }

    @Test fun onlyHorizontalSwipesOnTheControlAreReplayed() {
        val bar = Bounds(0, 2510, 1272, 2652)
        assertTrue(SeekTargetDetector.allowsHorizontalSwipe(bar, 200f, 2545f,
            1000f, 2545f, 12))
        assertFalse(SeekTargetDetector.allowsHorizontalSwipe(bar, 200f, 2000f,
            1000f, 2000f, 12))
        assertFalse(SeekTargetDetector.allowsHorizontalSwipe(bar, 200f, 2545f,
            210f, 2580f, 12))
        assertFalse(SeekTargetDetector.allowsHorizontalSwipe(bar, 200f, 2545f,
            1000f, 2600f, 12))
        assertFalse(SeekTargetDetector.allowsHorizontalSwipe(bar, 200f, 2545f,
            210f, 2545f, 12))
    }
}
