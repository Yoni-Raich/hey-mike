/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun compareVersionsCorrectlyEvaluatesSemverOrder() {
        assertTrue(AppUpdateManager.compareVersions("0.1.2", "0.1.1") > 0)
        assertTrue(AppUpdateManager.compareVersions("0.2.0", "0.1.9") > 0)
        assertTrue(AppUpdateManager.compareVersions("1.0.0", "0.9.9") > 0)
        assertTrue(AppUpdateManager.compareVersions("v0.1.2", "0.1.1") > 0)
        assertTrue(AppUpdateManager.compareVersions("v0.1.2-fix", "0.1.1") > 0)

        assertEquals(0, AppUpdateManager.compareVersions("0.1.1", "0.1.1"))
        assertEquals(0, AppUpdateManager.compareVersions("v0.1.1", "0.1.1"))

        assertTrue(AppUpdateManager.compareVersions("0.1.0", "0.1.1") < 0)
        assertTrue(AppUpdateManager.compareVersions("0.1.0-fix", "0.1.1") < 0)
        assertTrue(AppUpdateManager.compareVersions("0.0.9", "0.1.0") < 0)
        assertTrue(AppUpdateManager.compareVersions("1.0.0", "1.0.0-beta") > 0)
        assertTrue(AppUpdateManager.compareVersions("1.0.0-beta", "1.0.0") < 0)
    }

    @Test
    fun parseReleaseJsonDetectsNewerApkRelease() {
        val json = """
        {
          "tag_name": "v0.1.2",
          "body": "Bug fixes and improvements",
          "assets": [
            {
              "name": "android-agent-0.1.2.apk",
              "browser_download_url": "https://github.com/Yoni-Raich/hey-mike/releases/download/v0.1.2/android-agent-0.1.2.apk",
              "size": 250000000
            },
            {
              "name": "source.tar.gz",
              "browser_download_url": "https://github.com/.../source.tar.gz",
              "size": 1000
            }
          ]
        }
        """.trimIndent()

        val info = AppUpdateManager.parseReleaseJson(json, currentVersion = "0.1.1")
        assertNotNull(info)
        info!!
        assertEquals("0.1.2", info.latestVersionName)
        assertEquals("v0.1.2", info.latestTag)
        assertEquals("Bug fixes and improvements", info.releaseNotes)
        assertEquals("android-agent-0.1.2.apk", info.apkName)
        assertEquals(250000000L, info.apkSize)
        assertTrue(info.isUpdateAvailable)
    }

    @Test
    fun parseReleaseJsonMarksSameOrOlderVersionAsUpToDate() {
        val json = """
        {
          "tag_name": "v0.1.1",
          "body": "Current release notes",
          "assets": [
            {
              "name": "android-agent-0.1.1.apk",
              "browser_download_url": "https://github.com/Yoni-Raich/hey-mike/releases/download/v0.1.1/android-agent-0.1.1.apk",
              "size": 289000000
            }
          ]
        }
        """.trimIndent()

        val info = AppUpdateManager.parseReleaseJson(json, currentVersion = "0.1.1")
        assertNotNull(info)
        assertFalse(info!!.isUpdateAvailable)
    }

    @Test
    fun parseReleaseJsonReturnsNullWhenNoApkAssetFound() {
        val json = """
        {
          "tag_name": "v0.1.2",
          "body": "No APK attached",
          "assets": [
            {
              "name": "source.zip",
              "browser_download_url": "https://example.com/source.zip",
              "size": 5000
            }
          ]
        }
        """.trimIndent()

        val info = AppUpdateManager.parseReleaseJson(json, currentVersion = "0.1.1")
        assertNull(info)
    }

    @Test
    fun parseNightlyReleaseJsonDetectsNewCommitByVersionCode() {
        val json = """
        {
          "tag_name": "dev-nightly",
          "body": "Automated build from dev.\n\nCommit: abc123\nVersion code: 1002\nVersion name: 0.2.2-dev.abc123\nPackage: dev.androidagent.app.dev",
          "assets": [{
            "name": "hey-mike-dev-nightly.apk",
            "browser_download_url": "https://github.com/Yoni-Raich/hey-mike/releases/download/dev-nightly/hey-mike-dev-nightly.apk",
            "size": 1234
          }]
        }
        """.trimIndent()

        val info = AppUpdateManager.parseNightlyReleaseJson(json, 1001, "dev.androidagent.app.dev")
        assertNotNull(info)
        info!!
        assertEquals("0.2.2-dev.abc123", info.latestVersionName)
        assertEquals(1002L, info.latestVersionCode)
        assertEquals("abc123", info.commitSha)
        assertTrue(info.isUpdateAvailable)
    }

    @Test
    fun parseNightlyReleaseJsonRejectsAnotherPackage() {
        val json = """
        {
          "tag_name": "dev-nightly",
          "body": "Commit: abc123\nVersion code: 1002\nVersion name: 0.2.2-dev.abc123\nPackage: dev.androidagent.app.dev",
          "assets": [{
            "name": "hey-mike-dev-nightly.apk",
            "browser_download_url": "https://github.com/Yoni-Raich/hey-mike/releases/download/dev-nightly/hey-mike-dev-nightly.apk",
            "size": 1234
          }]
        }
        """.trimIndent()

        assertNull(AppUpdateManager.parseNightlyReleaseJson(json, 1001, "dev.androidagent.app"))
    }
}
