package dev.androidagent.app

import android.content.Context
import android.util.AtomicFile
import dev.androidagent.core.*
import dev.androidagent.remote.KeystoreSecretBox
import dev.heymike.connect.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File

class KeystoreConnectionGrantStore(context: Context) : ConnectionGrantStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "connected-app-grants.bin"))
    private val box = KeystoreSecretBox("hey_mike_connected_apps")
    private val codec = ListSerializer(ConnectionGrant.serializer())
    private val state = MutableStateFlow(load())
    override val grants = state.asStateFlow()
    private fun load(): List<ConnectionGrant> = runCatching {
        Json.decodeFromString(codec, box.open(file.readFully()).toString(Charsets.UTF_8))
    }.getOrDefault(emptyList())
    @Synchronized override fun save(grant: ConnectionGrant) {
        val next = state.value.filterNot { it.packageName == grant.packageName } + grant
        val bytes = box.seal(Json.encodeToString(codec, next).toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        state.value = next
    }
}
data class ConnectedAppsView(
    val providers: List<ConnectProvider> = emptyList(), val catalogs: Map<String, List<Capability>> = emptyMap(),
    val loading: String? = null, val error: String? = null,
    /** The app [error] is about, or null when it is about finding apps at all. */
    val errorPackage: String? = null,
)
class ConnectedAppsController(
    private val context: Context, val transport: ConnectTransport, val store: ConnectionGrantStore,
    private val scope: CoroutineScope,
) {
    val view = MutableStateFlow(ConnectedAppsView())
    private val prefs = context.getSharedPreferences("connected-app-history", Context.MODE_PRIVATE)
    val history = MutableStateFlow(runCatching {
        Json.decodeFromString(ListSerializer(ConnectHistory.serializer()), prefs.getString("items", "[]")!!)
    }.getOrDefault(emptyList()))
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            runCatching { transport.discover() }.onSuccess { apps ->
                view.update { before ->
                    val unchanged = apps.filter { app -> before.providers.any { it == app } }.map { it.packageName }.toSet()
                    before.copy(providers = apps, catalogs = before.catalogs.filterKeys { it in unchanged }, error = null, errorPackage = null)
                }
                // An app the user already turned on may have added abilities in an update. Reading its list
                // starts it, so this is limited to apps the user chose; discovery alone never starts an app.
                for (app in apps) {
                    val on = store.grants.value.any { it.packageName == app.packageName && it.signer == app.signer && it.enabled }
                    if (on) loadCatalog(app, quiet = true)
                }
            }.onFailure { e -> view.update { it.copy(error = e.message ?: "Could not look for apps.", errorPackage = null) } }
        }
    }
    /** Reads what [app] offers, then calls [then] with it. Used before the user is asked to turn an app on. */
    fun review(app: ConnectProvider, then: ((List<Capability>) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val caps = loadCatalog(app, quiet = false)
            if (caps != null) then?.invoke(caps)
        }
    }
    private suspend fun loadCatalog(app: ConnectProvider, quiet: Boolean): List<Capability>? {
        if (!quiet) view.update { it.copy(loading = app.packageName, error = null, errorPackage = null) }
        return try {
            transport.capabilities(app).also { caps -> view.update { it.copy(catalogs = it.catalogs + (app.packageName to caps)) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) view.update { it.copy(error = e.message ?: "Could not read what ${app.label} offers.", errorPackage = app.packageName) }
            null
        } finally {
            if (!quiet) view.update { it.copy(loading = null) }
        }
    }
    /** UI-only entry point. No model tool can create or widen a grant. */
    fun enable(app: ConnectProvider, enabled: Boolean) {
        val caps = view.value.catalogs[app.packageName] ?: return
        val previous = store.grants.value.find { it.packageName == app.packageName && it.signer == app.signer }
        store.save(reviewedConnectionGrant(app, caps, previous, enabled))
    }
    /** Turning off needs no list from the app, so it works even when the app cannot be reached. */
    fun turnOff(app: ConnectProvider) {
        val grant = store.grants.value.find { it.packageName == app.packageName && it.signer == app.signer } ?: return
        if (grant.enabled) store.save(grant.copy(enabled = false))
    }
    fun capability(app: ConnectProvider, cap: Capability, enabled: Boolean? = null, ask: Boolean? = null, automation: Boolean? = null) {
        val grant = store.grants.value.find { it.packageName == app.packageName && it.signer == app.signer } ?: return
        val before = grant.capabilities.find { it.name == cap.name && it.policyHash == cap.policyHash() }
        val next = CapabilityGrant(cap.name, cap.access, cap.policyHash(), enabled ?: before?.enabled ?: false,
            ask ?: before?.askFirst ?: false, automation ?: before?.automationAllowed ?: false)
        store.save(grant.copy(capabilities = grant.capabilities.filterNot { it.name == cap.name } + next))
    }
    @Synchronized fun record(event: ConnectHistory) {
        val next = (history.value + event).takeLast(50)
        history.value = next
        prefs.edit().putString("items", Json.encodeToString(ListSerializer(ConnectHistory.serializer()), next)).apply()
    }
    suspend fun tryCapability(app: ConnectProvider, capability: Capability, arguments: String): String {
        val gateway = ConnectedAppsGateway(transport, store, record = ::record)
        return try {
            gateway.beginRun("developer-try", File(context.cacheDir, "connect-try"))
            gateway.invoke("connected_apps", kotlinx.serialization.json.buildJsonObject {
                put("operation", "call")
                put("app", app.packageName)
                put("capability", capability.name)
                put("arguments", ConnectProtocol.parse(arguments))
            }).text
        } catch (e: Exception) { "Cannot run: " + e.message }
        finally { gateway.cancel() }
    }
}
