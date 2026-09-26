# Permissions and privacy

Hey Mike can send screen content to a cloud Codex service and can control
the phone. Read this page before granting optional permissions.

## Declared permissions

| Permission | Why it is declared | User impact |
|---|---|---|
| `INTERNET` | Codex sign-in, app-server traffic, and update checks | Network traffic can leave the phone. |
| `ACCESS_NETWORK_STATE` | Report network state and diagnose connectivity | Reads connection state. |
| `ACCESS_WIFI_STATE` | Wireless Debugging discovery support | Reads Wi-Fi state. |
| `CHANGE_WIFI_MULTICAST_STATE` | Support Android NSD discovery for Wireless ADB | Controls multicast-lock state for discovery. |
| `SYSTEM_ALERT_WINDOW` | Floating control card during device control | Lets the card appear above other apps. |
| `POST_NOTIFICATIONS` | Foreground-service and app notifications | Allows visible notifications when granted. |
| `FOREGROUND_SERVICE` and special-use/microphone types | Keep a user-started agent or voice run alive | Shows a foreground-service notification and uses system service rules. |
| `WAKE_LOCK` | Keep the screen awake during an active typed or voice run | May keep the display on longer and use more battery. |
| `RECORD_AUDIO` | Optional realtime voice input | Microphone audio is captured only for an active voice session. |
| `MODIFY_AUDIO_SETTINGS` | Select the voice audio route | Changes audio routing during a voice session. |
| `REQUEST_INSTALL_PACKAGES` | User-started installation of a downloaded update APK | Opens the Android package installer; it does not silently install. |
| `QUERY_ALL_PACKAGES` | Resolve installed apps for approved intent actions | Lets the app see more installed-package metadata. |
| `SCHEDULE_EXACT_ALARM` | Fire a standing rule at the minute it names | One alarm is held, for whichever rule is next due. Without it a rule may run up to an hour late. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm that alarm after a restart | The app is started briefly at boot to reschedule; it does not begin a run. |

The Accessibility service is a separate user-enabled Android capability. It is
used for screen observation, taps, typing, scrolling, and screenshots when
available. The input method is another user-selected Android service used for
Unicode input and is restored after the operation.

**Notification access** is a third user-enabled capability, used only by
standing rules, and it is the one that reads other apps' content. It is off
until granted by hand in Settings, and no app can grant it to itself. What it
does with a posted notification, in this order:

1. notifications from Hey Mike itself are dropped, so a rule cannot trigger
   itself;
2. ongoing and group-summary notifications are dropped;
3. **the package is checked before the title or the body is read**, against the
   apps named by the rules that are currently on. A notification from any other
   app is never looked at, and with no notification rule saved the service reads
   nothing at all;
4. only then are the title and text extracted, matched **on the phone**, and
   discarded.

Nothing is kept. There is no notification log, no history of what arrived, and
no tool that can ask for one. The rule journal stores timestamps only.

## What can leave the phone

While a task is active, the agent may send the model:

- the user's chat text and session context;
- semantic screen nodes, visible text, and tool results;
- screenshots when the observation path needs vision;
- finalized voice transcripts when realtime voice is used.

A standing rule sends on **only the fields its own actions write into**. A rule
that matches on the body of a message but whose prompt uses only the sender's
name never transmits that body; the reply when the rule is saved lists exactly
which fields it will send. Matching itself happens on the phone.

The model service is not local. Review the account and service terms that apply
to your Codex account before using the app with private data.

The app's local CONNECT proxy resolves and tunnels allowlisted OpenAI HTTPS
destinations. It does not terminate TLS or record request bodies. Diagnostics
keep only short redacted error tails. No API key is shipped in the APK.

## What is stored locally

Sessions, messages, generated images, screenshots, configuration, runtime files,
and artifacts are kept in app-private storage. The manifest disables Android
backup for the app. Raw microphone audio and realtime SDP are not saved or
logged; finalized transcripts can be stored in the session.

The app-managed on-device skills are copied into app-private storage at
startup. A session workspace is organization, not an OS-level security
sandbox. Treat files and model output as sensitive if the phone is shared.

## Device-control safety

Screen text is untrusted input to the agent. The app routes device actions
through a gateway, shows visible control state for mutating runs, and supports
local Stop. Stop revokes new tool calls before interrupting active work. It
cannot undo a side effect that already completed.

Do not enable Accessibility or overlay access for a phone or user profile where
you cannot accept those risks. Do not use the app with secrets visible on
screen unless you understand that a running task may transmit observations.
