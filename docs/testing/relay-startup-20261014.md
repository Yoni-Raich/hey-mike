# Windows relay startup repair

The installed 1144 / `0.14.0-dev.reconnect1` APK is byte-identical to its PC
artifact: SHA-256 `38b195a0c3da4f52452430bc4def47f9100be9888f84617df577188586f7c5e5`.
The physical phone attempt at 23:50:04 on October 13 created relay metadata at
23:50:06. Its recorded process and listener were absent when inspected. The
phone returned `Codex relay closed` at 23:55:15. Metadata never recorded a clean
shutdown. This establishes the failed relay and retry delay, rather than a
model response failure.

The old startup used background `Start-Process` inside a short SSH command.
The new startup uses `CreateProcessW` with `CREATE_BREAKAWAY_FROM_JOB`, a hidden
new console, and disabled handle inheritance. It checks an authenticated
health response before claiming readiness. Broker PID plus start time rejects
stale metadata or a reused PID before attachment. The Android writer retains
the safe terminal error instead of replacing it with a generic closure.

`CodexRelayWindowsJobTest` executes the product bootstrap in a native Windows
job with kill-on-close and breakaway allowed. It closes the job, then proves
the retained relay still exchanges Unicode input/output. A separate test
reproduces the old startup's termination and confirms stale attachment fails
immediately. These are native Windows job fixtures; they do not by themselves
prove the installed phone's real OpenSSH/model/network path.

Validation commands and final build results are recorded in `PROGRESS.md`.
Candidate: 1145 / `0.14.0-dev.reconnect2`, Dev Debug. Physical installation,
new computer chat, in-flight reattachment, and follow-up continuity remain
pending while the phone is locked. No app data, sign-in, or sessions were
cleared; no test APK was installed. The original reconnect worktree remains
byte-identical to the preserved source manifest.

References: [Windows process flags](https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags),
[CreateProcessW](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw).
