# Interactive Live Activity toggle

[Specification index](../../REFERENCE_SPEC.md) · [capture matrix](../reference/02-state-capture.md) · [interaction contract](../design/13-interaction-contract.md)

Apple documents intent-backed `Button` **and** `Toggle` controls in expanded Dynamic Island and Lock Screen Live Activity presentations. The documented `Toggle` can change appearance optimistically while its app intent runs, so visible on/off is not proof that the underlying task accepted the change. Apple also says controls on a locked device are inactive until authentication and unlock. This is a distinct R18a path from R18's momentary button action. The documentation does not establish the exact iPhone 18 Pro Max/iOS 27 toggle animation, hit region, lock prompt or rollback timing; measure them on the reference phone. [Apple interactive Live Activities](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities).

## Reference fixture and capture

- Place one `Toggle(isOn:intent:label:)` in a controlled expanded Live Activity and its Lock Screen view. Give it a unique source ID, intended Boolean value, app-intent invocation ID, committed source value and publisher update timestamp. Keep a separate momentary button so the two interactions cannot be confused.
- Run accepted, delayed, rejected and timed-out changes. Capture the immediate visual state, actual `perform()` result, subsequent ActivityKit update and any rollback. Tap twice rapidly and end the activity while the first intent is pending; record whether a late result can affect a new or ended activity.
- Repeat while locked, then authenticate and cancel authentication in separate trials. Record whether a tap invokes the intent, prompts unlock, delays execution, or leaves the control inactive. Compare Lock Screen and expanded-Island behavior separately.
- Record VoiceOver focus, spoken on/off value and activation for both states. A visual switch position alone does not prove an accessible or committed action.

## Android contract

For an **app-owned** task, model the control as `{source ID, action ID, committed Boolean, requested Boolean, pending invocation ID, error}`. The publisher owns the real command and must publish the resulting committed state. The renderer may show a measured optimistic visual only while tracking that pending invocation; it must rollback on rejection, timeout, source end or permission loss. A later callback with an old invocation ID cannot mutate the replacement source. Match the iPhone's exact visual timing only after R18a capture.

A third-party notification's `Notification.Action` exposes a title, optional semantic action and a `PendingIntent`; that alone does **not** declare a Boolean toggle, its current value or its eventual result. Show a toggle only for a named publisher/version that explicitly publishes those fields and a safe action contract. Otherwise keep the actual action as a button or omit it. The native Android notification and its lock-screen action remain separate system surfaces. [Android Notification.Action](https://developer.android.com/reference/android/app/Notification.Action), [notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

For this app's own Android notification action, API 31+ `setAuthenticationRequired(true)` asks the OS to unlock before sending the action's `PendingIntent`. When false, the OS still decides whether unlock is needed. That API does not make a replica overlay's touch target safe on keyguard or grant cross-app unlock control. In a selected SystemUI build, inspect keyguard action dispatch and actual user/profile redaction before connecting any Island control. Verify canceled unlock, successful unlock and post-unlock source state on the target phone. [Android action authentication](https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean)).

**Parity verdict:** `unverified` until the same controlled task is captured on both phones, including optimistic frame, committed/rejected result, lock gate, accessibility and teardown. App-owned Boolean commands are a direct Android implementation candidate. A generic third-party `Notification.Action` is publisher-dependent and does not prove toggle parity.
