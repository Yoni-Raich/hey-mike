# Findings fixes and latency validation

User-authorized implementation after RUN-005. Base dev `72bbe603`. Preserve the original dirty checkout and existing phone data.

| Work | Required evidence |
|---|---|
| Workflow text call | Save, reload, bind parameters, describe and run. Invalid serialization must not replace a valid definition. |
| Computer draft | Exact whitespace/Unicode retained before acknowledgement; switch, reopen and interrupt source without sending the target draft. |
| Stop trace | A call has a result or explicit cancellation. Completed plan prefix and outputs remain; no tail dispatch or automatic replay. |
| Own UI masking | Own controls stay inaccessible. Semantic/unchanged/image results explain masking. Launch acknowledgement is not screen proof. |
| Export verification | Info reads actual duration/dimensions, omits unknown values, and a shipped procedure checks the external video and final frames. |
| Reproducible QA | Isolated cases, exact input/trace checks, active-run guard, checkpoint before Send and no automatic replay after an uncertain action. Native QA fixture supports Unicode, rebuild, delayed UI and clearly simulated negatives. |
| Engine benchmark | Same warmed synthetic fixture, same exact task/output and at least three repeats per engine. Retain failures; compare only successful outcomes and document setup differences. CapCut comparisons need their own matching-output proof. |
| UI latency | At least 20 input samples; retain raw log and gfxinfo. Median/p95/max, failed/no-draw samples, frame deadline misses. Separate dispatch-to-next-draw, frame pipeline, tool time and turn time. |

The debug evidence receiver is read-only and requires Android's DUMP permission. It is absent from release. Idle `uiautomator dump` is allowed only after the receiver proves there are no active runs; wait for accessibility to rebind before Send. During a run use raw screenshots, trace and the receiver. No clear/uninstall, accessibility grant bypass, personal sends, purchases or publishing.

`UiLatencyProbe` is opt-in in debug via `dev.androidagent.app.QA_UI_LATENCY`. It records only sample numbers, durations and statuses. Dispatch-to-next-draw does not establish displayed pixels, end-to-end navigation completion, touch hardware latency or GPU presentation. `gfxinfo framestats` supplies a different frame-pipeline measure. Do not mix them into a single latency number.
