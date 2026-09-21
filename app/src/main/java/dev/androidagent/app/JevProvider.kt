package dev.androidagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.androidagent.core.JevCandidate
import dev.androidagent.core.JevDecision
import dev.androidagent.core.JevDecisionProvider
import dev.androidagent.core.JevDecisionRequest
import dev.androidagent.core.JevProviderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Jev API token encrypted at rest. The token is never part of UI
 * state, tool arguments, session files, or diagnostics.
 */
class JevTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(loadState())

    fun state(): StateFlow<JevProviderState> = state.asStateFlow()

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        state.value = loadState()
    }

    @Synchronized
    fun saveToken(raw: String) {
        val token = raw.trim()
        if (token.isEmpty()) {
            clearToken()
            return
        }
        val encrypted = encrypt(token)
        prefs.edit().putString(KEY_TOKEN, encrypted).apply()
        state.value = loadState()
    }

    @Synchronized
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        state.value = loadState()
    }

    @Synchronized
    fun readToken(): String? = prefs.getString(KEY_TOKEN, null)?.let { encrypted ->
        runCatching { decrypt(encrypted) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun loadState(): JevProviderState = JevProviderState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        tokenConfigured = prefs.getString(KEY_TOKEN, null)?.isNotBlank() == true,
    )

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
        ).joinToString(".")
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "invalid encrypted Jev token" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "jev_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "encrypted_token"
        const val KEY_ALIAS = "hey_mike_jev_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Android network adapter for the official TypeSafe System One endpoint. */
class AndroidJevProvider(context: Context) : JevDecisionProvider {
    private val tokens = JevTokenStore(context)
    override val state: StateFlow<JevProviderState> = tokens.state()

    fun setEnabled(enabled: Boolean) = tokens.setEnabled(enabled)
    fun saveToken(token: String) = tokens.saveToken(token)
    fun clearToken() = tokens.clearToken()

    override suspend fun choose(request: JevDecisionRequest): JevDecision = withContext(Dispatchers.IO) {
        val token = tokens.readToken() ?: throw IOException("Jev token is unavailable")
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.outputStream.use { output ->
                output.write(requestBody(request).toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Jev request failed with HTTP $code")
            parseResponse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(request: JevDecisionRequest): String = buildJsonObject {
        put("model", MODEL)
        put("state", buildState(request))
        put("questions", buildJsonObject {
            put("next_action", buildJsonObject {
                put("type", "choice")
                put("instructions", "Which one legal next UI action best advances the goal? Choose ESCALATE if none fits.")
                put("criteria", buildJsonObject {
                    request.candidates.forEach { candidate -> put(candidate.key, candidate.description) }
                })
            })
        })
    }.toString()

    private fun buildState(request: JevDecisionRequest): String = buildString {
        append("GOAL:\n").append(request.goal)
        append("\n\nFRESH UI OBSERVATION:\n").append(request.observation)
        append("\n\nOnly choose one of the listed candidate keys. Do not invent an action.")
    }

    private fun parseResponse(body: String): JevDecision {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IOException("Jev returned invalid JSON") }
        val answer = root["answers"]?.jsonObject?.get("next_action")?.jsonObject
            ?: throw IOException("Jev response has no next_action answer")
        val choice = answer["choice"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("Jev response has no choice")
        val confidence = answer["confidence"]?.jsonPrimitive?.doubleOrNull
            ?: throw IOException("Jev response has no confidence")
        val probabilities = answer["probabilities"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive.doubleOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()
        return JevDecision(
            choice = choice,
            confidence = confidence.coerceIn(0.0, 1.0),
            probabilities = probabilities,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: MODEL,
        )
    }

    private companion object {
        const val API_URL = "https://api.typesafe.ai/v1/systemone"
        const val MODEL = "jev-latest"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
