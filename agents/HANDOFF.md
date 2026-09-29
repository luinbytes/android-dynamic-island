# Development handoff

Updated 2026-09-29. This is an agent working note; recheck Git, PR, device and clock state before relying on it.

- Work on `feature/android-app-scaffold` in Lu's personal `luinbytes/android-dynamic-island` repository. PR #1 targets `spec/ios27-android17-pixel`. Do not merge or release without explicit authority.
- The current APK has an app-owned source engine, bounded overlay, multi-timer repository, media-session discovery and token-bound transport dispatch. Timer completion scheduling, notifications, reboot receiver and direct timer destination are in progress on this branch.
- Build with JDK 17 and `./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:lintDebug`; run only one heavy build at a time. Do not add or modify tests under the user's supplied global instructions unless explicitly requested.
- Physical prototype phone: Samsung SM-S906E, ADB serial `RZCT81C29ND`, Android API 36. Target every ADB command with `-s RZCT81C29ND`. The device's Do Not Disturb setting is `zen_mode=2`; preserve it and leave the display off after QA. Revoke temporary permissions and clear app-owned QA timers.
- See [Samsung evidence](../docs/verification/13-samsung-prototype-evidence.md) for observed results and open device checks. No iPhone native capture, Pixel physical qualification, selected SystemUI source or safe recovery route is present. Do not describe public-API overlay results as platform-layer or 1:1 parity.
- The user approved autonomous implementation, pushes, screenshots and QA until 10:00 BST on 2026-09-29. Check Europe/London time regularly and finish the authorized PR path without merging.
