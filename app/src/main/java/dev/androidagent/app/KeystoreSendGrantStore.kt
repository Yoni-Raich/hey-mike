package dev.androidagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.androidagent.core.SendGrant
import dev.androidagent.core.SendGrantStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * "Always allow" send answers, kept where the agent cannot forge them.
 *
 * The agent's shell runs as this app's own Linux user, so it can read and
 * write every file the app owns, preferences included. A grant it could write
 * would be a permission it gave itself. The list is therefore signed with an
 * HMAC key held in the Android Keystore, which never leaves the secure
 * hardware and cannot be exported, and a list whose signature does not verify
 * is ignored entirely.
 */
class KeystoreSendGrantStore(context: Context) : SendGrantStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val grants: StateFlow<List<SendGrant>> = state.asStateFlow()

    @Synchronized
    override fun add(grant: SendGrant) {
        save(state.value.filterNot { it == grant } + grant)
    }

    @Synchronized
    override fun remove(grant: SendGrant) {
        save(state.value.filterNot { it == grant })
    }

    private fun save(list: List<SendGrant>) {
        val body = encode(list)
        prefs.edit()
            .putString(KEY_BODY, body)
            .putString(KEY_MAC, runCatching { sign(body) }.getOrNull())
            .apply()
        state.value = list
    }

    private fun load(): List<SendGrant> {
        val body = prefs.getString(KEY_BODY, null) ?: return emptyList()
        val mac = prefs.getString(KEY_MAC, null) ?: return emptyList()
        val valid = runCatching {
            MessageDigest.isEqual(Base64.decode(mac, Base64.NO_WRAP), Base64.decode(sign(body), Base64.NO_WRAP))
        }.getOrDefault(false)
        // Tampered or unverifiable: trust none of it, never part of it.
        return if (valid) runCatching { decode(body) }.getOrDefault(emptyList()) else emptyList()
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
        private const val PREFS = "send_grants"
        private const val KEY_BODY = "grants"
        private const val KEY_MAC = "mac"
        private const val KEY_ALIAS = "hey_mike_send_grants"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        internal fun encode(list: List<SendGrant>): String = buildJsonArray {
            list.forEach { grant ->
                add(buildJsonObject {
                    put("package", JsonPrimitive(grant.packageName))
                    put("app", JsonPrimitive(grant.appLabel))
                    put("recipient", grant.recipient?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }.toString()

        internal fun decode(body: String): List<SendGrant> =
            (Json.parseToJsonElement(body) as JsonArray).map { element ->
                val item = element.jsonObject
                SendGrant(
                    packageName = item.getValue("package").jsonPrimitive.content,
                    appLabel = item["app"]?.jsonPrimitive?.contentOrNull ?: item.getValue("package").jsonPrimitive.content,
                    recipient = (item["recipient"] as? JsonPrimitive)?.contentOrNull,
                )
            }
    }
}
