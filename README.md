# Android Dynamic Island prototype

This repository contains the [reference specification](REFERENCE_SPEC.md) and a first installable Android layout probe. The app is **not** a measured iPhone Dynamic Island recreation. It renders one provisional demo pill through `TYPE_APPLICATION_OVERLAY`; it does not read notifications, control other apps, or replace Android's status bar, privacy indicators, lock screen, or Live Update chip.

## Build

The project uses JDK 17, Android SDK 36, Gradle 9.7.1 and Android Gradle Plugin 9.1.1. On this Mac mini, the JDK is installed through Homebrew but is not on the default shell PATH:

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew --no-daemon --max-workers=1 :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The app ID is `dev.luinbytes.dynamicisland`, with minimum Android API 30 and target API 36. These are prototype build settings; the [Pixel Android 17 gate](docs/android/18-device-qualification.md) remains separate.

Pull requests run the same assembly and lint checks on a GitHub hosted Android SDK runner.

## Use

1. Install and open **Island Prototype**. It starts with no overlay and asks for no notification access.
2. Choose **Open overlay settings** and explicitly enable display over other apps for this app. Return to the app; it rechecks the grant.
3. Choose **Show demo**. Tap the demo pill to expand or collapse it. Long-press the pill or choose **Stop** in the app to remove the window.

The window is bounded and requests an offset for the cutout area beyond the status-bar inset. Its actual placement must be inspected on each Android build; the displayed inset values are diagnostics, not measured screen coordinates. Its dimensions, text, timing and gesture behavior are placeholders for device experiments. Android can move, hide or cover an application overlay. The demo is process-local and does not run a foreground service, so Android may remove it when the app process ends. Revoking overlay access and returning to the app also removes it.

No real publisher adapter, notification listener, timer, SystemUI integration, or reference-matched contour is included yet. Follow the [implementation sequence](docs/verification/09-implementation-acceptance.md) and [comparison protocol](docs/verification/12-comparison-protocol.md) before claiming parity.
