---
name: phone-apk-delivery
description: How to get a Hey Mike build onto a phone without copying the whole APK — delta copy_file, Dev Debug export, install and verify. Read it before building for, copying to, or installing on a phone, and when adding any feature that moves large files to the phone.
---

# Phone APK delivery (delta mode of work)

**Dev channel only.** Delta transfer ships in the `dev` flavor (`BuildConfig.DELTA_TRANSFER`).
The official `prod` release has no delta code path, native library, host helper or
delta skill text; it keeps the plain full copy. Never wire delta into `prod`.

A Dev Debug APK is ~330 MB; a build-to-build change is ~0.4-3 MB. Never move
the whole file when a delta is possible. The app already does this; your job
is to not bypass it.

## Rules

1. **Use `copy_file`.** From the phone chat: `copy_file(from="Pc:<apk>", to="chat:")`.
   It negotiates by SHA-256 over the existing SSH link, downloads a patch when
   it has a base, rebuilds the APK with native HDiffPatch, verifies the full
   hash and publishes atomically. Do **not** use `adb push`, `scp`, `adb install`
   of a full file over the network, base64, or a custom sender.
2. **Build with `assembleDevDebug`.** The task exports the exact signed APK into
   the host cache (`~/.hey-mike/file-transfer/v1`) and precomputes a patch
   against the previous artifact with the same file name. Keep the same output
   name (`app-dev-debug.apk`) so a base is found; a renamed APK is retained as
   an alias, but a different signing key still works because bases are opaque bytes.
3. **Bump `versionCode` for every build you send** (`-PversionCodeOverride`,
   `-PversionNameOverride`, or `version.properties`). Android refuses
   downgrades and same-code reinstalls of a different APK. Check the phone's
   current code with `apps_settings(app_info)` first. Never uninstall or clear
   data to get around a refusal (it deletes the Codex sign-in and sessions).
4. **First copy to a phone is full; later ones are deltas.** The app seeds its
   own installed APK as a base at startup. Do not clear `transfer-cache`.
5. **After a failed or dropped copy, check before retrying.** `files_media
   ws_list` on the chat, compare size and SHA-256 with the source
   (`Get-FileHash`). If it matches, go to `install_apk`; do not copy again.
6. **Installing is separate** (`install_apk` with the `chat:` address, needs
   Wireless ADB). Verify afterwards: `apps_settings(app_info)` version, and
   `sha256sum` of the installed `base.apk` equals the artifact.
7. **Report honestly.** State what was measured: bytes on the wire (UID rx
   from netstats) vs. assumed. Build/install alone prove nothing about delta;
   a delta run proves it only if the transferred bytes are small.

## Adding features that move big files

Reuse `core` `DeltaTransfers` / `ComputerPlace` and `TransferBlobStore`
(`workspace`); do not add a second transport. See `docs/ARCHITECTURE.md`
("APK delta copies") and `docs/testing/apk-delta-transfer-20261012.md`.
Python 3.9+ on the computer is needed for delta; without it the copy is a
full SFTP transfer, which is correct but slow.

## Known limits

Python-less hosts, split APKs, partial resume, and Linux strict snapshots are
not covered. Use a separate SSH connection for the transfer process (it is in
the helper already); a closed transfer process must not end the chat session.
