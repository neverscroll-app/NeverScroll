# NeverScroll

NeverScroll is an Android app for a familiar situation: someone sends you a short video, you watch it, and you want to return to the messenger before an instinctive swipe pulls you into the feed. On a recognized YouTube Shorts (including YouTube ReVanced), Instagram Reels, or TikTok screen, it blocks drags **from the first video**. A banner at the top provides an exit action. Short taps are passed to the original app where possible, so you can pause the video or open comments.

## Install

NeverScroll requires Android 8.0 or later. Download the signed APK from the [latest release](https://github.com/neverscroll-app/NeverScroll/releases/latest). To build a local development APK, use JDK 17 and Android SDK 36:

```sh
./gradlew assembleDebug
```

Install the downloaded APK (or your local `app/build/outputs/apk/debug/app-debug.apk`), open NeverScroll, read the access disclosure, and enable **NeverScroll — Feed protection** in Android accessibility settings. The home screen lets you turn protection on or off globally and separately for YouTube, YouTube ReVanced, Instagram, and TikTok. If a video is not recognized, you can enable compatibility mode for that app; this blocks swipes on **every** screen in the app, including its regular feed and messages. NeverScroll requires neither an account nor an internet connection.

The interface is available in English and Russian. Tap the language switch in the upper-right corner of the home screen to change between them. On Android 13 or later, Android's per-app language settings also work. On Android 8–12, the app remembers the in-app choice; without a choice it follows the device language. English is the fallback for other device languages.

## How protection works

1. The accessibility service inspects the visible screen only in supported apps.
2. The detector looks for short-video viewer IDs and the names and positions of video controls. On a recognized screen, a transparent overlay consumes drag gestures.
3. For a short tap, the service first tries the target button's accessibility click action. Otherwise, it briefly removes the overlay and replays the tap with Android's Gesture API. In TikTok, the top banner opens Recent apps so you can choose the messenger. In other apps it performs system Back; in ReVanced it repeats Back if the first step remains inside ReVanced. The overlay disappears when you leave the feed.

NeverScroll does not take screenshots, store interface labels, keep viewing history, use analytics, or send data anywhere. Settings are stored locally.

## Limitations

- Detection depends on the accessibility trees exposed by YouTube, YouTube ReVanced, Instagram, and TikTok. App updates, localization, and incomplete trees can cause missed feeds or false positives. The supported ReVanced package is `app.revanced.android.youtube`; other package names need another rule. Check the behavior on your device before relying on it.
- Compatibility mode helps when a directly opened video is missed, but deliberately blocks scrolling throughout the selected app. You can turn it off on the NeverScroll home screen.
- While the overlay is visible, it blocks every drag. In the tested app versions, it disappears while comments are open and returns after closing them. In other versions it may remain over comments; the exit banner remains available.
- In the tested TikTok version, system Back could switch to another video. The banner therefore opens Recent apps. If the device does not support Recent apps, it goes to the Android home screen.
- When a target button has no accessibility click action, the service replays a synthetic tap. The target app may delay or reject it. The overlay is briefly removed for that tap, so an extremely quick gesture could pass through. Hardware buttons, voice commands, and other accessibility services can also change videos independently of NeverScroll.
- An ordinary Android app cannot guarantee recognition of every future version of another app's feed. NeverScroll adds friction to an instinctive swipe; it is not an unbreakable device restriction.

## Development and verification

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

`FeedDetector.kt` holds recognition rules; `ScrollGuardService.kt` observes the active window and manages `GuardOverlayView.kt`; `MainActivity.kt` presents settings and the access disclosure. UI strings live in `res/values/strings.xml` (English) and `res/values-ru/strings.xml` (Russian). See `AGENTS.md` for maintenance guidance.

On a physical Android 16 phone, YouTube 21.03.36, YouTube ReVanced 21.16.256, Instagram 448.0.0.52.84, and TikTok 47.0.3 were checked. Vertical swipes on Shorts, Reels, and TikTok videos did not change the video; short taps opened comments, and protection returned after leaving comments. ReVanced Shorts was opened through a direct link because Shorts was disabled in its feed. The link was opened from Telegram, and the transition was repeated with Android `ACTION_VIEW` while Telegram was open; one tap on the NeverScroll banner returned to Telegram. The normal YouTube and Instagram home screens remained scrollable. After discovering that TikTok Back changed videos, the banner was checked separately: it opened Recent apps twice, and TikTok still showed the same video after reentry. Exit behavior was rechecked in ReVanced and Instagram. Regular YouTube was disabled on the phone during that final cycle, so its result comes from the earlier check. YouTube Shorts and compatibility mode were also checked on an Android 16 emulator.

For version 0.2.0, the English and Russian home screens and protection banner were checked on an Android 16 emulator using Android's per-app language setting and an open YouTube Short. The physical phone was disconnected during this localization check; gesture behavior on current third-party app versions still needs a fresh device check. No false positives were observed on the app versions listed above. Other versions need their own verification.

For version 0.2.1, the top-right language switch was checked in both directions on an Android 16 emulator using the signed release APK. The home screen and protection banner changed between English and Russian. A protected YouTube Short stayed on the same video after a vertical swipe. The Android 8–12 in-app language path was not tested on a device in this cycle.

To test manually, open a short-video link from a messenger, swipe vertically, try a short tap, and use the top banner to leave. In TikTok, choose the messenger from Recent apps. Check ordinary scrolling in the messenger and repeat for each enabled app. If a feed is missed, inspect its current accessibility tree and add a narrow detection rule with a fixture test.

## Publishing

From version 0.2.1, release APKs use a dedicated NeverScroll signing key. The private keystore and `release.properties` are stored outside the repository at `~/.config/neverscroll/`; back up both files securely, because future updates must use the same key. With those files available, run `./gradlew assembleRelease` to build the signed APK. A different signing file can be supplied with `-PneverscrollSigningFile=/path/to/release.properties`.

Version 0.2.0 was signed with a development key. Android cannot install the newly signed APK over that build. Uninstall 0.2.0 first, then install 0.2.1 and enable the accessibility service again.

NeverScroll uses `AccessibilityService` to control short-video feeds; it does not claim to be an accessibility tool for people with disabilities. Publishing on Google Play requires an Accessibility API declaration. The app already provides an explicit disclosure and consent step. See the [Google Play policy](https://support.google.com/googleplay/android-developer/answer/10964491) and [Android documentation](https://developer.android.com/guide/topics/ui/accessibility/service).
