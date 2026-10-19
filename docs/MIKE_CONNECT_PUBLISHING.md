# Publishing Mike Connect

Keep SDK source in the Mike repository for now. The host, protocol and provider
SDK can be reviewed together, and real compatibility changes remain visible in
one change. Consumers receive only the three public Apache-2.0 modules and the
developer kit; their apps do not depend on the Mike app or its AGPL host modules.

Use an independent SDK release line, for example `sdk-v1.0.0-preview.1`, separate
from Mike app tags. A future split into its own repository is reasonable when
it has independent maintainers or release cadence; consumers keep the same
Maven coordinates if ownership and API stay the same.

## Source and generated output

| Source path | Purpose |
| --- | --- |
| `connect-protocol/` | Protocol types, codec and schema validation |
| `connect-sdk/` | Android provider library and its public README/license |
| `connect-sdk-testing/` | In-process helper library |
| `connect-sdk/developer-kit/` | Portable documentation, two skills and standalone example |
| `tools/package_mike_connect.py` | Allowlisted preview packager and extracted-kit verifier |
| `tools/test_package_mike_connect.py` | Packaging/integrity/immutability checks |

Generate artifacts into ignored `artifacts/`, never into source or the APK's
on-device skill directory. The public developer skills are for other agents
working in another app's source. They are not Mike's runtime `connected-apps`
skill and are not auto-installed on the phone.

## Preview package

Publish the three libraries to the local Maven repository after their relevant
SDK tests pass:

```text
gradlew.bat :connect-protocol:publish :connect-sdk:publish :connect-sdk-testing:publish
python -m unittest tools.test_package_mike_connect
python tools/package_mike_connect.py --bundle-version 1.0.0-preview.1
```

The packager reads the actual protocol/SDK versions, requires all three Maven
components and source JARs, validates checksums, POM/module dependency boundaries,
and local Markdown links, then emits:

```text
artifacts/connect-distribution/
  mike-connect-developer-kit-1.0.0-preview.1/
    README.md
    LICENSE
    release.json
    MANIFEST.sha256
    maven/dev/heymike/connect/...
    skills/mike-connect-app-audit/...
    skills/mike-connect-integrate/...
    examples/preferences-provider/...
  mike-connect-developer-kit-1.0.0-preview.1.zip
  mike-connect-developer-kit-1.0.0-preview.1.zip.sha256
```

It refuses an existing output version. The old `mike-connect-sdk-1.0.0-local.zip`
and its earlier evidence stay unchanged. A generated preview records a source
revision and whether the worktree has uncommitted changes; a preview from a
dirty worktree is not evidence of a reproducible public release.

Extract into a fresh temporary folder and verify it:

```text
python tools/package_mike_connect.py --verify /path/to/extracted/mike-connect-developer-kit-1.0.0-preview.1
```

Use the extracted example, without a project dependency on Mike, to build a
consumer with Gradle 8.9/JDK 17+ and Android platform 35. Normal Google/Maven
dependencies still require a cache or network. The example is a build/usage
fixture; compiling it does not prove physical trust, grants or model behavior.

## Public release path

For the first developer preview, attach the immutable ZIP and checksum to an
SDK-tagged GitHub prerelease in the same repository, with direct links to the
two source SKILL.md files. [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
supports release notes and downloadable assets. This packaging command does
not create a remote release, tag, commit or upload.

For normal Gradle use, Maven Central is the preferred stable distribution
channel. A GitHub ZIP/local Maven repository is a useful preview bootstrap,
not a claim that `mavenCentral()` already resolves these coordinates.
GitHub Packages can be an optional staging channel when its authentication
requirements suit the audience.

Before a stable Central release, resolve and verify:

- The publisher-owned namespace for `dev.heymike.connect` (or choose a verified
  coordinate before external adoption). No ownership is assumed by local code.
- A production caller trust policy. The current default pins a local dev test
  caller; `Builder.trust` adds identities rather than replacing that default.
  Adding a production certificate alone does not remove test trust.
- Complete POM metadata for all three modules: project URL, description,
  developers, SCM and license. Add Javadoc/Dokka artifacts and PGP signatures.
  [Central requirements](https://central.sonatype.org/publish/requirements/)
  define the required publication files and metadata.
- A clean reviewed source revision, immutable version numbers, publication CI
  with protected credentials, checksums/signatures and a compatibility matrix.
- Physical conformance for approved and rejected callers, grants, cancellation,
  unknown writes and UI handoff on supported Mike/app build combinations.

Freeze published SDK versions. Update all three library versions, protocol
`SDK_VERSION`, examples/references and compatibility notes together for a later
SDK release; never republish different bytes under an external stable version.
The kit version may change for documentation/skills while the binary SDK hashes
remain identical. Keep that distinction explicit in `release.json` and notes.

## Skill checks

Run the skill creator's frontmatter validator when available and verify that
each skill remains usable after copying its complete folder to another path.
The packager checks that local references exist in the extracted kit. These
checks prove structure and portability, not the quality of every future agent
decision. Useful behavioral scenarios include a preset-only clock asked to
create a new font, an app with a well-tested repository ready for an adapter,
and a read-only audit of an app with account-scoped private data.
