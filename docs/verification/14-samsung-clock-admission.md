# Samsung Clock timer admission probe

Status: **withheld** for the app's external-source feed. This is an Android 16/Samsung prototype observation, not a general notification contract.

## Scope and method

On 2026-09-29, Samsung SM-S906E (`RZCT81C29ND`, build `S906EXXSDGZB6`) ran `com.sec.android.app.clockpackage` version `12.5.07.13` (`versionCode=1250713100`). A temporary debug-only probe in Island's real `NotificationListenerService` recorded **only Samsung Clock** notification identity, channel, flags, ranking, action labels/intent presence and extra names/types. It did not log other packages or any extra values. The probe was removed from source after the run, and listener access was revoked.

Two disposable Clock timers were started with Android's `SET_TIMER` intent at 20 and 25 minutes. The 25-minute timer was paused and deleted through Clock; the 20-minute timer was then deleted. No timer was allowed to expire. The phone remained in Do Not Disturb (`zen_mode=2`).

## Observations

| Transition | Listener evidence |
| --- | --- |
| First timer starts | A group summary at notification ID `2147483610` and a child at ID `0` arrived on `notification_channel_timer`. The child was ongoing, `visibility=PUBLIC`, with non-null `Cancel` and `Pause` action intents. Ranking reported importance 3 and no lockscreen override. |
| Second timer starts | Clock removed the old child ID `0` and group summary with removal reason `24`, then republished the summary and **two** children at IDs `0` and `1`. The newly selected 25-minute timer occupied ID `0`; the earlier 20-minute timer appeared at ID `1`. Both children offered `Cancel` and `Pause`. |
| Selected timer pauses | Child ID `0` updated in place to `Cancel` and `Resume`, both with non-null intents. Clock's own screen showed the 25-minute timer paused. |
| Selected timer is deleted | Child ID `0` was removed. The older timer remained at ID `1`; later deletion removed ID `1` and the summary. No Clock timer notification remained. |

The child records used `group=TIMER_GROUP_KEY`, ongoing flag, a Samsung `android.ongoingActivityNoti.*` extra schema and an `android.ongoingActivityNoti.secondaryInfo` string indicating duration and running/paused display state. The group summary was private and had no actions. These are proprietary, version-specific fields; the observed flags did not show a system-set promoted-ongoing flag.

## Admission decision

`StatusBarNotification.key` identifies a notification record, **not a durable timer task** here: child ID `0` was first the sole 20-minute timer, then was removed and reused for the newly selected 25-minute timer while the earlier task moved to ID `1`. Neither title nor group key disambiguates those tasks. The listener therefore must not admit these notifications as stable multiple Clock timer sources or bind a card to an action across that reshuffle. This probe also did not dispatch a delivered PendingIntent, verify locked-screen privacy, or observe natural completion. A one-active-timer-only adapter remains a possible narrow follow-up after those action and teardown checks; this run does not qualify it.

The app still admits only its own timers and exact-token media sessions. Ordinary and unqualified third-party notifications stay out of the Island feed.
