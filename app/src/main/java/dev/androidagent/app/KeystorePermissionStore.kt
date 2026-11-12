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

package dev.androidagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** How much a chat may do without asking. */
enum class PermissionLevel { ASK, FULL }

/** The chats the user gave full access, and what a chat they start next gets. */
data class ChatPermissions(val fullChats: Set<String> = emptySet(), val newChats: PermissionLevel = PermissionLevel.ASK) {
    fun of(chat: String?): PermissionLevel = if (chat != null && chat in fullChats) PermissionLevel.FULL else PermissionLevel.ASK
}

/**
 * Each chat's permission level, kept where the agent cannot raise its own.
 *
 * Full access answers every approval for the chat, so it is the one setting
 * the agent must never be able to write. Its shell runs as this app's Linux
 * user and can edit the app's files, so the list is signed with a Keystore
 * key as the send grants are ([KeystoreSendGrantStore]); a list that does not
 * verify reads as "ask first" for every chat.
 */
class KeystorePermissionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<ChatPermissions> = mutable.asStateFlow()

    fun isFull(chat: String): Boolean = chat in mutable.value.fullChats

    /** The user's pick in [chat]; it is also what the next chat they start begins with. */
    @Synchronized
    fun choose(chat: String, level: PermissionLevel) {
        val current = mutable.value
        save(current.copy(fullChats = current.fullChats.with(chat, level), newChats = level))
    }

    /** A chat the user just started takes the level they last picked. */
    @Synchronized
    fun startChat(chat: String) {
        val current = mutable.value
        if (current.newChats == PermissionLevel.FULL) save(current.copy(fullChats = current.fullChats + chat))
    }

    @Synchronized
    fun forget(chat: String) {
        val current = mutable.value
        if (chat in current.fullChats) save(current.copy(fullChats = current.fullChats - chat))
    }

    private fun Set<String>.with(chat: String, level: PermissionLevel) = if (level == PermissionLevel.FULL) this + chat else this - chat

    private fun save(value: ChatPermissions) {
        val body = encode(value)
        val mac = runCatching { sign(body) }.getOrNull()
        prefs.edit().putString(KEY_BODY, body).putString(KEY_MAC, mac).apply()
        // Unsigned, it would not survive a restart; do not pretend it was granted.
        mutable.value = if (mac != null) value else ChatPermissions()
    }

    private fun load(): ChatPermissions {
        val body = prefs.getString(KEY_BODY, null) ?: return ChatPermissions()
        val mac = prefs.getString(KEY_MAC, null) ?: return ChatPermissions()
        val valid = runCatching {
            MessageDigest.isEqual(Base64.decode(mac, Base64.NO_WRAP), Base64.decode(sign(body), Base64.NO_WRAP))
        }.getOrDefault(false)
        return if (valid) runCatching { decode(body) }.getOrDefault(ChatPermissions()) else ChatPermissions()
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key())
        return Base64.encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "chat_permissions"
        private const val KEY_BODY = "permissions"
        private const val KEY_MAC = "mac"
        private const val KEY_ALIAS = "hey_mike_chat_permissions"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        internal fun encode(value: ChatPermissions): String = buildJsonObject {
            put("full", buildJsonArray { value.fullChats.sorted().forEach { add(JsonPrimitive(it)) } })
            put("newChatsFull", JsonPrimitive(value.newChats == PermissionLevel.FULL))
        }.toString()

        internal fun decode(body: String): ChatPermissions {
            val item = Json.parseToJsonElement(body).jsonObject
            return ChatPermissions(
                fullChats = item.getValue("full").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                newChats = if (item["newChatsFull"]?.jsonPrimitive?.booleanOrNull == true) PermissionLevel.FULL else PermissionLevel.ASK,
            )
        }
    }
}
