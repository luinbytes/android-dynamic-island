# Android target-device qualification

[Specification index](../../REFERENCE_SPEC.md) · [architecture](05-android-architecture.md) · [comparison protocol](../verification/12-comparison-protocol.md)

This gate applies to **one named Android phone and exact OS build**. The [provisional primary profile](04-pixel-android17-baseline.md) is a stock Pixel 11 Pro XL on stable Android 17; no physical unit or exact build has been selected, and `adb devices -l` showed no connected phone on 2026-09-28. This gate determines whether that target can support a physically convincing Island and which implementation route can be tested. It is a prerequisite to paired tuning, not a 1:1 verdict.

## Evidence to collect first

| Gate | Record | Pass condition | If it fails or is unknown |
| --- | --- | --- | --- |
| Target identity | Model, hardware variant, build fingerprint, Android/API level, display settings, current OS update channel, intended test ownership. | All captures and source inspection refer to the **same** hardware/build. | Leave device qualification `unverified`; do not transfer a result from another variant or OS build. |
| APK installation and access gate | Installer/source for the test APK; on a locally installed Android 17 APK, system App Info Restricted Settings result, then separate overlay and listener grants, listener connection and visible drawing. [Onboarding contract](40-permission-onboarding.md) | The selected install path can actually reach both requested grants and the listener connection without a policy bypass. | Keep APK access `unverified` or `unsupported` for that installation path as observed; a blocked toggle is not evidence that the notification source is empty. [Android 17 compatibility definition](https://source.android.com/docs/compatibility/17/android-17-cdd) |
| Physical fit | Externally filmed sensor silhouette and screen edges in portrait and landscape; Android `DisplayCutout` bounds/safe insets, status-bar insets, active display geometry, density and any display-size override. Compare with the iPhone idle R0 and expanded R2 masks using [the paired protocol](../verification/12-comparison-protocol.md). | The required opaque Island contour can cover the real camera region without exposing sensor pixels or swallowing reference content, and can be placed within the **frozen** reference tolerance in every claimed orientation. | If the physical hole lies outside every contour allowed by the measured iPhone state, record target-physical parity `unsupported` for that state. If either phone's mask or tolerance is missing, record `unverified`. Never solve a sensor mismatch by hiding it in a screenshot. |
| Native top edge | Actual clock, connectivity, privacy indicator, cutout fill, notification icons/chips, shade gesture and touch targets in idle, expanded, fullscreen, landscape, **one-handed mode**, lock and AOD states. | One renderer has room and input ownership without covering or duplicating a required Android system control. | Record each conflict. An app overlay cannot claim it replaced a status-bar owner merely because its own view drew successfully. |
| APK prototype | On the exact build, grant overlay access and film `TYPE_APPLICATION_OVERLAY` position, clipping, visibility, touch and teardown across app, shade, permission dialog, keyguard, immersive app and rotation. For R4/R5/R5b, verify taps **between detached shapes** reach the underlying app while taps on each shape invoke the correct Island control. Revoke access and verify cleanup. [Gap-touch gate](../design/13-interaction-contract.md) | Only the **APK prototype route** is qualified for the states it visibly survives; its controls remain bounded and the system stays usable. | Mark the failed APK states `approximate`, `unsupported` or `unverified` based on observed evidence. Android documents application overlays below critical system windows and allows the system to change their position, size or visibility. [Android window type](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY) |
| System layer | Obtain source or an OEM-supported integration path for the **exact build**, identify the effective SystemUI/status-bar, shade, keyguard, privacy and screen-decoration owners, and document build/signing/boot/recovery feasibility. | The team can make and recover a target-specific build, and can prove a single SystemUI renderer can coordinate those owners. This qualifies the route for implementation; runtime parity remains unverified. | If there is no viable supported route, platform-layer parity is `unsupported` **on that target**. If source or recovery is not yet checked, it is `unverified`, not impossible. A resource overlay alone only changes existing values. [AOSP SystemUI](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/README.md), [AOSP RRO](https://source.android.com/docs/core/runtime/rros) |
| Safe recovery | Record whether the phone is disposable for OS experiments, backup and restoration path, OEM unlock policy, verified-boot/build requirements and a known-good image. Keep the first pass read-only. | A proposed system build has a documented recovery path and separately authorized data-impacting steps. | Do not unlock, flash or replace the phone's OS during qualification. Android documents that bootloader state transitions wipe data partitions. [AOSP device state](https://source.android.com/docs/security/features/verifiedboot/device-state) |

The Android cutout API provides safe insets and cutout bounding boxes, while the external image establishes the **visible physical sensor boundary**. Record both. Check window and screen coordinates separately; immersive mode and letterboxing can change their relationship. A simulated cutout is useful for layout development but cannot qualify a real sensor. [Android cutout guidance](https://developer.android.com/develop/ui/views/layout/display-cutout)

## Qualification sequence

1. Freeze the iPhone build and R0/R2 reference evidence, including sensor masks, orientation, capture uncertainty and tolerances. Until then, only inventory Android geometry; do not award a physical-fit pass.
2. Identify the candidate Android phone/build without changing accounts, boot state or OS. Capture its hardware edge and insets, then compare geometry in physical and normalized coordinates. Do not assume dp equals iPhone points.
3. Run the APK prototype only as a route probe. Film real window stacking and touch through system transitions; inspect a permission dialog as well as ordinary app content. An emulator or screen recording alone cannot establish the physical cutout or all system-window behavior.
4. If full system integration remains the goal, inspect that build's actual SystemUI source and recovery options. Trace status-bar and cutout ownership, then decide whether an implementation experiment is viable. Do not assume an AOSP class name is present in an OEM build.
5. Save a route decision and the original capture IDs. For every claimed capture row, continue through the [surface ownership contract](15-android-surface-ownership.md), [state-to-Android map](../verification/10-state-to-android-map.md) and [paired comparison protocol](../verification/12-comparison-protocol.md).

## Qualification record

Keep a record for each hardware/build pair; a major OS update creates a new record. Store evidence links outside this template, not private notification contents or device identifiers beyond what is needed to reproduce the build.

```json
{
  "android_model": null,
  "hardware_variant": null,
  "build_fingerprint": null,
  "api_level": null,
  "apk_install_source": null,
  "restricted_settings_evidence": [],
  "overlay_grant_evidence": [],
  "listener_grant_connection_evidence": [],
  "apk_access_gate": "unverified",
  "iphone_reference_version": null,
  "iphone_reference_build": null,
  "reference_capture_ids": [],
  "physical_cutout_evidence": [],
  "android_insets_evidence": [],
  "apk_overlay_evidence": [],
  "systemui_source_revision": null,
  "systemui_owner_trace": [],
  "recovery_plan_evidence": [],
  "physical_fit": "unverified",
  "apk_route": "unverified",
  "systemui_route": "unverified",
  "overall_device_qualification": "unverified",
  "notes": []
}
```

Use `qualified`, `unsupported` or `unverified` for each route gate; `physical_fit` may also be `approximate` when a measured geometry mismatch remains. A qualified device only permits implementation and paired testing. The [per-state verdict](../verification/12-comparison-protocol.md) remains `unverified` until real iPhone and Android results pass every required layer.
