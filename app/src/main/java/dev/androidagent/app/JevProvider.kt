package dev.androidagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.androidagent.core.JevDecision
import dev.androidagent.core.JevDecisionProvider
import dev.androidagent.core.JevDecisionRequest
import dev.androidagent.core.JevDecisionResponse
import dev.androidagent.core.JevProviderState
import dev.androidagent.core.JevDisabledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
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
    private val requestLock = Any()
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var requestCancelled = false
    private val lifecycle = java.util.concurrent.atomic.AtomicLong(0L)
    private val choosing = AtomicBoolean(false)
    private val settingsEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeDecision: Job? = null
    override val state: StateFlow<JevProviderState> = tokens.state()
    override val availabilityEpoch: Long get() = settingsEpoch.get()

    fun setEnabled(enabled: Boolean) {
        synchronized(requestLock) {
            if (enabled == state.value.enabled) return
            settingsEpoch.incrementAndGet()
            tokens.setEnabled(enabled)
            invalidateLocked(JevDisabledException(), cancelled = false)
        }
    }
    fun saveToken(token: String) = synchronized(requestLock) {
        settingsEpoch.incrementAndGet()
        tokens.saveToken(token)
        invalidateLocked(JevDisabledException("Jev configuration changed. Continue with ordinary device tools."), cancelled = false)
    }
    fun clearToken() = synchronized(requestLock) {
        settingsEpoch.incrementAndGet()
        tokens.clearToken()
        invalidateLocked(JevDisabledException("Jev token was removed. Continue with ordinary device tools."), cancelled = false)
    }
    override fun beginRun() {
        synchronized(requestLock) {
            invalidateLocked(CancellationException("Jev run was replaced"), cancelled = false)
        }
    }
    override fun cancelActiveRequest() {
        synchronized(requestLock) {
            invalidateLocked(CancellationException("Jev run was stopped"), cancelled = true)
        }
    }

    private fun invalidateLocked(reason: Exception, cancelled: Boolean) {
        lifecycle.incrementAndGet()
        requestCancelled = cancelled
        // This child owns both HTTP and backoff. Cancelling it never cancels
        // the caller's Codex run, and also wakes a long Retry-After delay.
        activeDecision?.cancel(CancellationException(reason.message))
        activeConnection?.let { connection -> DISCONNECT.execute { connection.disconnect() } }
        activeConnection = null
    }

    private fun checkRequest(epoch: Long, settings: Long) = synchronized(requestLock) {
        if (!state.value.ready || settings != settingsEpoch.get()) throw JevDisabledException()
        if (requestCancelled || epoch != lifecycle.get()) throw CancellationException("Jev request was stopped")
    }

    override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse = coroutineScope {
        check(choosing.compareAndSet(false, true)) { "A Jev request is already in flight" }
        val epoch = lifecycle.get()
        val settings = settingsEpoch.get()
        val decision = async(start = CoroutineStart.LAZY) { chooseOwned(request) }
        try {
            synchronized(requestLock) {
                checkRequest(epoch, settings)
                activeDecision = decision
            }
            decision.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!state.value.ready || settings != settingsEpoch.get()) throw JevDisabledException()
            throw cancelled
        } finally {
            decision.cancel()
            synchronized(requestLock) { if (activeDecision === decision) activeDecision = null }
            choosing.set(false)
        }
    }

    private suspend fun chooseOwned(request: JevDecisionRequest): JevDecisionResponse = withContext(Dispatchers.IO) {
        val requestLifecycle = lifecycle.get()
        val settings = settingsEpoch.get()
        checkRequest(requestLifecycle, settings)
        val token = tokens.readToken() ?: throw IOException("Jev token is unavailable")
        val body = requestBody(request)
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw IOException("Jev request is too large")
        }
        // Retries surround a decision only. No device dispatch is inside this block.
        withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            var lastFailure: IOException? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                checkRequest(requestLifecycle, settings)
                try {
                    return@withTimeoutOrNull requestOnce(body, token, requestLifecycle, settings)
                } catch (failure: IOException) {
                    checkRequest(requestLifecycle, settings)
                    if (failure is JevProtocolFailure || failure is javax.net.ssl.SSLException ||
                        failure is java.net.ProtocolException || failure is JevHttpFailure && !failure.retryable) throw failure
                    lastFailure = failure
                    if (attempt + 1 < MAX_ATTEMPTS) {
                        val backoff = 250L * (1L shl attempt)
                        val waitMs = (failure as? JevHttpFailure)?.retryAfterMs?.coerceAtLeast(backoff) ?: backoff
                        // Do not violate a long Retry-After or wait beyond the decision budget.
                        if (waitMs >= DECISION_TIMEOUT_MS) throw failure
                        delay(waitMs)
                    }
                }
            }
            throw requireNotNull(lastFailure)
        } ?: throw IOException("Jev decision deadline exceeded after bounded network recovery")
    }

    private suspend fun requestOnce(body: String, token: String, requestLifecycle: Long, settings: Long): JevDecisionResponse {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        synchronized(requestLock) {
            checkRequest(requestLifecycle, settings)
            activeConnection = connection
        }
        return suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)
            fun fail(failure: Exception) {
                if (settled.compareAndSet(false, true)) continuation.resumeWithException(failure)
            }
            // The coroutine deadline must disconnect blocking I/O immediately,
            // even when nobody presses the app's Stop button.
            continuation.invokeOnCancellation {
                settled.set(true)
                DISCONNECT.execute { connection.disconnect() }
            }
            NETWORK.execute {
                try {
                    if (settled.get() || !continuation.isActive) return@execute
                    checkRequest(requestLifecycle, settings)
                    connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        // Do not echo an upstream body that may contain input or credentials.
                        throw JevHttpFailure(code, retryAfterMillis(connection.getHeaderField("Retry-After")))
                    }
                    val response = parseResponse(readBounded(connection.inputStream))
                    if (continuation.isActive) {
                        synchronized(requestLock) {
                            checkRequest(requestLifecycle, settings)
                            if (settled.compareAndSet(false, true)) continuation.resume(response)
                        }
                    }
                } catch (failure: Exception) {
                    fail(failure)
                } finally {
                    synchronized(requestLock) {
                        if (activeConnection === connection) {
                            activeConnection = null
                        }
                    }
                    connection.disconnect()
                }
            }
        }
    }

    private fun readBounded(input: InputStream): String = input.bufferedReader(Charsets.UTF_8).use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(4_096)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            output.append(buffer, 0, read)
            if (output.length > MAX_RESPONSE_CHARS) throw JevProtocolFailure("Jev response is too large")
        }
        output.toString()
    }

    private fun requestBody(request: JevDecisionRequest): String = buildJsonObject {
        put("model", MODEL)
        put("state", request.state)
        put("questions", buildJsonObject {
            request.questions.forEach { question ->
                put(question.name, buildJsonObject {
                    put("type", "choice")
                    put("instructions", question.instructions)
                    put("criteria", buildJsonObject {
                        question.criteria.forEach { (key, description) -> put(key, description) }
                    })
                })
            }
        })
    }.toString()

    private fun parseResponse(body: String): JevDecisionResponse {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw JevProtocolFailure("Jev returned invalid JSON") }
        val answers = root["answers"]?.jsonObject
            ?: throw JevProtocolFailure("Jev response has no answers")
        return JevDecisionResponse(
            answers = answers.mapNotNull { (name, element) ->
                val answer = element as? JsonObject ?: return@mapNotNull null
                name to JevDecision(
                    type = (answer["type"] as? JsonPrimitive)?.contentOrNull,
                    choice = (answer["choice"] as? JsonPrimitive)?.contentOrNull,
                    confidence = (answer["confidence"] as? JsonPrimitive)?.doubleOrNull,
                    probabilities = (answer["probabilities"] as? JsonObject)?.mapNotNull { (key, value) ->
                        (value as? JsonPrimitive)?.doubleOrNull?.let { key to it }
                    }?.toMap() ?: emptyMap(),
                )
            }.toMap(),
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: MODEL,
        )
    }

    private companion object {
        val DISCONNECT = Executors.newCachedThreadPool { task -> Thread(task, "jev-close").apply { isDaemon = true } }
        val NETWORK = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "jev-http").apply { isDaemon = true }
        }
        const val API_URL = "https://api.typesafe.ai/v1/systemone"
        const val MODEL = "jev-latest"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val DECISION_TIMEOUT_MS = 30_000L
        const val MAX_ATTEMPTS = 3
        const val MAX_REQUEST_BYTES = 150_000
        const val MAX_RESPONSE_CHARS = 150_000
    }
}
