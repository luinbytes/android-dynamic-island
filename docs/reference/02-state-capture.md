# State capture matrix

[Specification index](../../REFERENCE_SPEC.md) · [capture execution plan](12-capture-execution-plan.md)

## Capture protocol

Capture the iPhone screen at the highest available recording quality **and** film the display externally: a screen recording may not establish the visible physical camera/sensor boundary or actual display timing. More importantly, iPhone screen recording itself uses a red Island indicator, so external footage is required for an uncontaminated idle state and may be required for activity-priority observations. [Apple screen-recording guide](https://support.apple.com/guide/iphone/take-a-screen-recording-iph52f6e1987/27/ios/27). Apple publishes a 1320 × 2868 portrait display pixel grid for iPhone 18 Pro Max, but that does not establish the saved recording or camera frame dimensions; read each file's actual dimensions before measuring. [iPhone 18 Pro specifications](https://www.apple.com/iphone-18-pro/specs/). Record the exact iOS build, orientation, display zoom, text size, locale, appearance, whether Always-On is active, and active privacy/accessibility settings such as Differentiate Without Color. Use a neutral wallpaper and a second high-contrast background. Capture at least three repeat runs per transition, with an idle lead-in and a settled tail. Store an event timestamp and crop coordinates beside each clip. Do not publish personal notification contents.

## State tables

The R IDs are stable across the Android map and capability ledger. Each state appears once in these tables:

- [Layout, interaction and Live Activity lifecycle](02a-layout-and-lifecycle-capture.md)
- [Media and first-party system activities](02b-system-activities-capture.md)
- [Lock, StandBy, privacy and system controls](02c-system-surfaces-capture.md)
- [Activity entry, search and source admission](02d-entry-and-routing-capture.md)

### Measurement sheet for each captured state

Record: reference build; screenshot or frame ID; trigger time; orientation; published display grid and **actual capture-frame** dimensions; coordinate space and capture-to-display registration; Island bounding box `(x, y, width, height)`; sensor bounding box; corner contour; background and edge colors; content element boxes; font family/weight/size estimate; baseline; icon vector and color; tap/hold/sliding hit regions; entrance/exit frame counts; easing trajectory; frame-to-frame contour and element paths; per-element blur, opacity and scale where content changes; haptic/audio response; destination. Keep **capture pixels**, **registered display pixels**, **screen-relative ratios**, and **physical size estimates** as separate measurements; Android density-independent pixels are not interchangeable with iPhone points. Do not assign an exact iPhone 18 Island width until a native capture supports it.

Use this record for each observation (replace `null` only when evidence exists):

```json
{
  "state_id": "R1",
  "device": "iPhone 18 Pro Max",
  "ios_version": null,
  "ios_build": null,
  "source_url_or_local_capture": null,
  "activity_style": null,
  "activity_state": null,
  "activity_authorization": {"activities_enabled": null, "frequent_pushes_enabled": null},
  "start_path": "foreground_request | scheduled | apns_push_to_start | live_activity_intent | first_party | unknown",
  "source_event_id": null,
  "source_event_time": null,
  "delivery_observed_time": null,
  "publisher_relevance_scores": null,
  "orientation": "portrait",
  "display_settings": {"zoom": null, "text_size": null, "appearance": null, "differentiate_without_color": null, "siri_ai_available": null},
  "system_context": {"locked": null, "always_on_dimmed": null, "standby": null, "focus_mode": null, "muted": null, "camera_active": null, "microphone_active": null, "location_active": null, "screen_recording_active": null},
  "published_display_px_portrait": {"width": 1320, "height": 2868},
  "capture_frame_px": {"width": null, "height": null},
  "bbox_coordinate_space": "capture_frame | registered_display | unknown",
  "capture_to_display_transform": null,
  "registration_residual_px": null,
  "island_bbox_px": null,
  "sensor_bbox_px": null,
  "content_boxes_px": null,
  "element_transitions": null,
  "trigger_frame": null,
  "settled_frame": null,
  "capture_fps": null,
  "gesture_result": null,
  "attention": {"screen_woke": null, "sound_played": null, "haptic_felt": null},
  "provenance": "native_screen_recording | external_camera | Apple_documentation | Apple_marketing_video",
  "confidence": "measured | estimated | unknown"
}
```

For motion, align clips to the event trigger, inspect shape and element position on each frame, and compare duration at the **recorded** frame rate. A 60 fps recording cannot prove behavior at the phone's 120 Hz display rate. External camera footage must be corrected for perspective before using it for geometry.
