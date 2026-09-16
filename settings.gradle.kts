pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "Hey Mike"
include(":app", ":core", ":a11y", ":engine-codex", ":runtime", ":workspace", ":adb", ":device-tools", ":overlay", ":voice", ":automations")
