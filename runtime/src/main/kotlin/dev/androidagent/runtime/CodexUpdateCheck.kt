package dev.androidagent.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class CodexUpdateInfo(val installed: String? = null, val latest: String? = null, val message: String = "Updates arrive with the Mike app")

/** Android's static Codex binary belongs to the APK; this checks availability only. */
class CodexUpdateCheck(
    private val cache: File,
    private val installed: () -> String?,
    private val fetch: () -> String = {
        val connection = URL("https://api.github.com/repos/openai/codex/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "HeyMike-runtime-check")
            kotlin.check(connection.responseCode == 200)
            connection.inputStream.use { stream ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    kotlin.check(bytes.size() + count <= 2 * 1024 * 1024)
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(CodexUpdateInfo())
    val state: StateFlow<CodexUpdateInfo> = mutable
    private var lastCheck = 0L

    suspend fun check(force: Boolean = false): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = installed()
            val saved = runCatching { Json.parseToJsonElement(cache.readText()) as JsonObject }.getOrNull()
            val cachedLatest = saved?.get("latest")?.jsonPrimitive?.content?.takeIf(ClaudeReleaseClient::validVersion)
            mutable.value = info(current, cachedLatest ?: mutable.value.latest)
            val previous = maxOf(lastCheck, saved?.get("at")?.jsonPrimitive?.longOrNull ?: 0L)
            if (!force && now() - previous in 0 until 15 * 60_000L) {
                mutable.value = info(current, cachedLatest ?: mutable.value.latest)
                return@withContext
            }
            lastCheck = now()
            try {
                val metadata = Json.parseToJsonElement(fetch()) as JsonObject
                kotlin.check(metadata["draft"]?.toString() == "false" && metadata["prerelease"]?.toString() == "false")
                val latest = metadata["tag_name"]?.jsonPrimitive?.content?.removePrefix("rust-v").orEmpty()
                kotlin.check(ClaudeReleaseClient.validVersion(latest))
                mutable.value = info(current, latest)
                cache.parentFile?.mkdirs()
                val part = File(cache.parentFile, cache.name + ".tmp")
                part.writeText("{\"latest\":\"$latest\",\"at\":$lastCheck}")
                java.nio.file.Files.move(part.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = info(current, cachedLatest).let {
                    it.copy(message = "Could not check for updates; installed Codex is unchanged. ${it.message}")
                }
            }
        }
    }

    companion object {
        fun info(current: String?, latest: String?): CodexUpdateInfo = CodexUpdateInfo(current, latest,
            if (current != null && latest != null && ClaudeReleaseClient.newer(latest, current))
                "Codex $latest is available. Update the Mike app to update this phone."
            else "Codex updates on this phone arrive with the Mike app")
    }
}
