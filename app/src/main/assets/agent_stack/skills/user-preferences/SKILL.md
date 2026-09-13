---
name: user-preferences
description: The user's saved defaults — preferred apps, named addresses and contacts — shared by every chat. Read before asking which app, place or person the user means; update when the user states a lasting preference.
---

# User Preferences

The user's defaults live in one file shared by every chat:

`{{PREFERENCES_PATH}}`

Read and edit it with your own file tools. It is on the app's private storage, not the phone's shared storage, so it never needs the device `shell` tool or ADB.

## Structure

```json
{
  "apps": {
    "messaging": "WhatsApp",
    "browser": "Chrome",
    "maps": "Google Maps",
    "music": "YouTube"
  },
  "addresses": {
    "home": "Herzl 1, Tel Aviv"
  },
  "contacts": {
    "mom": "Dana Cohen"
  }
}
```

Add keys under these sections as needed; keep the file valid JSON.

## Rules

1. **Check first.** When a task leaves the app, place or person open ("send mom a message", "navigate home", "play some music"), read the file and use what it says without asking.
2. **Ask only when it is missing,** or when the saved value is ambiguous for this task.
3. **Save lasting preferences.** When the user states one ("I always use Waze", "home is Herzl 1"), update the file right away and tell them it is saved. Do not save one-off choices.
4. **Never store secrets** — passwords, codes, card or ID numbers — even if asked.
