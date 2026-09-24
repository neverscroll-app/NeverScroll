# NeverScroll — instructions for coding agents

## Product contract

The user opens one short video sent from a messenger, watches it, and returns. When a supported short-video feed is visible, block drag gestures from the first video onward. Preserve short taps where possible and provide an always-visible exit action. TikTok's Back can navigate to another video, so its exit action opens Recents for choosing the sending app; other feeds use Back. Protection is opt-in through Android accessibility settings and can be disabled per app.

An optional compatibility switch per app intentionally protects every screen in that app when automatic feed recognition misses a direct link. Keep it off by default and describe its wider scope in the UI and disclosure. Treat YouTube ReVanced as a separate app with its own switch; test it using a direct Shorts link because its feed Shorts entry may be disabled.

## Implementation map

- `FeedDetector.kt` contains package allowlisting and feed recognition. Keep recognition conservative: a false positive blocks normal app navigation. Use both viewer IDs and right-side video controls when a direct link lacks a feed title. Add fixture-style unit tests for each recognition rule.
- `ScrollGuardService.kt` reads the active accessibility tree, manages the overlay, invokes accessible button clicks, and replays taps when needed. Keep all UI inspection local and transient.
- `GuardOverlayView.kt` consumes drags. Changes to touch handling must preserve a way to leave the feed.
- `MainActivity.kt` explains access and exposes settings. Update the disclosure when data access or gesture behavior changes.
- Keep user-facing text in `res/values/strings.xml` (English) and `res/values-ru/strings.xml` (Russian), including accessibility service and overlay text. Check both languages when changing either screen.

## Verification

Run `./gradlew testDebugUnitTest assembleDebug`. For changes to detection or gestures, also test on a physical device with current YouTube, YouTube ReVanced, Instagram, and TikTok versions where installed. For TikTok, verify that the exit chip opens Recents and reopening TikTok leaves the same video; its system Back may navigate between videos. Record unsupported UI versions and observed false positives in `README.md`; automated tests cannot certify third-party accessibility trees.

The user's latest requirement (block the next scroll immediately) takes precedence over the older chat's idea of counting several videos before a break.
