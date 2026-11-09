# Conversation-based discovery intake

The source changes are based on the inbox-backpressure branch at `f1ee0a30`,
not a new integration/release build. They replace message fragments with one
open row per conversation or app. All measurements below are local JVM/build
checks. No private phone messages or screenshots are stored here.

## Checked behavior

| Case | Evidence |
|---|---|
| 1,000 assistant progress messages create no discovery work | `SignalRowsTest.aThousandProgressMessagesOpenNothingAndOneRequestOpensOneTextFreeRow` |
| A request plus final reply forms one text-free queue pointer | Same test; final-only transcript assertion |
| Equal chat titles remain separate sessions | `oldHistoryAndChildTaskPromptsDoNotBecomeOwnerRequests` |
| Owner feedback and marked task results enter the immediate lane | `ownerCorrectionsAndMarkedTaskResultsAreImmediateWithoutAProgressLoop` |
| 1,000 reposted app-status deliveries create one row and one distinct-body count | `aRepostedAppStatusStaysOneRowAndDoesNotReturnAfterProcessing` |
| An app repost stays deduped after processing/restart | Core status test and SQLite migration/reopen test |
| A conversation overflow keeps other conversations intact | `notificationOverflowStaysInsideItsOwnConversationAndPreservesEqualWordsAtDifferentTimes` |
| Reserved slots and remaining daily budget serve people/owner input | `peopleAndOwnerFeedbackHaveRoomAndBudgetWhenBackgroundIsFull` |
| A full background feed cannot hide later owner feedback | `ownerInputCanPassAFullBackgroundFeedWithoutAcknowledgingTheBlockedRequest` |
| Upgrade does not reimport ordinary historical chat fragments | Core and SQLite migration tests |
| Historical scans label unmarked old replies and use only the last reply per request | `explicitHistoryScanLabelsUnknownOldRepliesAndKeepsOnlyTheLastReplyForEachRequest` |
| A new edit to an older message qualifies after the sequence cutoff | `theSequenceBoundarySkipsMigratedHistoryButAdmitsANewEditToAnOldRequest` |
| Excluding an app-notification title removes that old title from an aggregated app row | `aNewSourceExclusionAlsoRemovesAnOldTitleFromAnAppRow` |
| A pass cannot delete newly merged input | `SignalRowsPersistenceTest.aCommitCannotDeleteNewInputMergedWhileScoutWasReading` |
| Commit is idempotent, including source review counters | Same SQLite test |
| Cursor, row and capture counters roll back together | `captureCursorCountersAndRowsRollBackTogether` |
| Final-phase promotion updates both cached transcript and durable feed, once | `aFinalPhasePromotionReachesBothTheLiveTranscriptAndDurableFeed` plus coordinator segment assertions |
| Source counters can support a settings proposal but cannot apply it or teach an owner rule | `SourceReviewTest` |
| Existing suppression/receipt, source-policy, Stop and execution rules remain covered | Full scoped core/workspace/app suites |

## Commands and results

Windows, JDK 21, Android SDK, isolated per-task Gradle home with shared
dependency-cache junctions. The isolated build-storage configuration used a
high ceiling so the task did not evict another worktree's output.

```powershell
.\gradlew.bat :core:test :workspace:testDebugUnitTest :automations:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:lintDevDebug --no-daemon --console=plain
git diff --check
```

Selected XML counts: core 924; workspace 63; automations 19; app 172.
Total 1,178 cases, zero failures/errors, 14 existing workspace skips.
App lint has zero errors, 21 warnings and four information findings.

The packaged artifact is local Dev Debug using repository version 1183 /
`0.14.0-dev.unified`, with the local debug key. It was not copied to a phone
or published as an update. Any later phone delivery must increase the version
above the installed code (1187 in the supplied hosting snapshot).

## Remaining proof

No physical phone/UI run, screenshot fixture, migration of the live task DB,
overnight capture/Scout/Coach cycle, real source-exclusion proposal, Doze
endurance or full release gate was run. Build/tests do not prove those paths.
In particular, the source-counter prompts are tested contracts; no measured
claim is made about how often a real model will correctly recommend exclusion.
Historical replies written before phase metadata are explicitly labelled as
unverified final status during a user-requested scan.
