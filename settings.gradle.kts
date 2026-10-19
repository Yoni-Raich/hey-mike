pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "Hey Mike"
include(":connect-protocol", ":connect-sdk", ":connect-sdk-testing", ":connect-host")
include(":samples:connect-clock")
include(":app", ":core", ":a11y", ":engine-codex", ":engine-claude", ":runtime", ":workspace", ":adb", ":device-tools", ":overlay", ":voice", ":automations", ":remote", ":web", ":mcp-loopback")
