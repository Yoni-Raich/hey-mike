# Assistant screen context and text input

## Change

The assistant panel captures the original screen at invocation, delivers its
text before voice audio opens, and accepts typed questions with the same text
and original screenshot. The first insertion/paste ends voice; the panel
stays open and displays the text response. The chat keeps the user's original
question instead of showing the internal context wrapper.

Android screen-sharing switches and protected windows remain respected.
Accessibility supplies visible text only through the device gateway, with no
run arming, node handles or ADB fallback. It can replace AssistStructure text
only for the same app and a permitted, non-null platform screenshot.

## Physical evidence, first candidate

Redmi 12 / Android 15, dev debug build 1125 (`0.14.0-dev.assistant1`), installed
with replacement; app data and the enabled accessibility service survived.
The Xiaomi already selected Mike as its default assistant. Android
`KEYCODE_ASSIST` opened the real VoiceInteractionSession over Chrome. This
tests the session path, not a human's physical long press of the power key.

A synthetic Hebrew post described a free book exchange in the rose garden at
10:30. A canvas separately drew the number 731; it was absent from extracted
screen text. At invocation the app captured 448 characters and a platform
screenshot. Voice reached LISTENING with accessibility still bound. Inserting
the question switched voice to IDLE; log timestamps were 08:41:38.962 for the
switch and 08:41:40.136 for confirmed shutdown (1.174 s including remote
acknowledgement, not a measurement of local audio-stop latency).

The typed question asked for the event, time and number. The model answered
all three, including the image-only 731, directly in the panel. The trace
contains one user turn, one assistant answer and completed status, with **zero
tool calls**. Answer latency from recorded user turn to the assistant message
was 11.767 s. The screenshot attached to the turn was Android's original
capture, not the floating panel. The answer correctly noted that the canvas
label was clipped; no claim is made about its cropped letters.

Ignored evidence: `artifacts/assistant-20261009/`, including `post.png`,
`panel-ready.png`, `typed-before-send.png`, `answer1.png`, per-session traces,
build output, and continuous app/system log capture. Raw captures are not
committed or uploaded.

## Final candidate and UI repeat

Build 1127 (`0.14.0-dev.assistant3`), package `dev.androidagent.app.dev`,
Dev Debug, replaced the previous candidate on the same Redmi 12. Installed
metadata was read back. APK SHA-256:
`dadea9011bb9bef2469de4d7047c6970b88d05b2be4eea18b2bbc2c885a13a7a`.
`apksigner verify --verbose` passed with debug-key v2 signing; `zipalign -c
-P 16 4` passed. The Dev Release variant built unsigned and was not installed.

At 09:13:48.344 the final candidate recorded 385 visible characters and an
original screenshot, before voice became live. The guarded IME probe verified
that the assistant field, rather than Chrome, held the input connection.
The exact typed question was:

> סכם לי את הפוסט שעל המסך. ציין את שעת ההתחלה ואת המספר בתמונה הירוקה.

Voice was LISTENING before insertion and IDLE afterwards; the accessibility
service stayed bound. The switch and confirmed shutdown were logged at
09:14:36.297 and 09:14:37.409: **1.112 s**, including remote acknowledgement.
Local audio is stopped before that wait; its precise latency was not measured.

Gboard opened, the card moved above it, and the complete Hebrew question was
visible with Send enabled and the status **Voice off**. Sending hid the keyboard
and started the normal selected Claude/Sonnet chat. The model summarized the
post and answered **10:30** and image-only **731**. The screenshot number was
absent from the input text. The trace has three events: user, assistant,
completed; **zero tool calls**. User timestamp 1791526522671 to assistant
1791526534222 is **11.551 s**. This measures the recorded turn to full answer,
not first token or screen capture latency.

The reply exceeded the panel's visible answer area. A real swipe scrolled to
the time and image-number paragraphs; Send and bottom controls remained
visible. Open Mike handed off the same session to the main chat, with the
original screenshot and answer. The regular chat renders Markdown, while the
native floating answer view currently shows plain text including Markdown
markers. This is a remaining polish item, not lost answer content.

Final UI evidence, shown to the user and retained locally under
`artifacts/assistant-20261009/`: `ship-panel-ready.png`,
`ship-keyboard-ready.png`, `ship-answer.png`, `ship-answer-scrolled.png`,
`ship-open-mike-ready.png`, `ship-input-evidence.json`, and `ship-trace/`.
The original chat was restored; Gboard remained selected; voice and run state
were idle, with accessibility bound. Only the QA HTTP server and its exact
ADB reverse port were stopped/removed. Existing chats and app data were kept.

An earlier 1126 repeat also succeeded with the normal Claude/Sonnet engine:
385 captured characters, original image, exact typed question, three trace
events and zero tools, 12.100 s answer latency. Its confirmed voice shutdown
took 1.005 s. That run found a four-line answer clipping problem, fixed in
1127 by the bounded ScrollView and rechecked with the swipe above.

## Build, logic and log evidence

Final gate passed in 1m28s:

```powershell
./gradlew.bat test :app:assembleDevDebug :app:assembleDevRelease :app:assembleDevDebugAndroidTest :app:lintDevDebug :voice:lintDebug :a11y:lintDebug '-PversionCodeOverride=1127' '-PversionNameOverride=0.14.0-dev.assistant3' --offline --no-daemon --no-parallel --max-workers=2 --console=plain
python -m unittest tools.test_prepare_runtime tools.e2e_validation.test_helpers tools.e2e_validation.test_latency
git diff --check
```

JUnit XML: **2,321 executions, zero failures/errors, 16 skips**, across 221
suites/variants. Python: **23 passed**. Instrumentation was compiled, not run.
No connected-test uninstall or data clearing was used. New checks cover long
screen text, password/own/hidden-node exclusion, platform-consent and package
matching, visible snapshot preference, trust delimiters, exact displayed
question versus enriched engine prompt, one-time typing transition, and audio
gating on delivery, cancellation and failure.

Completed continuous UID and system-event captures cover **08:38:53–09:08:53**
and **09:13:33–09:17:23**, Jerusalem time. Both manifests report complete,
no early end or reader errors. No FATAL, ANR, native fatal signal or
OutOfMemoryError signature was found. The final crash buffer is empty and
exit-info shows the expected package-update exits for the three installs.
OEM `mi_exception_log`, renderer and frame warnings remain in the evidence;
this is not a claim that every logged warning is fixed or harmless. These
short feature runs do not prove long-term stability or a performance change.

## Driver limitations and remaining checks

One early keyboard-navigation attempt focused Chrome's address editor before
the assistant was ready. It was cancelled without submission and is invalid
as an assistant test. A later guarded attempt reached SPEAKING before typing;
it proves stopping an active voice session, not typing during STARTING.
An extra character caused by a moving keyboard in the 1126 driver was removed
from the QA field before Send; the submitted trace was checked for exact text.

No physical spoken question or hardware power-key long press has yet been
verified. A live voice connection and delivery gate do not prove speech
recognition or a spoken answer. The realtime voice API in the pinned runtime
accepts screen text; screenshot image understanding is proven for typed
questions. Protected/disabled screen-sharing policy is covered in logic
tests; its OEM settings flow still needs physical coverage. Landscape, large
font, a cold/unsigned-in assistant and an early Open Mike before chat selection
need device coverage. At the end of the Xiaomi run, the Nothing hosting the
source chat had not been attached or updated; its later repair is recorded
below. This change is stacked on the previous validation-fixes
branch; no final strong-model review or merge was performed.

Android callback/privacy contract reference:
[VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession).

## Nothing opening-crash follow-up

After the user requested the same build on Nothing, the exact 1127 APK
completed its 329,778,916-byte transfer into phone Downloads. Opening that
content URI launched APK Manager, which displayed its automatic silent-ADB
installation flow. The turn was interrupted before installed package
metadata could be read back. The last confirmed pre-transfer build was
1113 / `0.15.0-adb-access-preserved-test`; the user then reported a crash on
opening. Neither a completed 1127 install nor its crash stack is assumed.

Read-only inspection of the preserved 1113 source found a concrete upgrade
defect: its session database version 6 includes `sessions.is_mike` and the
unique `mike_main_chat` index, but no `composer_drafts`. The validation
branch also used version 6 and expected that table. SQLiteOpenHelper skips
onUpgrade when both versions are 6. Opening a selected chat reads its draft,
so this path can throw `no such table: composer_drafts` immediately.

The new `persistentMikeSchemaSixWithoutDraftTableOpensWithoutLosingHistory`
test created the old schema, history, queue and workspace fixture. Running it
against the original implementation failed with SQLiteException for the
missing table (`nothing-repro-before.log`). Database version 7 now invokes
the existing additive migration. The same test passes and preserves title,
thread, engine, messages, queued work, the old Mike column/index, exact
Unicode/whitespace drafts and the workspace file. A second test checks that
the other version-6 shape retains its existing draft and history.

Build 1128 / `0.14.0-dev.assistant4`, package `dev.androidagent.app.dev`,
Dev Debug, passed the full gate in 1m26s: all unit variants, Dev Debug and
Release builds, androidTest APK compilation, and app/voice/a11y/workspace
lint. XML totals: **2,325 executions, zero failures/errors, 16 skips** in
221 suites/variants. Runtime Python tests: **15 passed**. Diff check,
debug-key v2 signature verification and 16 KiB alignment passed. SHA-256:
`04ca64576fcb19b69971823eaf65a45d2afbe24aafd7f7a5c17c6393ee603515`.
Ignored build/test logs are under `artifacts/assistant-20261009/`.

Before the physical follow-up, Nothing mDNS discovery was visible but the
connection failed. Pc's ADB log identified `SSLV3_ALERT_CERTIFICATE_UNKNOWN`;
the advertised TCP endpoint was reachable. Discovery and enabled Wireless
Debugging did not establish authenticated Pc access. USB then appeared as
unauthorized and was used only after the user approved Android's prompt and
the serial entered `device` state. A separate driver error had emitted an
oversized screenshot-tool reply before the interruption. No causal link
between that reply and the opening crash was established.

## Nothing physical repair and preservation evidence

Nothing A059 / Android 16, primary Android user 0, serial `00152154B002517`.
The pre-install snapshot confirmed version 1127 / `0.14.0-dev.assistant3`,
updated at 09:38:45. SHA-256 of the installed base APK matched the original
1127 artifact exactly (`dadea901…13a7a`). The crash buffer contains repeated
main-thread fatal exceptions at 09:48:47, 09:48:50 and 09:48:56:

```text
android.database.sqlite.SQLiteException: no such table: composer_drafts
while compiling: SELECT text FROM composer_drafts WHERE session=?
LocalSessionStore.composerDraft (LocalSessionStore.kt:158)
```

This confirms the missing-table diagnosis on the actual device, rather than
only in the regression fixture. The previous schema was version 6, without
`composer_drafts`, with 430 sessions, 28,583 messages and an empty run queue.
A private database/preferences archive was taken after a scoped force-stop,
before installation. Raw data stays in ignored local artifacts and was not
committed or uploaded.

`adb -s 00152154B002517 install -r` returned Success for the exact 1128 APK.
Package metadata read back 1128 / `0.14.0-dev.assistant4`, updated at 11:39:18.
The installed base APK SHA-256 matches
`04ca64576fcb19b69971823eaf65a45d2afbe24aafd7f7a5c17c6393ee603515`.
Dex inspection also confirms the packaged SQLiteOpenHelper constructor uses
version 7. MainActivity start returned `Status: ok`, correct component,
WARM launch and 1,102 ms total time. A running app PID was observed; a later
process restart and database read were also captured. A native screenshot
shows the Mike new-chat screen, but no model prompt was sent.

An early post-install private snapshot still showed v6 and was not accepted
as completion evidence. The subsequent live database copy shows v7,
`composer_drafts`, and the preserved `mike_main_chat` index. Comparing original
rows by primary key confirms **430/430 chats and 28,583/28,583 messages**,
with zero missing, changed or added rows in those tables; the empty queue is
unchanged. SQLite TEXT was compared as bytes to avoid imposing UTF-8
normalization on legacy Android data. No database reset or uninstall occurred.

The UID/system-event capture completed normally from **11:37:53–11:47:53**
Jerusalem time: `complete:true`, no early termination, reader error or stderr.
It has no new FATAL, ANR, native-signal or OutOfMemoryError signature. Expected
process/update activity and a skipped-frame warning remain recorded; this
short opening/upgrade run does not establish general performance or stability.

USB disconnected before the extra controlled cold reopen and saved-history
UI navigation. The second capture attempt refused dispatch because the
selected serial was no longer in `device` state. Those extra checks are not
passes. Dev's accessibility service is not enabled: the enabled Hey Mike
component belongs to prod. Android's selected assistant belongs to Dev.
Manual Dev accessibility setup and a live connection are still needed for
device-tool/model and floating-panel repeats on Nothing.

Evidence root: `artifacts/assistant-20261009/nothing-usb-1128-20261009/`,
including `before-install/`, private backups, `installation.json`,
`live/log-capture-status.json`, `context-check.json`,
`live-database-check.json` and `data-preservation-final.json`.
