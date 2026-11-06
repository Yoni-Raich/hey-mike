// SPDX-License-Identifier: Apache-2.0
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(providers.gradleProperty("mikeConnectRepository").orElse("../../maven").get())
            content { includeGroup("dev.heymike.connect") }
        }
    }
}
rootProject.name = "MikeConnectPreferencesExample"
include(":app")
