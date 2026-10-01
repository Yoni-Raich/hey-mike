/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.androidagent.workspace.ResponsibilitySeal
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** The on-device model shares the app UID. A plain JSON file cannot enforce user review. */
class KeystoreResponsibilitySeal(private val alias: String = "hey_mike_responsibility_state") : ResponsibilitySeal {
    override fun hasKey(): Boolean = keyStore().containsAlias(alias)
    override fun sign(payload: String): String {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key())
        return Base64.encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun verify(payload: String, signature: String): Boolean {
        if (!hasKey()) return false
        return MessageDigest.isEqual(Base64.decode(signature, Base64.NO_WRAP), Base64.decode(sign(payload), Base64.NO_WRAP))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey = (keyStore().getKey(alias, null) as? SecretKey) ?:
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }

}
