---
name: media-output-check
description: Verify a locally exported video against its requested duration and resolution before reporting completion.
---

# Check the exported video

After editing/exporting in an app such as CapCut:

1. Find the exact new export with `files_media search`. Use its MediaStore URI, not the project preview or an older export.
2. Call `files_media info` for that URI. Check `durationMs`, `width`, `height` and `rotationDegrees` against the user's target. For a 4-second 720p portrait output, expect about 4000 ms and 720 x 1280. Allow only a small frame-duration tolerance. A missing value or `metadataStatus:unavailable` is unknown; never invent it.
3. Open the exported file in an external viewer. Check the beginning, transitions, text and final frames. Look for a default end card, blank tail or overly long text layer. Metadata does not prove visual content or full decode.
4. If it fails, return to the QA draft and repair the specific issue, export again, then verify the new URI. Retain evidence of failed exports. Do not publish, send, buy features, log in or delete user projects as part of verification.
5. Report the measured output and any remaining limit. If returning to Mike produces `masking.reason:agent_own_ui`, explain the deliberate masking; do not repeatedly reopen it or diagnose a black-screen crash from that result.
