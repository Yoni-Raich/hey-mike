// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class TrustedAgent(val packageName: String, val certificateSha256: String) {
    init { require(certificateSha256.replace(":", "").matches(Regex("[0-9a-fA-F]{64}"))) }
}
object TrustedAgents {
    // Public certificate of this developer's test build. Production trust is configured by the app publisher.
    val HEY_MIKE = TrustedAgent("dev.androidagent.app.dev", "f65c3469483d5b059543c7a67d369578bf3627a02795d77fc76a244444dda1ee")
}
object CallerVerifier {
    fun verify(context: Context, uid: Int, trusted: Collection<TrustedAgent>): String {
        if (Build.VERSION.SDK_INT < 30) throw SecurityException("Mike Connect requires Android 11")
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        return trusted.firstOrNull { agent ->
            agent.packageName in packages && context.packageManager.hasSigningCertificate(agent.packageName,
                agent.certificateSha256.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), PackageManager.CERT_INPUT_SHA256)
        }?.packageName ?: throw SecurityException("Untrusted Mike Connect caller")
    }
}
