---
name: app-cards
description: How to operate a specific app without exploring blindly — what earlier chats learned about it (recall_capability, saved workflows), plus starter cards with packages, selectors and flows for WhatsApp, Chrome, Google Maps, Settings and YouTube. Read before working inside any app.
---

# App Knowledge

Three sources, checked in this order, tell you how an app works before you explore it. For messaging, calling or navigating to a person or place, `quick-actions` comes before all of them.

## 1. What earlier chats learned

- **`list_workflows(package)`** — a saved step sequence that already worked. If one matches the task, run it with `run_workflow`. If a step fails, continue from that step; never restart it.
- **`recall_capability(package)`** — selectors and deep links earlier chats confirmed for this app. A record marked `stale:true` is a hint to verify, not a fact.

## 2. The starter cards below

Hand-written for five common apps. They are hints, not guarantees: apps change their layouts. Confirm with `read_ui` and match on `text`, `contentDescription` or `resourceId`.

## 3. Save what you learn

This is how the next chat gets faster:

- **`quick-actions save-intent`** — after a deep link reached a destination a later request could reuse. This is what makes the next request one step.
- **`quick-actions save-contact`** — after you found a person's phone number.
- **`remember_capability`** — after you find a selector or deep link that works, especially one missing from or contradicting a card. Use a `resourceId` or `contentDescription`, never a coordinate pair.
- **`save_workflow`** — after a multi-step UI sequence with no deep link ran cleanly and is likely to be repeated.

When an app offers a deep link, `open_intent` usually beats a long tap sequence (see the `device-automation` skill).

---

# Starter Cards

---

## WhatsApp — `com.whatsapp`

| Element | Selectors |
|---|---|
| Search icon | `resourceId` `com.whatsapp:id/search_icon`, `contentDescription` "Search" |
| Search input | `com.whatsapp:id/search_input` or `com.whatsapp:id/search_src_text` |
| Chat row name | `com.whatsapp:id/conversations_row_contact_name` |
| Message field | `com.whatsapp:id/entry`, `text` "Message" |
| Send button | `com.whatsapp:id/send`, `contentDescription` "Send" |
| Back | `com.whatsapp:id/back`, `contentDescription` "Navigate up" |

**Send a message**
1. Run `quick-actions` first: `act.sh run whatsapp.send contact=<name> text=<message>`. It needs the user's approval in the app.
2. If the contact is not saved and the number cannot be found, open the chat by name, then save the number when you see it: `open_app(package="com.whatsapp")` → tap Search → `type_text(text="<name>")` → `read_ui(text="<name>")` → tap the matching row.
3. Tap the message field, `type_text(text="<message>")`, then `read_ui` to confirm the text is there. The voice-note icon turns into **Send**.
4. Tap Send, then `read_ui` to confirm the bubble appears in the conversation.

## Google Chrome — `com.android.chrome`

| Element | Selectors |
|---|---|
| URL / search bar | `com.android.chrome:id/url_bar`, `text` "Search or type URL" |
| Tab switcher | `com.android.chrome:id/tab_switcher_button` |
| Menu | `contentDescription` "More options" |
| Home | `com.android.chrome:id/home_button`, `contentDescription` "Home" |

**Open a page or search**
1. For a known URL, prefer `open_intent(uri="https://…", package="com.android.chrome")`.
2. Otherwise: `open_app(package="com.android.chrome")` → tap the URL bar → `type_text(text="<url or query>", submit=true)`.
3. Verify with `read_ui`, or `screenshot` when the web layout matters.

## Google Maps — `com.google.android.apps.maps`

| Element | Selectors |
|---|---|
| Search bar | `text` "Search here", `com.google.android.apps.maps:id/search_omnibox_text_box` |
| Directions | `contentDescription` or `text` "Directions" |
| Start navigation | `contentDescription` or `text` "Start" |

**Navigate to a place**
1. Check the `user-preferences` skill for a saved address first.
2. Prefer `open_intent(uri="google.navigation:q=<url-encoded destination>")` to start navigation directly.
3. Otherwise: `open_app` → tap "Search here" → `type_text(text="<destination>", submit=true)` → tap the result → Directions → Start.

## Android Settings — `com.android.settings`

| Element | Selectors |
|---|---|
| Search | `text` "Search settings", `com.android.settings:id/search_action_bar` |
| Network | `text` "Network & internet" or "Connections" |
| Apps | `text` "Apps" or "Applications" |
| Display | `text` "Display" |

**Change a setting**
1. `open_app(package="com.android.settings")`, tap "Search settings", `type_text(text="<setting name>", submit=true)`. Search beats scrolling.
2. Tap the matching preference.
3. A toggle is a `Switch` node; check its state in `read_ui` before tapping, and verify after.

## YouTube — `com.google.android.youtube`

| Element | Selectors |
|---|---|
| Search icon | `contentDescription` "Search", `com.google.android.youtube:id/menu_item_1` |
| Search input | `com.google.android.youtube:id/search_edit_text` |
| Video title | `com.google.android.youtube:id/title` |
| Play / Pause | `contentDescription` "Play" / "Pause" |

**Play a video**
1. `open_app(package="com.google.android.youtube")` → tap Search → `type_text(text="<query>", submit=true)`.
2. Tap the first relevant title; playback starts on its own. Verify with `read_ui` or `screenshot`.
