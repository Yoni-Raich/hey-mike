# Mike Connect developer kit evidence — 2026-10-18

Source worktree: `feat/mike-connect-sdk`, base `c33b168`. Existing integration
changes remain uncommitted and other worktrees were left alone. This change
adds public developer-kit source, packaging/tests, documentation and links.
It does not alter SDK binary code, installed apps, grants or runtime skills.

## Output

`artifacts/connect-distribution/mike-connect-developer-kit-1.0.0-preview.1.zip`
contains 82 entries, 209272 bytes, with SHA-256:

```text
d9cce4828b5a693fc2f0666437e01206ae1d84d533a4d6fdc2fae2933dd7ffeb
```

An extracted copy and `.zip.sha256` are alongside it. `release.json` records
the SDK version 1.0.0, binding 1, MCP profile 2026-07-28, exact binary hashes,
dirty-source revision and `local-preview` status. All payload files have SHA-256
entries in `MANIFEST.sha256`. Reusing an existing bundle version is refused.

Unchanged public binary SHA-256 values:

| Maven artifact | SHA-256 |
| --- | --- |
| connect-sdk:1.0.0 AAR | 56c4136ea3697e3ae6096e1c79decdb215ec53c426b37b165b90cb8b9de0de05 |
| connect-protocol:1.0.0 JAR | cf770e9b14905751a712ef330eb76bb78e042607a1efd443a8f597b6e6c745fb |
| connect-sdk-testing:1.0.0 AAR | d5b441ec2115ccbca8d93df2c5a71a265f89593dcdcc2d3b8243c49f6b23543d |

The earlier `artifacts/mike-connect-sdk-1.0.0-local.zip` still hashes to
`2542e6e6619be464546c2828a7b70c1c238363d209393dc267726d6ea5a540aa`.

## Checks

```text
python -X utf8 -m unittest tools.test_package_mike_connect -v
python -X utf8 <skill-creator>/scripts/quick_validate.py connect-sdk/developer-kit/skills/mike-connect-app-audit
python -X utf8 <skill-creator>/scripts/quick_validate.py connect-sdk/developer-kit/skills/mike-connect-integrate
python -X utf8 tools/package_mike_connect.py --bundle-version 1.0.0-preview.1
python -X utf8 tools/package_mike_connect.py --verify artifacts/connect-distribution/mike-connect-developer-kit-1.0.0-preview.1
git diff --check
```

Both skill frontmatter validators pass. Each skill's local links also resolve
with only its own folder as the payload. The packager verifies the real Maven
artifacts and their sidecars/POM/Gradle metadata; tests cover corruption, stale
metadata, private dependencies, missing references, machine paths, unwanted
private files, manifest tampering, path traversal and immutable outputs.
Final packaging test result: 12 passed in 1.714s. `git diff --check` passed.

The first test run found CRLF-sensitive skill-name matching in the packager;
it now uses splitlines. The system skill validator's Windows locale decoding
needed `python -X utf8`. These fixes do not alter the packaged skills or binaries.

The ZIP was extracted into a fresh system-temp directory, with no source
project dependencies. Its example was compiled using the repository wrapper
only as a Gradle launcher:

```text
gradlew.bat -p <extracted-kit>/examples/preferences-provider :app:assembleDebug :app:lintDebug
  --no-daemon --console=plain -g <isolated-gradle-cache>
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=2
```

Environment: JDK 21, Android platform 35, Gradle 8.9, AGP 8.7.3, Kotlin 2.1.0.
Result: BUILD SUCCESSFUL in 28s, 44 actionable tasks. The first launch had a
PowerShell argument-splitting error; quoting the `-P` property fixed the launch.
The actual example sources compiled and lint passed. Log:
`artifacts/connect-distribution/external-consumer-build.log`.
The example lint report contains zero errors/fatals and seven warnings; no
baseline or weakened error gate was added.

## Limits

No new model-driven behavioral benchmark of these skills was run. No app was
installed and no physical caller/consent/UI flow was tested during this task.
The current SDK still defaults to a dev test identity, with additive trust API.
The kit is not Maven-Central-ready: production trust policy, owned namespace,
complete POM metadata, Javadoc artifacts, PGP signing and release CI need their
own work. No external release, tag, commit or upload was created.
