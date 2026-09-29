# Capture evidence bundle and verdict record

[Specification index](../../REFERENCE_SPEC.md) · [capture plan](../reference/12-capture-execution-plan.md) · [comparison protocol](12-comparison-protocol.md)

Use one manifest per **R ID, source scenario, iPhone build, Android build and implementation route**. Different publishers, OS builds, or APK/SystemUI routes require separate records; they cannot inherit a prior `matched` result. The manifest links raw captures, source/action logs, measurements and review decisions. It is a record format for future captures, not evidence that a capture has happened.

## Bundle layout and identity

Keep a stable bundle ID such as `C02_R5_publisherA_ios27build_androidbuild_APK_01`. Store full-frame raw files without edits; store perspective-corrected frames, masks, crops, redactions and annotations as **derived** artifacts pointing to a raw parent. Hash each artifact with SHA-256 after acquisition and record the actual file dimensions and frame rate from the file, not the advertised display resolution. Keep the trigger log's event ID and clock domain; record any measured clock offset before comparing iPhone and Android latency. A filename or wall-clock timestamp alone does not prove synchronization.

Store the manifest beside a local evidence directory or in a private review store. Use relative artifact paths. Exclude personal notification text, contact identifiers and account tokens from public manifests; retain necessary unredacted originals only in an access-controlled evidence store. A redacted review copy must retain a link to its raw parent and cannot replace it for a pixel verdict.

## Record shape

The fields below are required even when their value is `null` or an empty list. `null` means **not observed or not measured**, never zero or a negative result. The `state_id` must exist in the [capture matrix](../reference/02-state-capture.md); `session_id` must be its assigned C session. `route` is `APK` or the exact named SystemUI build route. `source_scenario` identifies the publisher, version, trigger and conditional capability set without private data.

```json
{
  "schema_version": 1,
  "bundle_id": null,
  "session_id": null,
  "state_id": null,
  "source_references": [],
  "source_scenario": {"publisher": null, "version": null, "trigger": null, "capabilities": []},
  "iphone": {"model": "iPhone 18 Pro Max", "ios_version": null, "ios_build": null, "settings_record": null},
  "android": {"model": null, "build_fingerprint": null, "settings_record": null, "route": null, "implementation_revision": null},
  "runs": {"iphone": [], "android": []},
  "artifacts": [],
  "alignment": {"coordinate_space": null, "transform_artifact": null, "residual_px": null, "clock_offset_ms": null, "clock_offset_uncertainty_ms": null},
  "tolerances": {"frozen_before_android_tuning": false, "frozen_at": null, "rationale_artifact": null, "values_artifact": null},
  "measurements_artifact": null,
  "source_and_action_result_artifact": null,
  "negative_or_interference_cases": [],
  "layer_verdicts": {"visual": "unverified", "motion": "unverified", "interaction": "unverified", "source_fidelity": "unverified", "system_coexistence": "unverified"},
  "overall_verdict": "unverified",
  "review": {"reviewer": null, "reviewed_at": null, "notes": [], "open_gaps": []}
}
```

Each entry in `runs.iphone` or `runs.android` needs a run ID, confirmed trigger event ID and timestamp/clock domain, observed source ID and state, capture artifact IDs, first visible and settled frames, action request/result IDs if a control was used, and whether the expected surface was observed. Use `observed`, `not_observed`, `unavailable` or `unverified` for `surface_result`. For a negative result, include the reproducible trigger, observation interval and the competing native surfaces inspected. `not_observed` is valid only after that interval; `unavailable` or an unconfirmed trigger leaves the case `unverified`. Do not substitute three frames from one run for three independent repetitions.

```json
{
  "run_entry": {"run_id": null, "trigger_event_id": null, "trigger_time": null, "clock_domain": null, "source_id": null, "source_state": null, "capture_artifact_ids": [], "first_visible_frame": null, "settled_frame": null, "surface_result": "unverified", "observation_interval_ms": null, "inspected_native_surfaces": [], "action_request_id": null, "action_result_id": null},
  "artifact_entry": {"artifact_id": null, "relative_path": null, "sha256": null, "kind": null, "acquired_at": null, "actual_width_px": null, "actual_height_px": null, "actual_fps": null, "parent_artifact_id": null}
}
```

Replace these example entry shapes with actual objects in the `runs` and `artifacts` arrays. Use `source_references` for the primary documentation or native-reference links that justify expecting this state; a citation never substitutes for a run.

Each `artifacts` entry needs a unique ID, relative path, SHA-256, kind (`raw_external`, `raw_screen`, `source_log`, `measurement`, `derived_frame`, `mask`, `review_copy` or another explicit kind), acquisition time, actual dimensions/frame rate when applicable, and `parent_artifact_id` for derived items. The measurement artifact uses the [state measurement sheet](../reference/02-state-capture.md#measurement-sheet-for-each-captured-state) and the [five-layer comparison](12-comparison-protocol.md). Record the native Android chip, shade, lock/AOD and privacy output as applicable, alongside the replica; a replica-only crop cannot prove system coexistence.

## Verdict gate

Before `matched`, confirm at least three independent iPhone runs for the measured transition, matching Android runs on the qualified build, frozen layer-specific tolerances, a reproducible source/action result, and evidence for every applicable layer. Mark an inapplicable layer with a written reason in `review.notes`; never treat missing evidence as inapplicable. `approximate` records a measured difference, `unsupported` records a proven route limit on the selected build, and `unverified` covers missing or insufficient evidence. The overall verdict follows the weakest required layer. Reopen the record when the publisher version, OS build, implementation revision or capability contract changes.

Until devices and captures exist, every new manifest remains a template with `unverified` verdicts. The [device qualification record](../android/18-device-qualification.md) is a prerequisite for target-specific Android route claims.
