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

package dev.androidagent.app.panels

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dev.androidagent.core.SavedPanelVault
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Saved panels, kept where the agent cannot forge or replay them.
 *
 * The agent's shell runs as this app's Linux user and can write the app's
 * preferences, so a panel list it wrote would be a panel the user never
 * approved. The list is signed with an HMAC key held in the Android Keystore,
 * as send grants are. Each write makes a new key and deletes the old one, so
 * a copy saved earlier (with a panel the user has since removed) no longer
 * verifies when it is put back.
 */
class KeystoreSavedPanelVault(
    context: Context,
    private val prefsName: String = PREFS,
    /** Must not start with another vault's prefix: each write deletes every other key under its own. */
    private val aliasPrefix: String = ALIAS_PREFIX,
) : SavedPanelVault {

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): SavedPanelVault.Read {
        val body = prefs.getString(KEY_BODY, null) ?: return SavedPanelVault.Read.Empty
        val mac = prefs.getString(KEY_MAC, null) ?: return SavedPanelVault.Read.Invalid("no signature")
        val alias = prefs.getString(KEY_ALIAS, null)?.takeIf { it.startsWith(aliasPrefix) }
            ?: return SavedPanelVault.Read.Invalid("no key")
        val key = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
            ?: return SavedPanelVault.Read.Invalid("its key is gone")
        val valid = runCatching {
            MessageDigest.isEqual(Base64.decode(mac, Base64.NO_WRAP), sign(key, body))
        }.getOrDefault(false)
        return if (valid) SavedPanelVault.Read.Valid(body) else SavedPanelVault.Read.Invalid("signature does not match")
    }

    @Synchronized
    override fun write(body: String) {
        try {
            val alias = aliasPrefix + UUID.randomUUID().toString()
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }
            val written = prefs.edit()
                .putString(KEY_BODY, body)
                .putString(KEY_MAC, Base64.encodeToString(sign(key, body), Base64.NO_WRAP))
                .putString(KEY_ALIAS, alias)
                .commit()
            if (!written) {
                runCatching { keyStore().deleteEntry(alias) }
                return
            }
            // Only the key that signs the list on disk survives.
            val keys = keyStore()
            keys.aliases().toList().filter { it.startsWith(aliasPrefix) && it != alias }.forEach { old ->
                runCatching { keys.deleteEntry(old) }
            }
        } catch (error: Exception) {
            // The panel list stays in memory; it is not on disk until a write works.
            Log.w(TAG, "Saved panels were not written: ${error.javaClass.simpleName}")
        }
    }

    private fun sign(key: SecretKey, body: String): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key)
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val TAG = "SavedPanels"
        private const val PREFS = "saved_panels"
        private const val KEY_BODY = "panels"
        private const val KEY_MAC = "mac"
        private const val KEY_ALIAS = "key"
        private const val ALIAS_PREFIX = "hey_mike_saved_panels_"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Where saved panels keep their own state, signed the same way. Its prefix shares no start with the list's. */
        fun forState(context: Context) = KeystoreSavedPanelVault(context, "saved_panel_state", "hey_mike_panel_state_")
    }
}
