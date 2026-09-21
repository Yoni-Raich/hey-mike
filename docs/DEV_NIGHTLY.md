# Dev nightly builds

The `dev` flavor is installed beside the stable flavor as
`dev.androidagent.app.dev`. It checks the GitHub `dev-nightly` prerelease, while
the stable flavor continues to check the latest normal release.

The workflow runs after a push to `dev`, once per day, or manually. It checks
the current `dev` commit against the commit recorded in the release. If the
commit did not change, it does not build or replace the APK. The push trigger
means the flow also works while `main` remains the repository default branch;
the daily schedule is then an extra safety check.

The workflow builds `assembleDevDebug`. This is intentional for the current
Developer Preview: the APK is debuggable and test-signed. Android will update
an installed dev APK only when the signing key is the same, so configure these
repository secrets with the existing local debug keystore:

- `DEV_KEYSTORE_BASE64`
- `DEV_KEYSTORE_PASSWORD`
- `DEV_KEY_ALIAS`
- `DEV_KEY_PASSWORD`

Do not commit the keystore. The base64 value should be created from the same
debug keystore that signed the dev APK already installed on the phone. The
workflow gives every nightly build a higher `versionCode`, starting at 1000 so
it can update older dev APKs whose version code came from an earlier release.

The update banner still requires the normal Android install confirmation. The
app does not silently install an APK.
