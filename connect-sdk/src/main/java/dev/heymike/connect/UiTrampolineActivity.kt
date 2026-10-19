// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal object UiTokens {
    private data class Entry(val intent: Intent, val expires: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    fun mint(context: Context, intent: Intent): String {
        val component = requireNotNull(intent.component) { "UI intent must name an explicit component" }
        require(component.packageName == context.packageName && intent.selector == null && intent.clipData == null) { "UI must stay in this app" }
        require(intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
        entries.entries.removeAll { it.value.expires < SystemClock.elapsedRealtime() }
        require(entries.size < 32) { "Too many UI handoffs" }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        entries[token] = Entry(Intent(intent), SystemClock.elapsedRealtime() + 60_000)
        return token
    }
    fun consume(token: String): Intent? = entries.remove(token)?.takeIf { it.expires >= SystemClock.elapsedRealtime() }?.intent
}
/** The unguessable bearer token is returned only on an authenticated Binder callback, once, for 60 seconds. */
class UiTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent?.getStringExtra("dev.heymike.connect.UI_TOKEN").orEmpty()
        UiTokens.consume(token)?.let { target ->
            if (target.component?.packageName == packageName) runCatching { startActivity(target) }
        }
        finish()
    }
}
