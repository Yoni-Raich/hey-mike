# Mike Connect plan for <app>

User outcome: <what the user wants to accomplish>
Inspected source/version: <workspace and relevant version; no secrets>

## Evidence and app boundaries

<UI/domain/repository/storage/job paths with file and symbol references.
State what is observed and what is still an assumption.>

## Candidate capabilities

| Name | User outcome | Ready / needs app work / defer | Access / UI | Owner symbol and evidence | Input/output bounds |
| --- | --- | --- | --- | --- | --- |

## Selected first slice

<List the selected operations and an actual end-to-end user flow.
For each, give bounded JSON argument/result examples, permissions and account
ownership checks, mutation boundary, verification read, cancellation, retry and
unknown-outcome behavior. Explain why a sensitive action needs that scope.>

## Product work before integration

<Describe missing domain features separately from SDK wiring. For a creative
capability, identify the editable representation, validation, persistence and
renderer needed to create a new result. Include expected changes and impact.>

## Validation and unresolved decisions

<Meaningful checks for handlers, invalid input, persistence, privacy/ownership,
UI handoff and real Binder trust. Separate host tests, compiled package,
installed metadata and physical behavior. List only decisions blocking the
chosen scope; this document itself is not permission to widen the task.>
