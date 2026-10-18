pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Mike Browser Probe"
include(":web", ":core")
project(":web").projectDir = file("../../web")
project(":core").projectDir = file("../../core")
