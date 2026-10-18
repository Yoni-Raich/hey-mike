---
name: files-across-devices
description: Move files between this chat, the phone's storage and the user's computers with copy_file, then act on the copy — install an APK built on a computer, send or open a computer file on the phone, bring a phone photo to a computer. Read it whenever a task needs a file that is somewhere else.
---

# Files across devices

One tool copies files: `copy_file(from, to, replace)`. Other tools act on a
file once it is where they can reach it. A task that needs a file from
somewhere else is a copy followed by an action, never a tool of its own.

## Addresses

| Address | Where |
| --- | --- |
| `chat:<path>` | This chat's folder on the phone. `chat:` alone is the folder itself. |
| `phone:<path>` | The phone's shared storage: `phone:Download/`, `phone:Pictures/Receipts/`, `phone:DCIM/Camera/a.jpg`. |
| `content://...` | A phone file by the uri `files_media` gave you. |
| `<Computer>:<path>` | A file on a saved computer, by its name: `Pc:C:\Users\me\app.apk`, `Server:/home/me/a.pdf`. `computers(mode="status")` lists the names. |
| a path with no place | Where your shell runs: this chat's folder in a phone chat, the project folder in a computer chat. |

A destination that ends in `/` keeps the file's name. Nothing is overwritten
unless `replace: true`. A copy lands whole or not at all, shows its progress
to the user, and stops with Stop.

The reply names the file's new address in `to`. A copy into `phone:` also
returns `uri`, the `content://media/...` uri the phone's tools take.

## Recipes

**Install an app built on a computer.**
1. `copy_file(from="Pc:C:\\src\\app\\build\\outputs\\apk\\debug\\app-debug.apk", to="chat:")`
2. `install_apk(file="chat:app-debug.apk")` with the address step 1 returned.

If the install fails, fix the cause and run `install_apk` again with the same
`chat:` file. Do not copy again: the file is already on the phone.
`install_apk` needs Wireless ADB; if the runtime snapshot lists it as off, say
so and leave the APK in the chat.

**Send or open a computer file on the phone** (WhatsApp, email, a viewer).
1. `copy_file(from="Pc:C:\\Users\\me\\report.pdf", to="phone:Download/")`
2. `files_media(operation="share", uri=<uri from step 1>)` opens the share
   sheet; `operation="open"` opens it in a viewer. The user picks the app and
   presses Send.

**A phone photo or file to a computer.**
1. `files_media(operation="search", kind="image", name="receipt", limit=5)`
2. `copy_file(from=<its uri>, to="Pc:C:\\Users\\me\\Pictures\\")`

**A chat file to the phone's Downloads, or back.**
`copy_file(from="chat:out/summary.pdf", to="phone:Download/")`, or
`copy_file(from="phone:Download/list.csv", to="chat:")` to work on a file in
the chat.

## APKs from a computer: one `copy_file`, never a manual transfer

(Dev build only. This section is not in the official release.)

`copy_file` already sends an APK from a computer as a **delta**: the phone and
the computer compare SHA-256 hashes, and only the changed bytes travel
(about 0.4-3 MB instead of ~330 MB). The patch is applied on the phone and the
full APK is checked by hash before it appears in the chat folder. This is the
only way to move a build to the phone.

- Always call `copy_file(from="<Computer>:<apk path>", to="chat:")` once. Do
  not use `adb push`, `scp`, base64, `shell` copies, or split the file yourself:
  those send all ~330 MB.
- The first APK on a phone is a full copy. The phone then keeps the installed
  APK and earlier copies as delta bases, so the next build is small. Do not
  delete files in `transfer-cache` or the chat folder to "save space".
- A copy lands whole or not at all. If the reply is an error or the chat
  dropped, first look at the chat folder (`files_media ws_list`) and compare
  the file size or SHA-256 with the source. If it is there and matches, go on
  to `install_apk`. Do not copy it again.
- Stop never starts a full fallback copy by itself. If a delta cannot be used
  (no Python 3.9+ on the computer, missing base), the copy falls back to a full
  transfer on its own; that is slow, not wrong.
- Installing is a separate step: `install_apk(file="chat:<name>.apk")`, which
  needs Wireless ADB. Check the installed version with
  `apps_settings(app_info)` and say what you saw. Android refuses a downgrade;
  do not uninstall to get around it.

## When a copy fails

- `which_place`: the path names no place (a `C:\` path in a phone chat). Put
  the computer's name in front: `Pc:C:\...`.
- `exists`: a file is already there. Ask before `replace: true` if the user may
  still want the old one.
- A computer path with no folder to be relative to: in a phone chat, give the
  full path on the computer.
- A phone file you cannot read: `files_media(operation="permission_status")`,
  then the permission request it describes.
