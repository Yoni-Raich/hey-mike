---
name: device-capabilities
description: Use fast Android APIs for contacts, calendar, media and workspace files, message drafts, installed apps, permissions and Settings. Read this before walking the UI for data Android can provide directly.
---

# Device capabilities

Use these tools before UI automation. They call Android APIs, return small JSON,
and can also run as `call` steps inside one workflow. The tool list is fixed when
a chat starts, so start a fresh chat after an app update if these names are missing.

## Tools

- `contacts`: `permission_status`, `search`, `list`, `get`, `create_draft`.
- `calendar`: `permission_status`, `list`, `get`, `create_draft`.
- `files_media`: `permission_status`, `list`, `search`, `info`, `open`, `share`,
  `ws_list`, `ws_read_text`, `ws_write_text`.
- `communications`: `draft_sms`, `draft_email`, `dial`,
  `notification_access_status`, `open_notification_access_settings`.
- `apps_settings`: `list_apps`, `app_info`, `open_app`, `open_app_settings`,
  `permission_status`, `request_permissions`, `open_special_access`,
  `open_system_setting`.
- `location`: `permission_status`, `current`.

Every call needs `operation`. Unknown operations and fields are refused. Results
are typed JSON with `ok`, `tool` and `operation`. Lists are capped; narrow the
query instead of asking for all phone data.

Examples:

```text
contacts(operation="search", query="Dana", limit=5)
calendar(operation="list", startMs=..., endMs=..., limit=20)
files_media(operation="search", kind="image", name="receipt", limit=10)
apps_settings(operation="list_apps", query="maps", limit=10)
location(operation="current")
```

## Where the phone is

`location(operation="current")` reports the newest fix the phone already has.
It never waits for a new one, so a phone whose radio has been idle answers
`errorType:"no_fix"` rather than stalling the turn — say so and offer to try
again, or widen `max_age_ms`. Every answer carries `age_ms`: a ten-minute-old
fix is not where the user is standing, and saying "you are at X" from one is
wrong.

Coarse accuracy is the default and is enough for a town, a neighbourhood or
"which city am I in". Pass `precise=true` only when the task actually needs the
street — it asks for a stronger permission the user may refuse. `precise` comes
back in the answer, and a coarse grant always reports `precise:false` even when
you asked for true.

`errorType:"location_off"` means the whole phone has location switched off. That
is not a permission problem and requesting permissions will not fix it; use
`apps_settings(operation="open_system_setting", ...)`.

There is no background or repeating form of this tool. A task that means "tell
me when I get home" is a standing rule — see `device-automation` — not a loop
around `location`.

Workspace operations only see the current run workspace. Paths must be relative.
They cannot delete, escape with `..`, or use `file://`. Media open/share accepts
only `content://media/...` URIs returned by `files_media` list or search.

## Permissions: one system request

Do not ask in chat first. If a read fails with `errorType:"permission_denied"`:

1. Pass its `permissions` array once to
   `apps_settings(operation="request_permissions", permissions=[...])`.
2. Android shows its normal permission dialog. The user answers there.
3. If granted, repeat the exact call in `retry.tool` + `retry.arguments`.

Only missing allowlisted permissions are requested. There is no extra Mike
approval card. A denial is final for this attempt; do not loop.

## Visible actions are drafts

`create_draft`, `draft_sms`, `draft_email`, `dial`, `open`, `share`, and Settings
operations only open a visible Android screen. They do not save a contact or
event, send a message, place a call, pick a share target, or change a setting.
The user or later visible UI action owns that final step.

Notification access here only reports whether Hey Mike appears enabled and can
open the setup screen. This version does not read notification content.

## One-call workflows

A workflow `call` step uses one of the registered tools:

```json
{"id":"find","action":"call","tool":"contacts",
 "arguments":{"operation":"search","query":"{{name}}","limit":1},
 "output":"matches"}
```

Capture JSON with `output`, then use a field in a later call:

```json
{"id":"get","action":"call","tool":"contacts",
 "arguments":{"operation":"get","id":"{{outputs.matches.items.0.id}}"},
 "output":"person"}
```

An exact reference keeps its JSON type. Captured outputs are returned in a
failure's `resume.arguments`; pass that object unchanged so completed API calls
are not repeated. A workflow cannot call shell, install, or another workflow.
