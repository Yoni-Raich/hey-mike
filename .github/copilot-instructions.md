# Hey Mike

Read AGENTS.md, PROGRESS.md, and docs/ARCHITECTURE.md before work.

Build: `./gradlew :app:assembleDevDebug` (Windows: `./gradlew.bat`). Tests: `./gradlew :core:test`. Lint: `./gradlew :app:lintDevDebug`.

These commands are the intended gates; see PROGRESS.md for the latest execution evidence. Keep the module contracts in core stable and device calls inside device-tools. Development integration uses dev; validated releases use main. Never include secrets or generated artifacts in source commits.
