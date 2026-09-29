package dev.androidagent.remote

import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.ExternalChatgptTokens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads only the active phone account. The refresh token never leaves the phone. */
class PhoneAccountTokens(private val phone: CodexEngine, private val authFile: File) {
    suspend fun current(refresh: Boolean = false, previousAccountId: String? = null): ExternalChatgptTokens {
        if (refresh) phone.refreshAccountToken()
        val tokens = withContext(Dispatchers.IO) { fromAuthFile(authFile) }
        check(previousAccountId == null || previousAccountId == tokens.accountId) {
            "Mike's account changed during the remote run. Start a new turn."
        }
        return tokens
    }

    companion object {
        internal fun fromAuthFile(file: File): ExternalChatgptTokens {
            val root = runCatching { Json.parseToJsonElement(file.readText()).let { it as? JsonObject } }.getOrNull()
            check(root?.string("auth_mode") == "chatgpt") {
                "Sign in to ChatGPT in Mike before using a computer chat."
            }
            val saved = root["tokens"] as? JsonObject
            val access = saved?.string("access_token").orEmpty()
            val account = saved?.string("account_id").orEmpty()
            check(access.isNotBlank() && account.isNotBlank()) {
                "Mike cannot use this phone sign-in on a computer. Sign in again in Mike."
            }
            return ExternalChatgptTokens(access, account)
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
