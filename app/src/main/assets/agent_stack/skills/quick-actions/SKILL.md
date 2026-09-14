---
name: quick-actions
description: One-step actions from saved contacts and saved intents — message, call or SMS a person by name ("send dad a message", "call mom"), navigate, search. A script turns the request into one ready open_intent call, with no looking up and no walking through app screens. Use it FIRST for any request to contact a person or open a known destination, and save every new contact number and working intent so the next request is one step.
---

# Quick Actions

Saved **contacts** (with international phone numbers) and saved **intents** (deep-link templates) let one script turn "send a WhatsApp to dad: I'm on my way" into the exact `open_intent` call. No contact search, no UI navigation.

The script:

`sh {{SKILLS_DIR}}/quick-actions/scripts/act.sh`

Its data lives in `{{QUICK_ACTIONS_DIR}}`, shared by every chat and kept across app updates.

---

## 1. Run it first

For any request to message, call or text a person, navigate somewhere, or open a known destination, run the matching intent **before** doing anything else:

```sh
sh {{SKILLS_DIR}}/quick-actions/scripts/act.sh run whatsapp.send contact="אבא" text="אני בדרך"
```

It prints one JSON line:

```json
{"tool":"open_intent","arguments":{"uri":"https://wa.me/972501234567","package":"com.whatsapp","text":"אני בדרך"}}
```

Call `open_intent` with **exactly** those `arguments`, then verify with `read_ui` that the chat opened with the text in the field. Opening sends nothing. Then press Send in the same turn — do not stop to ask in the chat. The press itself asks the user in the app, unless they chose to always allow it (see `device-automation`, "Sending asks the user").

Pass the user's words as they gave them: `contact="אבא"` matches a saved key, name or alias. `text` is the user's message, verbatim.

## 2. Built-in intents

| intent | params | does |
|---|---|---|
| `whatsapp.send` | contact, text | WhatsApp chat with the message typed in |
| `whatsapp.chat` | contact | WhatsApp chat |
| `phone.dial` | contact | dialer with the number; the user presses call |
| `sms.send` | contact, text | SMS with the message typed in |
| `maps.navigate` | place | Google Maps navigation |
| `waze.navigate` | place | Waze navigation |
| `web.search` | query | web search |
| `youtube.search` | query | YouTube search |

`sh .../act.sh list` shows these plus everything saved. For `place`, use a saved address from `user-preferences` when the user says "home" or "work". Pick the navigation app from the user's preferences.

## 3. When the script cannot resolve it

| exit | meaning | do this |
|---|---|---|
| 4 | no saved contact | find the number (section 4), save it, run again |
| 3 | no such intent | use a built-in one, or work it out once and save it (section 5) |
| 2 | a parameter is missing or invalid | read the message, fix the call |

## 4. Save every contact you find — required

The first time a person is needed and not saved:

1. **Find the number.** Ask the user, or read it from the Contacts app with the device tools (`open_app` the contacts app, search the name, `read_ui`).
2. **Check it is the right person.** If several contacts match, ask which one.
3. **Save it** in international form (`+972 50-123-4567` or `972501234567`, never `050…`), with every name the user uses as an alias:

```sh
sh {{SKILLS_DIR}}/quick-actions/scripts/act.sh save-contact dad "Yossi Cohen" "+972501234567" "אבא,dad,yossi"
```

4. Run the intent again and tell the user the contact is saved, so next time it is one step.

When the user uses a new nickname for someone already saved, save that contact again with the alias added. `contact <name>` shows who a name resolves to, and `forget-contact <key>` removes one.

## 5. Save every intent that worked — required

Whenever you reach a destination in an app through a deep link that a later request could reuse — a chat, a search, a route, a screen — save it as a template once it works:

```sh
sh {{SKILLS_DIR}}/quick-actions/scripts/act.sh save-intent spotify.search query - "spotify:search:{query}" com.spotify.music - "Search Spotify"
```

Arguments, in order (`-` for empty):

1. **name** — `app.verb`, lowercase.
2. **params** — comma-separated, e.g. `contact,text`.
3. **action** — e.g. `android.intent.action.DIAL`, or `-` for VIEW.
4. **uri** — the template. `{param}` is filled and percent-encoded; with a `contact` parameter, `{phone}` (digits, no `+`) and `{name}` are available too.
5. **package**
6. **text** — the prefilled message template, e.g. `{text}`, or `-`.
7. **description** — what it does, for `list`.

A saved intent with a built-in's name replaces it.

An intent that needs **extras** — a timer (`android.intent.action.SET_TIMER`
with `android.intent.extra.alarm.LENGTH`), a calendar event, a share — cannot be
stored here. Save it as a workflow definition with one `open_intent` step and
its values as parameters (see the `workflows` skill), so "a timer for 10
minutes" is one `workflow_runner` call. Also record the underlying selector or link with `remember_capability`, and a multi-step UI sequence that has no deep link as a workflow definition (see `workflows`).

Tell the user briefly what you saved.

## 6. Never

- Store passwords, codes, card or ID numbers.
- Call anything but `open_intent` with the script's output, or edit the arguments it printed.
- Edit the data files by hand; always use `save-contact` and `save-intent`, which keep them valid.
