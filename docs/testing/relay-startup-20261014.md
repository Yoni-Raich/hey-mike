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

1145 / `0.14.0-dev.reconnect2` was copied with the app's `copy_file` and
installed with `install_apk`. Independent installed APK SHA-256 matched the
PC artifact. A real new PC chat retained its broker and app-server beyond the
SSH bootstrap, confirming that startup fix. It still stalled during RPC input
delivery and eventually returned `Stream closed`; it was not a successful chat.
Temporary attachment diagnostics were restored byte-for-byte afterwards.

A separate authenticated Windows OpenSSH echo fixture reproduced stalled
stdin in both PowerShell and a native .NET executable. LF/CRLF and a raw
stream reader did not resolve it. The underlying Windows pipe cause remains
unproven. An authenticated SSH `direct-tcpip` channel to a private loopback echo
listener completed 100 round trips. 1146 therefore uses that tunnel for both
Codex and native Claude relay attachments, preserving the same framing,
capability, receipts, broker identity, replay cursor, lease and Stop behavior.
There is no external listener or second child process. The SSH server must
allow TCP forwarding.

The real-SSH unit fixture verifies 100 Unicode tunnel round trips and that
closing it leaves the SSH session usable. The product Windows job fixture
now also runs the exact endpoint/tunnel attachment through a real SSH server,
detaches it, then confirms the same broker and ordered input/output after
reattachment. The final 1146 gate passed: 2,667 unit executions, no failures or
errors, 32 existing skips, no app/voice lint errors, and 15 runtime preparation
Python tests. The initial full run exposed the short lease starting before
process startup/publication; moving that boundary fixed the failure before the
successful final gate.

1146 Dev Debug (`0.14.0-dev.reconnect3`) is 331,619,686 bytes, SHA-256
`11052cde1eeaf5f9c4d1fa7a98026245c3b340a684f2d3c6bc42d39827bd6a16`.
Package/version, the unchanged debug certificate, v2 signature, 16KiB ZIP
alignment and exact packaged relay source were verified. Dev Release also
contains the exact helpers, but is unsigned and was not installed.

Physical Nothing A059 validation on October 14:

- The app's `copy_file` completed in 7.718s. The independently read copied
  file hash matched. `install_apk(replace=true)` updated the calling app,
  interrupting its own terminal tool receipt; package metadata confirmed 1146
  and the independently hashed installed `base.apk` matched. No full network
  ADB APK transfer, uninstall, data clear or test APK was used. Wire bytes were
  not measured and are not inferred from the transfer duration.
- A new PC Codex chat received its first assistant message about 14s after
  sending. The actual PC `exec_command` receipt returned the working folder
  with exit code zero. The phone `read_ui` receipt succeeded through its ADB
  gateway. The final `RELAY-1146-OK` reply and completed turn were recorded.
- A prepared PC command used `SetTcpEntry` to delete exactly one loopback TCP
  connection, after matching the test broker PID, process start ticks, port,
  child parent and the single established connection. It executed once with
  exit code zero. An independent TCP watcher saw the connection disappear and
  a new peer port appear about 1.84s later. Broker PID 37948 and app-server PID
  19068 retained the same creation times. The command output and
  `RECONNECT-1146-OK` final reached the phone, with no resubmitted user turn.
- The next turn recalled the first marker and working folder and returned
  `FOLLOWUP-1146-OK` on the same Codex thread.
- The original reported chat still showed its prior Codex relay and Claude
  startup errors. A new reply-only connection check on its selected Claude /
  Sonnet engine returned `EXISTING-1146-OK` and a completed turn. It did not
  retry any earlier work.
- After an idle app restart, accessibility was restored with the Android
  settings UI and confirmed bound; other enabled services were preserved.
  The original screen timeout was restored to 120000ms. Temporary deployed
  diagnostics were restored byte-exactly; temporary echo processes were stopped
  with a guard restricting their executable paths to this test's temp folder.

The forced loss was the relay tunnel's TCP socket while the SSH session stayed
connected. It does not prove an outer SSH-session replacement, a physical
Wi-Fi/VPN outage, a long outage/soak, voice recovery, Linux retained processes,
or a non-debuggable Release installation. These remain separate validation.

No app data, sign-in, or sessions were cleared; no test APK was installed.
The original reconnect worktree remains byte-identical to the preserved source
manifest. Validation commands and final build results are in `PROGRESS.md`.

References: [Windows process flags](https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags),
[CreateProcessW](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw).
