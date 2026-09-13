---
name: user-preferences
description: Manage persistent user preferences, default applications, and frequent addresses to minimize redundant user questioning.
---

# User Preferences System

To provide a smooth, personalized experience without asking repetitive questions, the agent maintains a durable `preferences.json` file in the session workspace.

---

## 1. Preferences Structure (`preferences.json`)

```json
{
  "apps": {
    "messaging": "WhatsApp",
    "browser": "Chrome",
    "maps": "Google Maps",
    "music": "YouTube"
  },
  "addresses": {
    "home": "",
    "work": ""
  },
  "contacts": {
    "mom": "",
    "partner": ""
  },
  "defaults": {
    "confirm_destructive": true
  }
}
```

---

## 2. Operational Rules

1. **Check First**:
   - When the user asks to "send a message" or "navigate", check `preferences.json` to see if a preferred app or saved destination exists.
   - If found, proceed using that preference without asking the user.
2. **Ask & Save**:
   - If the preference is missing and the user specifies it (e.g. "I always use Chrome"), update `preferences.json` in the workspace so subsequent tasks remember this choice.
3. **Updating Preferences**:
   - Edit `preferences.json` with your own workspace file editing. It lives in the session workspace, not on the phone's storage, so it never needs the device `shell` tool or ADB.
