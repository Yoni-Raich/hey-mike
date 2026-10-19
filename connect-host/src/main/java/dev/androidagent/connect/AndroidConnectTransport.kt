package dev.androidagent.connect

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import dev.androidagent.core.*
import dev.heymike.connect.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AndroidConnectTransport(context: Context) : ConnectTransport {
    private val context = context.applicationContext
    private val pm = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private data class Binding(val remote: IMikeConnect, val connection: ServiceConnection, var used: Long, val calls: AtomicInteger = AtomicInteger())
    private val bindings = mutableMapOf<String, Binding>()
    private val catalogs = ConcurrentHashMap<String, List<Capability>>()
    override fun discover(): List<ConnectProvider> = pm.queryIntentServices(Intent(ConnectProtocol.ACTION), PackageManager.GET_META_DATA)
        .mapNotNull { found ->
            runCatching {
                val s = found.serviceInfo
                if (!s.exported || !s.enabled || s.permission != null || s.metaData?.getInt("dev.heymike.connect.binding") != ConnectProtocol.BINDING) return@runCatching null
                val info = pm.getPackageInfo(s.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signatures = info.signingInfo?.apkContentsSigners.orEmpty()
                if (signatures.isEmpty()) return@runCatching null
                val signer = signatures.map { cert -> MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
                val summaryValue = s.metaData?.get("dev.heymike.connect.summary")
                val summary = when(summaryValue) { is Int -> pm.getResourcesForApplication(s.applicationInfo).getString(summaryValue); is String -> summaryValue; else -> "" }
                ConnectProvider(s.packageName, s.name, s.loadLabel(pm).toString().take(80), summary.take(240), signer,
                    info.longVersionCode, runCatching { pm.getInstallSourceInfo(s.packageName).installingPackageName }.getOrNull())
            }.getOrNull()
        }.groupBy { it.packageName }.values.filter { it.size == 1 }.map { it.single() }.sortedBy { it.label }
    private fun validate(provider: ConnectProvider) {
        val current = discover().find { it.packageName == provider.packageName }
        if (current == null || current.signer != provider.signer || current.serviceName != provider.serviceName || current.versionCode != provider.versionCode)
            throw ConnectTransportException("provider_changed", false, "Provider was removed or changed; refresh Connected apps")
    }
    private fun key(p: ConnectProvider) = p.packageName + ":" + p.versionCode + ":" + p.signer + ":" + p.serviceName
    override suspend fun capabilities(provider: ConnectProvider): List<Capability> {
        validate(provider)
        return catalogs[key(provider)] ?: run {
            val result = request(provider, "tools/list", buildJsonObject { put("_meta", ConnectProtocol.meta()) }, 10_000)
            val tools = result["tools"] as? JsonArray ?: throw ConnectTransportException("invalid_response", false, "Provider did not return tools")
            require(tools.size <= 64) { "Too many provider tools" }
            val caps = tools.mapNotNull { runCatching { Capability.fromWire(it.jsonObject) }.getOrNull() }
            require(caps.map { it.name }.distinct().size == caps.size) { "Duplicate provider tools" }
            catalogs[key(provider)] = caps
            // Bound catalogs too, including apps updated many times.
            catalogs.keys.filter { it.startsWith(provider.packageName + ":") && it != key(provider) }.forEach(catalogs::remove)
            caps
        }
    }
    override suspend fun call(provider: ConnectProvider, capability: Capability, arguments: JsonObject, requestId: String,
        origin: String, approved: Boolean, inputResponses: JsonObject, requestState: String?): JsonObject {
        validate(provider)
        return request(provider, "tools/call", buildJsonObject {
            put("name", capability.name); put("arguments", arguments)
            put("_meta", ConnectProtocol.meta(origin, requestId, approved))
            if (inputResponses.isNotEmpty()) put("inputResponses", inputResponses)
            requestState?.let { put("requestState", it) }
        }, capability.timeoutMs + 1_000)
    }
    private suspend fun bind(provider: ConnectProvider): Binding = mutex.withLock {
        val key = key(provider)
        bindings[key]?.let { it.used = SystemClock.elapsedRealtime(); it.calls.incrementAndGet(); return@withLock it }
        if (bindings.size >= 8) {
            val victim = bindings.entries.filter { it.value.calls.get() == 0 }.minByOrNull { it.value.used }
                ?: throw ConnectTransportException("busy", false, "All provider connections are busy")
            runCatching { context.unbindService(victim.value.connection) }; bindings.remove(victim.key)
        }
        val ready = CompletableDeferred<IMikeConnect>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(IMikeConnect.Stub.asInterface(service)) }
            override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(ConnectTransportException("unavailable", false, "Provider disconnected")) }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(ConnectTransportException("unavailable", false, "Provider refused binding")) }
            override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(ConnectTransportException("unavailable", false, "Provider binding died")) }
        }
        var bound = false
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(provider.packageName, provider.serviceName)), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw ConnectTransportException("unavailable", false, "Provider cannot be bound")
            val remote = withTimeout(3_000) { ready.await() }
            Binding(remote, connection, SystemClock.elapsedRealtime(), AtomicInteger(1)).also { bindings[key] = it }
        } catch (e: Exception) {
            if (bound) runCatching { context.unbindService(connection) }
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            throw ConnectTransportException("unavailable", false, "Provider bind failed: " + e.javaClass.simpleName)
        }
    }
    private suspend fun request(provider: ConnectProvider, method: String, params: JsonObject, timeout: Long): JsonObject = withContext(Dispatchers.IO) {
        val expectedUid = pm.getApplicationInfo(provider.packageName, 0).uid
        val binding = bind(provider)
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JsonObject>()
        val callback = object : IConnectCallback.Stub() {
            override fun onNotification(message: ConnectMessage) { message.close() } // No logging of provider data.
            override fun onResponse(message: ConnectMessage) {
                try {
                    if (Binder.getCallingUid() != expectedUid) throw SecurityException("Wrong response caller")
                    require(message.blobs.isEmpty()) { "Unexpected binary parts" }
                    val body = ConnectProtocol.parse(message.json)
                    require(body.string("jsonrpc") == "2.0" && body["id"] == JsonPrimitive(id))
                    if (body["error"] is JsonObject) {
                        val error = body.getValue("error").jsonObject
                        throw ConnectTransportException(if (error["code"]?.jsonPrimitive?.intOrNull == -32022) "needs_update" else "protocol_error", true, error.string("message"))
                    }
                    val result = body["result"] as? JsonObject ?: error("Missing result")
                    require(result.string("resultType") in setOf("complete", "input_required"))
                    response.complete(result)
                } catch (e: Exception) { response.completeExceptionally(e) }
                finally { message.close() }
            }
        }
        val binder = binding.remote.asBinder()
        val death = IBinder.DeathRecipient { response.completeExceptionally(ConnectTransportException("outcome_unknown", true, "Provider process died")) }
        var dispatched = false
        try {
            binder.linkToDeath(death, 0)
            val body = ConnectProtocol.request(id, method, params).toString()
            require(body.toByteArray().size <= ConnectProtocol.MAX_JSON_BYTES)
            validate(provider)
            dispatched = true
            binding.remote.send(ConnectMessage(body), callback)
            withTimeout(timeout.coerceIn(1_000, 61_000)) { response.await() }
        } catch (e: TimeoutCancellationException) {
            runCatching { binding.remote.cancel(id) }
            discard(provider, binding)
            throw ConnectTransportException("outcome_unknown", dispatched, "Provider timed out")
        } catch (e: CancellationException) { runCatching { binding.remote.cancel(id) }; throw e }
        catch (e: Exception) {
            discard(provider, binding)
            if (e is ConnectTransportException) throw e
            throw ConnectTransportException(if (dispatched) "outcome_unknown" else "unavailable", dispatched, e.message?.take(200).orEmpty())
        } finally {
            runCatching { binder.unlinkToDeath(death, 0) }
            binding.calls.decrementAndGet()
            binding.used = SystemClock.elapsedRealtime()
            scope.launch {
                delay(60_000)
                mutex.withLock {
                    if (bindings[key(provider)] === binding && binding.calls.get() == 0 && SystemClock.elapsedRealtime() - binding.used >= 60_000) {
                        bindings.remove(key(provider)); runCatching { context.unbindService(binding.connection) }
                    }
                }
            }
        }
    }
    private suspend fun discard(provider: ConnectProvider, binding: Binding) = withContext(NonCancellable) {
        mutex.withLock {
            if (bindings[key(provider)] === binding) {
                bindings.remove(key(provider)); runCatching { context.unbindService(binding.connection) }
            }
        }
    }
    override suspend fun openUi(provider: ConnectProvider, token: String) = withContext(Dispatchers.Main) {
        validate(provider)
        require(token.matches(Regex("[a-f0-9]{64}")))
        context.startActivity(Intent().setComponent(ComponentName(provider.packageName, "dev.heymike.connect.UiTrampolineActivity"))
            .putExtra("dev.heymike.connect.UI_TOKEN", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
