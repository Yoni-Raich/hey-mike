# Voice child update timing — issue #129

Implemented on `fix/voice-child-update-timing-129`, based on dev
`189852e8dcd28fc463d9fd26811b41641c9006da` (merged PR #128).
Issue: https://github.com/Yoni-Raich/hey-mike/issues/129.
No other worktree was edited. No new agent was started.

## Behavior

Child context is received in full during user and Mike speech. Context receipt
does not request speech. A separate call-local queue reconstructs assistant
append/replacement revisions and keeps the latest useful complete change per
child. It waits for finalized turns, a 1.5-second quiet interval and an
8-second minimum between automatic summaries. A sustained microphone signal
holds the user turn until its finalized transcript; a short pause within a
sentence cannot release it. Mike's transcript and playback also hold it.

Pause/Resume child updates controls automatic announcements independently of
microphone mute, ending Voice or cancelling children. Pausing silences an
in-flight child response locally and keeps new context available. Resume uses
the newest pending changes, including an interrupted result. An explicit
status request consumes the pending summary but keeps automatic speech paused.
Call/thread epochs and Stop fences guard delivery; uncertain speech outcomes
are not automatically retried. Ending the call clears call-local state.

Technical activity and streaming fragments remain inspectable but never form
announcements. Bare no-change messages and this exact coordinator diagnostic
are also excluded from speech, even across different run IDs:

> The run ended without a final reply. Check the activity details for the actions that completed.

The coordinator's stored message and full quoted context are unchanged.
Failures and requests for user input still have meaningful status/gate data.

## Final checks

Windows, JDK 21.0.12, installed Android SDK; Java/Kotlin target 17.
An isolated Gradle user home reuses the local caches without loading the
machine's unrelated build-cleanup init scripts.

```powershell
.\gradlew.bat test assembleDevRelease assembleDevDebug assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug --gradle-user-home C:\Users\SHIRA\.codex\tmp\gradle-voice-129-20261011 --no-daemon --no-parallel --max-workers=2
python -m unittest tools.test_prepare_runtime
git diff --check
```

- Final Gradle run: **BUILD SUCCESSFUL**, 2m13s, 1,168 tasks (236 executed,
  932 up-to-date). No source edits were made during or after this final run.
  A SHA-256 manifest for all 12 changed Kotlin files matched after the build.
  An earlier build saw an old core jar after a helper rename during compilation;
  that run is not used as final evidence.
- XML across all variants: **2,506 executions**, zero failures/errors,
  16 skips. These are executions, not unique tests.
- `VoiceChildUpdatesTest`: **24 passed**, zero skips/failures/errors.
- Runtime preparation Python suite: **15 passed**. Its error output comes
  from expected negative fixtures; the suite result is OK.
- App and voice lint passed; no baseline or weakened check was added.
- Dev Debug, unsigned Dev Release and Dev Debug AndroidTest APKs built.
  Version source stays 1133 / `0.14.0-dev.subagents5`; no release was made.
- The full gate includes compilation of the new Compose Pause/Resume fixture.
  It was not executed and no UI screenshot was produced.

## Regression coverage

| Issue behavior | Evidence |
|---|---|
| Receive updates during both speakers' turns | Batch-delivery regression receives 30 fragments and two results while speaking; announcement waits through the next user turn. |
| A speech pause does not end a turn | Sustained input/transcript and Mike-turn tests hold across 5-second pauses; microphone mute can release an unfinalized capture latch. |
| Group bursts and preserve full results | Latest complete revision per child; append/replacement reconstruction; long Unicode packet reassembly and full result tail. |
| Quiet, resume and explicit status | Current response suppression, interrupted-result resume, newest pending result, direct status while paused, no repeated old revisions. |
| Replies without assistant deltas | Audio-only and TranscriptDone-only regressions restore playback at the real user's finalized turn. A quiet command keeps it suppressed. |
| No technical/no-content speech loop | Streaming/activity filtering, bare no-change revisions and ten repeated coordinator diagnostics with different run IDs. Diagnostic remains in full context. |
| User gates, Stop and unknown outcomes | Waiting question remains pending; revoked fences are pruned; packet Stop checks; only known unsubmitted reservations defer; uncertain submission does not replay. |
| Call ownership and lifetime | Queue reset regression; source/thread/epoch checks in context and scheduler. Existing source/child browsing fixture retained. |
| UI control is separate from Stop | Added full-screen Compose fixture asserts pause/resume does not call Voice Stop or child Stop, and source call/card remain active. Compiled only. |

## Audio and device gaps

Bundled WebRTC `144.7559.09` bytecode was inspected with `javap`.
`WebRtcAudioTrack.AudioTrackThread.run` zeroes the buffer when speaker mute is
set, before writing it and reporting playback samples. A muted level callback
therefore cannot serve as an audio-only reply's unmute trigger. The policy
restores playback at a real user's finalized turn instead of waiting for an
assistant delta. Audio-only output also releases the awaiting-reply latch.
WebSocket playback uses nonblocking writes so local pause/flush and Stop can
discard queued chunks without a writer waiting on a paused track.

No phone or emulator was used; nothing was installed or deployed. Real
microphone/noise thresholds, echo rejection, speaker buffers, Bluetooth routes,
quiet/resume commands and long live calls require separate physical QA for
both transports. Realtime events currently expose role/thread but no response
ID, so late frames from an interrupted child and a new user reply cannot be
identified perfectly by source. A submitted handoff, transcript, unit test or
APK build is not proof of interruption-free physical playback.

The original interrupted-speech experience remains a user report, not a new
physical reproduction from this implementation.
