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
need device coverage. The Nothing hosting the source chat was not attached
and was not updated. This change is stacked on the previous validation-fixes
branch; no final strong-model review or merge was performed.

Android callback/privacy contract reference:
[VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession).
