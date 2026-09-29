# Dynamic Island recreation: reference and implementation specification

Status: living research specification, 2026-09-29; **1:1 parity unverified**. The provisional iOS target is iPhone 18 Pro Max running **iOS 27.0.1**, Apple's latest listed iOS version on this date. The Android baseline is **stable Android 17 on a stock Pixel 11 Pro XL**, provisionally; no physical target or exact build has been selected. Record both installed builds before capture. Exact dimensions and motion require native reference capture; Android system integration requires target-device qualification. [Apple security releases](https://support.apple.com/en-us/100100), [Android 17](https://developer.android.com/about/versions/17), [Pixel 11 Pro XL specs](https://store.google.com/product/pixel_11_pro_specs?hl=en-US).

The specification is split by topic. Start with the capture matrix for iPhone behavior, then use the Android map and completion ledger to trace each state to a proof requirement.

## iOS reference and capture

1. [iOS reference and evidence](docs/reference/01-ios-reference.md) — target and parity boundary.
   - [Island presentation and geometry](docs/reference/01a-island-presentation.md)
   - [Activity lifecycle and interactions](docs/reference/01b-activity-lifecycle.md)
   - [System and publisher experiences](docs/reference/01c-system-experiences.md)
2. [State capture matrix](docs/reference/02-state-capture.md) — capture protocol, grouped state tables and frame-level measurement template.
   - [Layout and lifecycle states](docs/reference/02a-layout-and-lifecycle-capture.md)
   - [Media and system activities](docs/reference/02b-system-activities-capture.md)
   - [Lock, privacy and controls](docs/reference/02c-system-surfaces-capture.md)
   - [Entry and routing states](docs/reference/02d-entry-and-routing-capture.md)
3. [Reference fixtures](docs/reference/11-reference-fixtures.md) — controlled iPhone and Android publisher scenarios for repeatable capture.
   - [Native capture execution plan](docs/reference/12-capture-execution-plan.md) — session order, prerequisites and evidence gates for all 78 states.

## Visual and interaction design

4. [Design contract](docs/design/04-design-contract.md) — shape, content, motion, input, and Android coexistence.
5. [Interaction contract](docs/design/13-interaction-contract.md) — gesture outcomes, arbitration, cancellation and Android input ownership.
6. [State machine](docs/design/14-state-machine.md) — source lifecycle, presentation transitions, arbitration boundaries and concurrency checks.
7. [Typography and icons](docs/design/17-typography-icons.md) — reference glyph measurement, Android asset provenance and visual verdicts.

## Android integration

8. [Pixel and Android 17 baseline](docs/android/04-pixel-android17-baseline.md) — provisional Pixel 11 Pro XL profile, build freeze and Pixel-specific proof gates.
9. [Android integration architecture](docs/android/05-android-architecture.md) — APK prototype, SystemUI route, and target-device gate.
10. [Android event adapters](docs/android/06-android-events.md) — real signals, publisher contracts, and action limits.
11. [Android permissions](docs/android/07-android-permissions.md) — overlay, notification, runtime grants, and third-party access.
12. [Android surface ownership](docs/android/15-android-surface-ownership.md) — status-bar chip, shade, lock, privacy and input owners with coexistence proof.
13. [Android device qualification](docs/android/18-device-qualification.md) — exact-build physical fit, overlay behavior, SystemUI feasibility and recovery evidence.
14. [Tap destination routing](docs/android/19-destination-routing.md) — source-specific app scenes, action tokens, Android launch limits and handoff proof.
15. [Notification admission](docs/android/20-notification-admission.md) — ordinary notification negative cases and evidence required before a third-party source enters the Island.
16. [Publisher foreground ownership](docs/android/21-foreground-ownership.md) — source-app visibility, competing activities and app-to-Island handoff boundaries.
17. [Screen-recording ownership](docs/android/25-screen-recording-ownership.md) — Android recording callbacks, system chip ownership and R16 proof.
18. [Notification listener visibility](docs/android/27-notification-listener-visibility.md) — filter types, connection lifecycle and profile gates for third-party events.
19. [Permission onboarding](docs/android/40-permission-onboarding.md) — Android Settings navigation, grant checks and return-state proof.
20. [Immersive and cutout coexistence](docs/android/41-immersive-cutout-coexistence.md) — edge-to-edge apps, transient system bars, physical camera fit and top-edge input proof.
21. [Third-party publisher adapters](docs/android/43-third-party-adapter-contract.md) — versioned source identity, media/notification reconciliation, action ownership and negative cases.

## Feature investigations

22. [Search and assistant entry](docs/features/22-search-assistant-entry.md) — Island swipe outcomes, Android provider ownership and shade coexistence.
23. [Local Capture recording](docs/features/23-local-capture.md) — iPhone videoconference recording icon and Android capture/privacy ownership.
24. [File-download activity](docs/features/24-file-download.md) — Safari's documented download Live Activity, iOS 27 verification and Android transfer contracts.
25. [Satellite entry](docs/features/26-satellite-entry.md) — documented iPhone Island route to Connection Assistant and Android satellite ownership.
26. [SharePlay start](docs/features/28-shareplay-start.md) — Apple-documented Island-adjacent sharing message and publisher-dependent Android integration.
27. [Action button start](docs/features/29-action-button-start.md) — user-configured Live Activity start and Android shortcut or OEM-button boundaries.
28. [Rotating content in one activity](docs/features/30-one-activity-rotating-content.md) — stable source identity through publisher-authored event changes.
29. [Interactive Live Activity toggle](docs/features/31-interactive-toggle.md) — optimistic feedback, committed state, lock authentication and Android publisher contract.
30. [Action-button control feedback](docs/features/32-action-button-control-feedback.md) — symbol/value presentation during button hold, source result and target-device input ownership.
31. [AlarmKit alarm lifecycle](docs/features/33-alarmkit-alarm-lifecycle.md) — countdown, pause, alert, snooze, stop and Android alarm ownership.
32. [Push to Talk channel](docs/features/34-push-to-talk-channel.md) — joined-channel indicator, transmission phases and Android publisher/system ownership.
33. [App Clip Live Activity](docs/features/35-app-clip-live-activity.md) — no-full-app start, installation handoff and Android Play Instant boundary.
34. [StandBy night and privacy](docs/features/36-standby-night-privacy.md) — full-screen transition, red Night Mode, sensitive content and Android dream ownership.
35. [Activity hide and restore](docs/features/37-activity-hide-restore.md) — candidate visibility gesture, source continuity and Android chip coexistence.
36. [Local and remote Now Playing](docs/features/38-remote-now-playing.md) — iOS 27 session identity, remote controls and Android media-session boundaries.
37. [Push update ordering](docs/features/39-push-update-ordering.md) — timestamp, priority, stale and post-end behavior across APNs and FCM.
38. [Activity end and dismissal policy](docs/features/42-end-and-dismissal-policy.md) — final content, Island exit and Lock Screen retention across distinct end policies.
39. [Screen capture and sensitive content](docs/features/44-capture-privacy-coexistence.md) — physical versus captured Island content, app-scene signal limits and Android window protection.
40. [Third-party live communication](docs/features/45-live-communication.md) — iOS conversation phases, group controls and Android Telecom ownership.
41. [Shazam recognition](docs/features/46-shazam-recognition.md) — search progress/results, Auto Shazam and Android publisher-notification boundaries.
42. [Apple Sports live game](docs/features/47-apple-sports-live-game.md) — current Island-presence candidate, game lifecycle and Android Live Update boundaries.
43. [Wallet boarding-pass flight](docs/features/48-wallet-flight-activity.md) — Wallet-owned flight tracking, airline-app suppression and Android pass-notification limits.
44. [Wallet event ticket](docs/features/49-wallet-event-ticket.md) — system-started activity versus pass suggestion, with Android event-notification boundaries.
45. [Apple TV sports Live Activity](docs/features/50-apple-tv-sports-live-activity.md) — Apple TV versus Apple Sports publisher identity, current Island presence and Android app evidence.
46. [Live Voicemail](docs/features/51-live-voicemail.md) — Island icon and transcript handoff, call ownership and Android dialer limits.
47. [Hold Assist call handoff](docs/features/52-hold-assist-call-handoff.md) — green call indicator, agent-return alert and native dialer ownership.
48. [MLB live game](docs/features/53-mlb-live-game.md) — documented third-party Island score/inning activity and named Android MLB notification limits.

## Verification and acceptance

49. [Completion ledger](docs/verification/03-completion-ledger.md) — coverage groups and evidence still required for parity verdicts.
50. [Platform proof gates](docs/verification/08-platform-proof-gates.md) — cutout, privacy, media, lifecycle, and accessibility checks.
51. [Implementation and acceptance](docs/verification/09-implementation-acceptance.md) — build sequence, parity verdicts, and current gaps.
52. [State-to-Android map](docs/verification/10-state-to-android-map.md) — one Android signal, owner and proof gate for each iPhone capture row.
53. [Comparison protocol](docs/verification/12-comparison-protocol.md) — paired geometry, content, motion and interaction measurements with frozen tolerances.
54. [Route capability ledger](docs/verification/16-route-capability-ledger.md) — documented APK limits, SystemUI proof paths and unverified parity verdicts for every capture group.
55. [Evidence manifest](docs/verification/18-evidence-manifest.md) — capture bundle identity, raw artifact provenance, source/action traces and five-layer verdict record.

The [capture matrix](docs/reference/02-state-capture.md) and [completion ledger](docs/verification/03-completion-ledger.md) define the evidence needed before any state can be called `matched`. Source-backed limitations remain `unsupported` or `unverified` until tested on the chosen devices.
