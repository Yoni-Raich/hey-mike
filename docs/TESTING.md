# Testing and evidence

Tests in this repository cover different boundaries. Keep those boundaries
explicit when reporting a result.

## Useful commands

Run these from the repository root in PowerShell:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat :app:assembleDevDebug --no-daemon
.\gradlew.bat :device-tools:test :core:test --no-daemon
python -m unittest tools.test_prepare_runtime
git diff --check
```

The recorded v0.6.0 full validation also used:

```powershell
.\gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug --no-daemon
```

That full gate passed on 2026-09-08. The focused screen-awake change passed
`:core:test`, `:app:testDevDebugUnitTest`, `:overlay:testDebugUnitTest`, and
`:app:assembleDevDebug` on 2026-09-09. These are recorded results, not a claim
that every later environment will pass.

## Evidence levels

- **PASS** means the named command or observation completed successfully.
- **NOT TESTED** means no evidence was collected for that behavior.
- **BLOCKED** means a known environment or product limit prevents the check.

Builds prove compilation and packaging. Unit tests prove the covered logic.
Compose fixture tests prove fixture UI behavior. APK installation proves only
that Android accepted the APK. None of these alone proves real account sign-in,
streamed chat, visual quality, device control, or voice success.

## Verifying standing rules on a phone

Nothing about a standing rule is proven by a unit test except the decision it
makes. The wake-ups — an alarm landing, a notification arriving, a rule taking
the screen — have only ever been reasoned about, so this is the checklist that
turns that into evidence. Run it on a phone with the app already set up
(accessibility on, signed in) and record the result per step, with the phone
model and Android version.

Grant these first, in Settings > Standing rules:

- **Notification access** — needed for step 4 only. No app can grant it itself.
- **Exact alarms** — if the screen says "Alarms: approximate", step 2 may land
  late, and that is a result worth recording rather than a broken step.

### 1. The spine — does a rule run at all

Ask Mike: *"Make a rule called `check-one` that just notifies me 'rule ran', and
run it now."* It should call `automation_rule` with `mode:"create"` then
`mode:"run"`.

- [ ] A notification saying "rule ran" appears.
- [ ] `automation_rule(mode:"describe", rule:"check-one")` shows `firedToday: 1`.

A `run` that reports `started` but produces nothing means the host fired and the
evaluator refused: `describe` will show the count unchanged, and `mode:"test"`
with the same moment names the clause.

### 2. The clock — does an alarm land

Ask for a rule scheduled two or three minutes ahead that notifies. Then **lock
the phone and put it down.**

- [ ] The notification arrives within a minute of the stated time.
- [ ] It arrives with the screen off and the app not in front.

This is the step that Doze and OEM battery management break. A miss here is the
most important result in the whole checklist — record the phone model, and
whether the app is exempted from battery optimisation.

### 3. Surviving a restart

With the rule from step 2 still saved, reschedule it a few minutes out, then
**reboot the phone** and leave it alone.

- [ ] The notification still arrives.

An alarm does not survive a restart; only the boot receiver re-arming it does.
If this fails, scheduled rules silently stop after every reboot.

### 4. Notifications — does the filter hold

Ask for a rule on one app you can trigger easily (a message to yourself), whose
action is `notify` and whose text uses `{{notification.title}}`.

- [ ] Sending yourself a message in that app fires the rule.
- [ ] A notification from **any other app** does not fire it.
- [ ] Mike's own notification from step 1 does not fire it (no loop).

### 5. The device claim and Stop

Ask for a rule whose action is `run_workflow`, naming a workflow already proven
on this phone, and run it with `mode:"run"`.

- [ ] The floating control card appears, labelled `Automation · <workflow>`.
- [ ] The workflow runs.
- [ ] Firing it again **while a chat turn is working** waits for that turn and
      then runs, rather than reporting the phone busy.
- [ ] Tapping **Stop** while the rule is driving stops it, and the reply names
      the step it stopped at without claiming earlier steps were undone.

### 6. Asking, and not answering

Ask for a rule with an `ask` action followed by a `notify`.

- [ ] Running it puts up a notification with Yes and No.
- [ ] **No** stops the run: the second notification never appears.
- [ ] Ignoring it for five minutes has the same effect as No.

### What a failure here means

A step that fails is a real defect, not a flaky test: every one of these is the
feature's actual job. Record it in `PROGRESS.md` under "Not proven yet" with the
phone and Android version before changing any code.

## Current gaps

No standing rule has ever fired on hardware: the checklist above has not been
run. The `:automations` module also has no unit tests of its own — it is alarms,
broadcasts and a notification listener, which need an instrumented run.

The full signed-in phone chat and complete device-control flow are not proven.
Physical checks are still needed for the Accessibility service, realtime voice,
overlay visuals, recovery, Wireless ADB pairing/reconnect, and several device
tools. The emulator runtime is also not a substitute for an ARM64 phone.

CI runs `:app:lintDevDebug` and it is expected to pass. Do not claim that
project-wide lint is green because scoped tests or `:voice:lintDebug` pass;
run `:app:lintDevDebug` itself.

## Protecting a configured phone

Do not run `connectedDevDebugAndroidTest` against a phone whose app data matters.
The Android Gradle test flow installs the test APK and then uninstalls the app
and test APK. That removes app-private sign-in, sessions, and staged runtime
files, and installing the test APK can disable the Accessibility service.

For a stateful phone, install both APKs with `adb -s <serial> install -r`, enable
Accessibility by hand if needed, and run only a selected instrumentation class
with `adb -s <serial> shell am instrument ...`. Even then, instrumentation
force-stops the package, so it cannot observe a live Accessibility service in
the same process. See [Known issues](KNOWN_ISSUES.md).
