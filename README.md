# Android Dynamic Island

This repository contains a [reference specification](REFERENCE_SPEC.md) and an installable Android APK implementation in progress. The current app draws a bounded `TYPE_APPLICATION_OVERLAY` for confirmed sources. Its source store supports up to three visible activities under a labeled Android approximation policy; this is **not** a measured iPhone Dynamic Island recreation or a replacement for Android's status bar, privacy indicators, notification shade, lock screen, or Live Update chip.

## Build

The project uses JDK 17, Android SDK 36, Gradle 9.7.1 and Android Gradle Plugin 9.1.1. On this Mac mini:

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The app ID is `dev.luinbytes.dynamicisland`, with minimum Android API 30 and target API 36. Pull requests assemble and lint the APK on GitHub Actions.

## Current features

- **App-owned timers:** start multiple independent one-, five-, or ten-minute countdowns; pause, resume, and clear each by its stable ID. Deadlines use elapsed real time and restore after an app process restart. A reboot resumes from the last saved remaining time rather than inferring time while powered off.
- **Island presentation:** choose a source and enable the bounded overlay after granting Android's separate display-over-other-apps access. Tap the pill to expand or collapse it; long-press to stop the overlay. Selection, source lifecycle, and presentation are separate in the state engine. Current geometry, content, and gestures are provisional until native reference capture.
- **Media discovery:** optionally enable Android notification-listener access to read published media sessions. An observed app appears in diagnostics; the user must then allow that package before its session may enter the Island. Listener disconnect or access loss removes private session content. The app does not yet send publisher media commands or admit ordinary notifications as activities.
- **Device diagnostics:** display read-only battery, ringer and torch facts. These facts do not automatically become Island activities or trigger device controls.

The timer repository does not schedule an exact alarm or post a completion notification while the process is stopped. An Android media session is visible only when its publisher exposes it and listener access is connected; lack of an observed session is not proof a publisher task ended. The current overlay runs in the app process and Android may move, cover, or remove it. Stopping the overlay leaves the timer task state intact.

No iPhone 18 Pro Max/iOS 27 native capture, Pixel 11 Pro XL/Android 17 physical qualification, or selected SystemUI build/recovery route is available. Follow the [implementation sequence](docs/verification/09-implementation-acceptance.md), [device qualification](docs/android/18-device-qualification.md), and [paired comparison protocol](docs/verification/12-comparison-protocol.md) before assigning a parity verdict.
