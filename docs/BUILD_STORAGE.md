# Local build storage on Windows

All Hey Mike worktrees on this PC use one storage root:

`C:\Users\SHIRA\Documents\AI\Android-agent-use\build`

Each worktree has a bucket named by a hash of its full path. Module output
stays separate inside that bucket: `app`, `core`, `runtime`, and the other
modules. `.hey-mike-build.json` records the source worktree and finish time.
The latest output is reused on the next build of that worktree; this is not an
archive of every build invocation.

## Install or change the size

Run from a worktree that has these tools:

```powershell
.\tools\build-storage\install.ps1 -Repository C:\Users\SHIRA\Documents\AI\Android-agent-use -MaxGiB 5
```

The installer saves the config and helper copies under
`%USERPROFILE%\.gradle\hey-mike-build-storage`. It installs one init script
under `%USERPROFILE%\.gradle\init.d\hey-mike-build-storage.gradle`.
If you use a different `GRADLE_USER_HOME`, pass that path as `-GradleHome`.
The helper copies keep working when the source worktree is removed.
No existing worktree source file or build directory is moved or removed.

The init script checks the actual Git common directory. Existing and new
worktrees of this repository match, even outside the main folder. Other
repositories and GitHub CI keep their normal Gradle output paths. This is a
local PC setting, not an APK change or a global build policy for other apps.
Use `--no-configuration-cache`; the active-build tracking needs Gradle's
build lifecycle callbacks. Normal project `.gradle` caches and shared Gradle
dependency caches are not part of the 5 GiB build-output target.

## Cleanup

The saved target is **5 GiB (5,368,709,120 bytes)**. Before and after builds,
the manager removes the oldest completed, inactive worktree bucket until
storage is within this target. It checks the bucket owner and resolved path,
refuses links/junctions, and holds an exclusive cleanup handle while deleting.
Active builds hold a Java file lease. A short start reservation covers the gap
before that lease opens; an interrupted start reservation expires in 10 minutes.
The current build bucket is always kept, including its APK and test output.

This is a cleanup target, not an NTFS disk quota. Concurrent builds or one
large current build can exceed it temporarily. If no safe bucket can be
removed, the manager reports the excess and keeps active/current output.
It never removes source files, release `artifacts/`, personal data, unmarked
folders, or the old module build folders that predate this setting.

```powershell
# Read current size and the saved target.
& "$env:USERPROFILE\.gradle\hey-mike-build-storage\manage.ps1" -Mode status

# Apply the same cleanup policy now, keeping any active build.
& "$env:USERPROFILE\.gradle\hey-mike-build-storage\manage.ps1" -Mode cleanup
```

`cleanup` without a current build may remove any completed bucket, including
the newest if it is the only bucket and exceeds the target. Copy release APKs
you need to preserve into the existing ignored `artifacts/` folder first.

## Runtime staging and APK paths

`prepareCodexRuntime` uses the source worktree's own `prepare_runtime.py` via
`stage_runtime.py`. The wrapper redirects generated native libraries, assets,
archive downloads and extraction into that worktree's bucket. It preserves
the worktree's pinned versions, URLs, hash checks and binary patches. Unknown
staging commands fail rather than silently using an old local output path.

Find the real APK using Gradle's build directory or its output metadata.
Do not assume a local `app/build/outputs/...` path on this PC. For example:

`build\<worktree-id>\app\outputs\apk\dev\debug\app-dev-debug.apk`

`clean` still clears only that project's module outputs. Do not use
`git clean -fdX` or delete the whole central root while a build is running.

## Checks

```powershell
python -m unittest tools.test_build_storage tools.test_prepare_runtime -v
.\gradlew.bat :core:test :app:assembleDevDebug --no-daemon --max-workers=2
git diff --check
```

The cleanup tests use disposable folders. They cover age order, active leases,
start reservations, current output, unmanaged files, invalid roots, junctions,
runtime redirection and preservation of pinned runtime metadata. A successful
debug build proves staging and output routing, not phone runtime behavior.
