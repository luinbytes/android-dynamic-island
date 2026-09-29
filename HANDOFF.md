# One-time research handoff

**Reader action:** Read this file once, then delete `HANDOFF.md` from the checkout and commit that deletion before further publication. The durable specification is indexed in [REFERENCE_SPEC.md](REFERENCE_SPEC.md); do not move these notes into another handoff file.

## State at handoff (2026-09-29)

- The active objective is a source-backed, state-by-state specification for an exact iPhone 18 Pro Max / iOS 27 Dynamic Island recreation with Android integration. **1:1 parity is unverified.** Do not convert documented API support or a lookalike render into a measured verdict.
- The provisional iOS reference is iPhone 18 Pro Max on iOS 27.0.1. The provisional Android profile is stock Pixel 11 Pro XL on stable Android 17. Freeze each phone's actual build before comparing. Secondary Pixel 11 and Pixel 11 Pro profiles, and a separate Fold profile, are described in [the Pixel baseline](docs/android/04-pixel-android17-baseline.md).
- The index currently covers 78 capture IDs across 11 native-capture sessions, each mapped to an Android signal/owner and route-capability ledger. Design, lifecycle, permissions, overlay and SystemUI paths, third-party publisher limits, and five-layer acceptance are split into topic files.
- No reference iPhone footage exists in this workspace. `adb devices -l` returned no attached Android device on 2026-09-29. No physical Pixel fit, exact Android build, source/build/signing/recovery path, app, or runtime parity has been proved.

## Next evidence-driven work

1. Continue checking current Apple and Android/Pixel primary sources for missing iOS 27 Island states or Pixel system-surface conflicts. A pending research lead: [Google's Pixel 11 Pro HiLight description](https://blog.google/products-and-platforms/devices/pixel/pixel-11-pro-hilight/) documents face-down call and Gemini light cues on Pixel 11 Pro XL. If included, treat them as Pixel native coexistence behavior, **not** an on-screen Island parity state. Another lead: [Android accessibility overlays](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY) belong to a connected accessibility service; do not assume they transfer status-bar, privacy-indicator or keyguard ownership to an ordinary replica APK.
2. Obtain native external footage on the exact iPhone build using [the capture execution plan](docs/reference/12-capture-execution-plan.md). Record real sensor masks, typography, motion, touch, source IDs, and first-party or publisher action results. Keep positive and negative observations separate.
3. Qualify a physical Pixel 11 Pro XL and exact stable Android 17 build through [the device gate](docs/android/18-device-qualification.md), including camera aperture, `DisplayCutout`, status bar, shade, Live Update chip, permissions, security windows, lock/AOD, and Pixel app versions. Keep the stock APK and build-specific SystemUI routes distinct.
4. Pair each R-state using [the comparison protocol](docs/verification/12-comparison-protocol.md) and [evidence manifest](docs/verification/18-evidence-manifest.md). Award `matched` only when visual, motion, interaction, source/action, and system-coexistence evidence all pass on real devices; otherwise record `approximate`, `unsupported`, or `unverified` as appropriate.

## Current delivery facts

This workspace was locally initialized as Git on 2026-09-29. The user selected a **public** `luinbytes/android-dynamic-island` GitHub repository as the publishing destination. The requester explicitly asked to commit and publish this specification and to delete this handoff after it is read. Check the current branch, remote, and commit before making further changes; this paragraph records intent at handoff creation, not proof of publication.
