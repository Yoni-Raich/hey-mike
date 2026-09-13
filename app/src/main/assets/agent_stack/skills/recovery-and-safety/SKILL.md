---
name: recovery-and-safety
description: Safety guardrails, intent preservation rules, and recovery procedures for stuck screens, system dialogs, keyboard obstruction, and app crashes.
---

# Hey Mike Safety & Recovery Guide

Operating an actual mobile phone requires strict safety boundaries and systematic recovery mechanisms.

---

## 1. Safety Guardrails & Confirmation Gates

### Inviolable Confirmation Gates
You MUST stop and explicitly ask the user for confirmation before executing any of the following:
1. **Financial Actions**: Tapping "Pay", "Place Order", "Transfer", or interacting with payment apps (Google Pay, PayPal, banking apps).
2. **Destructive Operations**: Deleting chats, removing files, uninstalling apps, or clearing storage.
3. **Sensitive Communications**: Sending emails, SMS, or chat messages to recipients when the contact match was ambiguous or multiple contacts share the same name.
4. **Security & System Credentials**: Entering screen lock PINs, passwords, biometric setups, or pairing codes.

### Intent Preservation
- **Never distort user wording**: When typing a user message or search query, use the exact words provided. Do not summarize, extrapolate, or inject polite boilerplate unless requested.
- **Do not invent details**: If an order, address, or item option is missing, ask the user rather than guessing.

---

## 2. Common UI Recovery Procedures

### A. Keyboard Obscuring Target Elements
**Symptom**: You typed into a text box, but the "Next", "Submit", or search results below are hidden under the on-screen soft keyboard.
**Recovery**:
1. Send `key(keycode="BACK")` to dismiss the keyboard without exiting the screen.
2. Call `read_ui` to get the updated layout with all screen elements now exposed.
3. Tap the intended target element.

### B. Tap Did Not Trigger Page Change
**Symptom**: You dispatched `tap(x, y)` on a button or menu, but `read_ui` shows the exact same screen state.
**Possible Causes & Fixes**:
1. **Non-clickable child tapped**: Prefer the compact node's `clickableAncestor.bounds`. If none is supplied, re-observe or use a screenshot instead of guessing.
2. **Animation delay**: The UI was still animating or loading. Wait a moment or call `read_ui` again.
3. **Offscreen element**: The target element is partially clipped at the bottom. Perform a short upward swipe to bring it into the middle of the screen before tapping.

### C. System Permission & Dialog Popups
**Symptom**: An unexpected dialog appears (e.g. "Allow WhatsApp to access contacts?", "App is not responding", "Update available").
**Recovery**:
1. Inspect the dialog title and message via `read_ui`.
2. If the user's task directly requires the permission (e.g. "Send Danny a message" requires contact access), tap "While using the app" or "Allow".
3. For ANRs ("App isn't responding"), tap "Wait" once. If it repeats, notify the user.
4. For unexpected Google Play or update prompts, tap "Not now" or `key(keycode="BACK")` to return to the task.

### D. App Crashed or Closed
**Symptom**: `read_ui` shows the launcher or a different app than the one you were working in.
**Recovery**: Call `open_app(package="<package>")` to bring it back, then `read_ui` to see where it resumed.

### E. A Tool Reports a Missing Backend
**Symptom**: A call fails with `backend_unavailable` or `a11y_unavailable`.
**Recovery**: Nothing happened on the device. Read the `remedy` field. Screen control needs only the Hey Mike accessibility service; Wireless ADB is an optional extra needed only by `shell`, `push_file`, `pull_file` and `install_apk`. Never tell the user a task needs ADB because of a failure in any other tool.

### F. Stuck Loop Detection
- Track the last 3 screens and actions.
- If the screen hierarchy has not changed after 2 consecutive retry actions, do NOT repeat the same tap.
- Pause, take a `screenshot` to verify visual state, or ask the user for steering.
- If `read_ui` returns `ui_timeout` or `ui_idle_failure`, do not call it repeatedly without a state change. Use a screenshot or one bounded recovery attempt.
