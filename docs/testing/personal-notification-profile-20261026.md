# Personal notification capture — 2026-10-26

The reported work notification reached the task board on tasks1170. The
existing task `rule` saves agent guidance; it does not filter notification
ingestion. Source inspection found only package/conversation exclusions, no
supported profile setting or tool API. Blocking Outlook/Teams packages would
also block their personal instances.

## Android boundary

`StatusBarNotification.getUser()` (API 21) identifies the user for whom the
notification is intended. The new policy accepts only equality with the
process user, never a package/UID heuristic, notification-key parser, hardcoded
owner/work user number or cross-user lookup. Thus a work/private profile is
rejected even when its package is identical to a personal package. Special
handles such as USER_ALL, missing handles and metadata/service failures are
rejected. No rejected payload, title, extras, text or key is read or logged.

On the project's minimum API 30, `UserManager.isManagedProfile` also refuses
collection if Mike itself runs in a managed profile. API 33+ additionally
checks `isProfile`, which covers other profile types. A normal secondary
personal user is supported; it need not be user 0. The policy needs no new
permission, hidden API or profile enumeration.

Android can deliver work notifications to a personal-profile listener; an IT
admin can control that delivery through
`DevicePolicyManager.setPermittedCrossProfileNotificationListeners`. Mike is
not the profile owner, so that API is not a usable self-service setting.
Android's notification-type filters do not express this user boundary.
The system still delivers notification objects; Mike rejects other users
before inspecting their payloads or passing them to any consumer.

Sources: [StatusBarNotification.getUser](https://developer.android.com/reference/android/service/notification/StatusBarNotification#getUser()),
[notification listener behavior](https://developer.android.com/reference/android/service/notification/NotificationListenerService),
[UserManager profile methods](https://developer.android.com/reference/android/os/UserManager#isManagedProfile()),
[profile-owner notification policy](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPermittedCrossProfileNotificationListeners(android.content.ComponentName,%20java.util.List%3Cjava.lang.String%3E)).

## Coverage and limits

The policy runs before the payload read in `onNotificationPosted` and before
each active notification in `onListenerConnected`. Both the automation event
path and task inbox are downstream. `TaskNotificationReader.messages` repeats
the policy so direct/future snapshot callers cannot bypass it. Reconnect
continues to collect only task signals, never replay automation actions.
The separate startup backfill reads Mike chats, not Android notifications.

Regression tests use synthetic StatusBarNotifications on Robolectric API 30
and 35. Rejected notifications have a payload getter that throws if accessed.
Tests exercise live callbacks, reconnect replay followed by a valid personal
message, direct reader access, special/missing/unreadable users, unavailable
UserManager, a listener in a managed/other profile and a secondary personal user.
No personal notification content was read to investigate this change.

This blocks new capture only. Previously captured signals/cards are retained
as requested; stored signals have no profile field, so their profile cannot
be safely inferred later. Pending pre-update work may still run. Physical
Android delivery and real managed-profile smoke tests are separate evidence
from Robolectric, compilation, packaging and installation.

## Source and deployment

The isolated branch `fix/personal-profile-notifications` starts from
`origin/dev` at `053cc14a`. That branch lacks the installed task-board feature.
Local baseline commit `bd25170` is a byte-verified snapshot of the existing 60
changed/untracked source files from the tasks1170 worktree. The original
checkout was not edited. The focused privacy commit is separate from that
baseline; do not merge the snapshot as an independent feature implementation.

Build/test results and the APK location are recorded in `PROGRESS.md`.
No push, merge, phone install, data clearing or notification-access
change is part of this source-only delivery. The current tasks1170 phone is
not protected by a local source change; it needs the resulting update.

## Validation result

Windows/JDK 21; task-specific Gradle home and central-build config with cleanup
of other buckets disabled (`removed:[]`). Commands used `--no-daemon
--max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`,
`ANDROID_HOME=C:\Users\SHIRA\AppData\Local\Android\Sdk`, and the task's
`storage.gradle` init script. No `local.properties` was created.

| Check | Result |
| --- | --- |
| `:automations:testDebugUnitTest` | PASS, 19 cases on API 30/35, no failures/errors/skips |
| `:core:test` | PASS, 839 cases, no failures/errors/skips |
| `:app:testDevDebugUnitTest` | PASS, 170 cases, no failures/errors/skips |
| `:app:assembleDevDebug` | PASS, cached runtime inputs verified against pins |
| `:app:lintDevDebug` | PASS, 0 errors, 21 warnings, 3 informational findings |
| Extra `:automations:lintDebug` | FAIL, 3 errors in unchanged code, described below |
| `git diff --check` | PASS |

The extra standalone library lint reports missing `POST_NOTIFICATIONS` at
`AndroidAutomationActions.kt:145` and `:233`, and `ACCESS_NETWORK_STATE` at
`AutomationWifi.kt:66`. Those files and the module manifest are unchanged
from the snapshot. Both permissions are declared in the app manifest; the
required app lint passes. The failure is retained explicitly; no lint
baseline, suppression, abort-on-error change or permission change was made.
The first test run exposed a synthetic-fixture constructor calling its
overridden payload getter. The sentinel now arms after construction; final
tests verify that Mike never calls it for rejected notifications.

The final app/core build gate passed in 3m30s. Combined final test XML totals
are 1,028 tests, zero failures/errors/skips. This is not a full release gate
or physical-device evidence. No tests were installed on a phone.

APK: `C:\Users\SHIRA\AppData\Local\Temp\hey-mike\personal-profile-notifications-20261026\app-dev-debug.apk`
(339,760,022 bytes). Package `dev.androidagent.app.dev`, version code 1171,
name `0.14.0-dev.personal1171`, minimum API 30, target 35; ARM64 and x86_64.
The source `version.properties` remains tasks1170; the build used
`-PversionCodeOverride=1171 -PversionNameOverride=0.14.0-dev.personal1171`.

SHA-256: `3ca184098d41b851f90b0e341ca6af2b47d7f8a12e149970820d2c7a71e09436`.
`apksigner verify --verbose --print-certs`: PASS, Android Debug certificate,
v2 signature, not v3/release signing. Certificate SHA-256:
`f65c3469483d5b059543c7a67d369578bf3627a02795d77fc76a244444dda1ee`.
It matches the local task-worktree APK; the phone's installed certificate was
not read. `zipalign -c -P 16 4`: PASS. Assembly exported the exact signed APK
as `app-dev-debug.apk` to the existing delta-transfer host cache.

For an authorized phone update, use `copy_file` followed by `install_apk`,
then independently verify installed version/base-APK hash and synthetic
personal/work notifications. Installation requires matching package/signing
identity and a version code higher than the currently installed build; if
1171 is already used, rebuild with a fresh higher code. Never uninstall or
clear data to resolve a refusal. Transfer/installation alone would not prove
the runtime privacy behavior.

Kept for local review (no push authorized):

- Worktree `C:\Users\SHIRA\.codex\worktrees\personal-profile-notifications-20261026`.
- Branch `fix/personal-profile-notifications`, with local source snapshot
  `bd25170` followed by the focused privacy fix.
- Build bucket `C:\Users\SHIRA\Documents\AI\Android-agent-use\build\031650f34452e280`.
- APK, logs, baseline source hashes, task Gradle home/config and focused patch
  under `C:\Users\SHIRA\AppData\Local\Temp\hey-mike\personal-profile-notifications-20261026`.

The 60 original task-worktree source files were rehashed after the build;
all still match the captured baseline. Existing user data was not deleted.
