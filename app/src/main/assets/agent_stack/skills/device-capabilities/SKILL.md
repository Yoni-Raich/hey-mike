---
name: device-capabilities
description: Use fast Android APIs for contacts, calendar, media and workspace files, notifications, SMS, calls, system controls (torch, volume, DND, brightness, clipboard, location, media, usage, settings), installed apps and permissions. Read this before walking the UI for data or controls Android can provide directly.
---

# Device capabilities

Use these tools before UI automation. They call Android APIs, return small JSON,
and can also run as `call` steps inside one workflow. The tool list is fixed when
a chat starts, so start a fresh chat after an app update if these names are missing.

## Tools

- `contacts`: `permission_status`, `search`, `list`, `get`, `create_draft`, `create`.
- `calendar`: `permission_status`, `list`, `get`, `create_draft`, `create`,
  `delete`, `calendars`.
- `files_media`: `permission_status`, `list`, `search`, `info`, `open`, `share`,
  `ws_list`, `ws_read_text`, `ws_write_text`, and folders the user granted:
  `folders`, `pick_folder` (the user picks a folder once; it stays granted),
  `folder_list` (`folder`, `path`), `folder_import` (`folder`, `path`, `to` in
  the workspace, default `imports/<name>`), `folder_export` (`folder`, `path`,
  `from` in the workspace; replacing a file needs `overwrite:true` and the
  user's approval). Files up to 50 MB.
- Shared into Mike: when the user shares text or files from another app, they
  arrive as a new chat with the text in the composer and the files attached.
- `communications`: `list_notifications`, `notification_reply`,
  `notification_press`, `notification_dismiss`, `read_sms`, `read_call_log`,
  `send_sms`, `place_call`, `draft_sms`, `draft_email`, `dial`,
  `notification_access_status`, `open_notification_access_settings`.
- `apps_settings`: `list_apps`, `app_info`, `open_app`, `open_app_settings`,
  `permission_status`, `request_permissions`, `open_special_access`,
  `open_system_setting`.
- `system_controls`: `status`, `torch`, `volume`, `ringer`, `dnd`, `brightness`,
  `screen_timeout`, `auto_rotate`, `clipboard_get`, `clipboard_set`, `location`,
  `media_status`, `media_control`, `usage`, `get_setting`, `put_setting`.

Every call needs `operation`. Unknown operations and fields are refused. Results
are typed JSON with `ok`, `tool` and `operation`. Lists are capped; narrow the
query instead of asking for all phone data.

Examples:

```text
contacts(operation="search", query="Dana", limit=5)
calendar(operation="list", startMs=..., endMs=..., limit=20)
files_media(operation="search", kind="image", name="receipt", limit=10)
apps_settings(operation="list_apps", query="maps", limit=10)
```

Workspace operations only see the current run workspace. Paths must be relative.
They cannot delete, escape with `..`, or use `file://`. Media open/share accepts
only `content://media/...` URIs returned by `files_media` list or search, or by `copy_file` into `phone:`.

## Permissions: one system request

Do not ask in chat first. If a read fails with `errorType:"permission_denied"`:

1. Pass its `permissions` array once to
   `apps_settings(operation="request_permissions", permissions=[...])`.
2. Android shows its normal permission dialog. The user answers there.
3. If granted, repeat the exact call in `retry.tool` + `retry.arguments`.

Only missing allowlisted permissions are requested. There is no extra Mike
approval card. A denial is final for this attempt; do not loop.

## Direct actions, in the background

These work without taking the screen, so prefer them over walking an app:

```text
communications(operation="list_notifications", package="com.whatsapp", limit=10)
communications(operation="notification_reply", key="<key from the list>", text="On my way")
communications(operation="read_sms", box="inbox", address="Dana", limit=5)
communications(operation="send_sms", to="+972...", body="...")
contacts(operation="create", name="Dana Levi", phone="+972...")
calendar(operation="create", title="Dentist", beginMs=..., endMs=...)
system_controls(operation="volume", stream="media", percent=30)
system_controls(operation="dnd", mode="priority")
system_controls(operation="location")
system_controls(operation="media_control", command="pause")
```

- Answer a message from its notification with `notification_reply`. Use `key`
  from `list_notifications`. Keys go stale after a notification changes, so list
  again on `not_found`.
- `notification_reply`, `send_sms` and `place_call` leave the phone. They go
  through the same send approval as any chat-app send. A notification action
  button, `calendar delete` and `put_setting` ask for approval too.
- A `grant_missing` error names the special access to open:
  `apps_settings(operation="open_special_access", kind="<grant>")`. The user turns
  it on there (write_settings, notification_policy, usage_access).
- `notification_access_missing` means the user has to enable Hey Mike in
  notification access first: `communications(operation="open_notification_access_settings")`.
- A background `clipboard_get` can return nothing: Android only lets the app in
  front read the clipboard.

## Visible drafts

`create_draft`, `draft_sms`, `draft_email`, `dial`, `open`, `share`, and the
Settings screens open a visible Android screen and leave the last step to the
user. Use them when the user wants to check before saving or sending.

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
