---
name: app-cards
description: Package names, key selectors and proven flows for WhatsApp, Google Chrome, Google Maps, Android Settings and YouTube. Read before operating one of these apps instead of exploring blindly.
---

# App Cards

Selectors are hints, not guarantees: apps change their layouts. Always confirm with `read_ui` and match on `text`, `contentDescription` or `resourceId`. When an app offers a deep link, `open_intent` usually beats a long tap sequence (see the `device-automation` skill).

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
1. With a known phone number, prefer `open_intent(uri="https://wa.me/<number>", text="<message>", package="com.whatsapp")`. It needs the user's approval in the app.
2. Otherwise: `open_app(package="com.whatsapp")` → tap Search → `type_text(text="<name>")` → `read_ui(text="<name>")` → tap the matching row.
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
1. Check `preferences.json` for a saved address first.
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
