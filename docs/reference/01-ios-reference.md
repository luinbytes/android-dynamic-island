# iOS reference and evidence

[Specification index](../../REFERENCE_SPEC.md)

## Reference target

- Provisional reference: **iPhone 18 Pro Max, iOS 27.0.1**. Apple lists 27.0.1 as its latest iOS version on 2026-09-29, with a release date of 2026-09-28, and lists the iPhone 18 Pro Max as available from 2026-09-18. The phone's exact installed build identifier is still unknown. Recheck Apple's current release before the first capture, then freeze one installed version/build for the primary reference package; if a later update is tested, give it a separate package and verdict rather than mixing its frames with 27.0.1 runs. An update listing does not prove Island pixels or behavior stayed identical. [Apple security releases](https://support.apple.com/en-us/100100), [iPhone 18 Pro announcement](https://www.apple.com/newsroom/2026/09/apple-debuts-iphone-18-pro-and-iphone-18-pro-max/).

Apple publishes the iPhone 18 Pro Max display size and some Dynamic Island behavior, but not the exact Island contour or all current state transitions. The source-backed findings are grouped below; the [capture matrix](02-state-capture.md) defines the measurements still owed.

- [Island presentation and geometry](01a-island-presentation.md) — display facts, layout regions, shape, animation guidance, Lock Screen and StandBy, and launch-video limits.
- [Activity lifecycle and interactions](01b-activity-lifecycle.md) — gestures, concurrent activities, starts, updates, controls and termination.
- [System and publisher experiences](01c-system-experiences.md) — Apple and third-party features, calls, media, privacy, accessories and system entry points.

## What “one-to-one” means here

The visual target is one chosen iPhone and iOS build, for explicitly captured states. Each supported state should match its reference in silhouette, placement, typography, content hierarchy, gesture result, and transition. Android must show **real** state for supported events, not a permanently looping demonstration.

A normal Android app cannot own the iOS system layer. `TYPE_APPLICATION_OVERLAY` appears over app windows but below critical system windows such as the status bar and IME. A native Android SystemUI implementation can instead participate in Android's own status-bar, notification, keyguard and Always-On surfaces, but requires integration with a particular device's OS build; it still cannot receive Apple's private events. Lock-screen, biometric, privacy, call, and payment behavior must be evaluated individually; visual simulation is not evidence of functional parity. [Android window types](https://developer.android.com/reference/android/view/WindowManager.LayoutParams), [AOSP SystemUI architecture](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/README.md).
