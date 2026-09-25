/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.app.ime

import android.Manifest
import androidx.annotation.RequiresApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Process
import android.util.Base64
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Minimal input method used by the device tool gateway for Unicode text.
 *
 * The receiver is registered only while the input method is alive, is scoped
 * to this package's action, and requires the platform DUMP permission. On
 * Android versions that expose the sender UID, only shell or unavailable identity is accepted; DUMP remains mandatory.
 * The payload is UTF-8 encoded Base64 so shell argument quoting cannot alter
 * the text. No text or payload is logged.
 */
class AgentInputMethodService : InputMethodService() {

    private lateinit var inputReceiver: BroadcastReceiver

    private val inputAction: String
        get() = "$packageName$ACTION_SUFFIX"

    override fun onCreate() {
        super.onCreate()
        inputReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action !in setOf(inputAction, "$packageName$PROBE_SUFFIX")) return

                if (Build.VERSION.SDK_INT >= 34 && intentSenderUid() !in setOf(Process.SHELL_UID, Process.INVALID_UID)) {
                    setFailure(RESULT_UNAUTHORIZED)
                    return
                }
                // DUMP is enforced on the sender by registerReceiver. Android may
                // hide shell identity unless the sender opts into sharing it.
                if (intent.action == "$packageName$PROBE_SUFFIX") {
                    val ready = currentInputConnection != null && currentInputEditorInfo != null &&
                        currentInputEditorInfo.inputType != 0
                    setResultCode(if (ready) RESULT_SUCCESS else RESULT_NO_INPUT_CONNECTION)
                    setResultData(if (ready) RESULT_DATA_OK else RESULT_DATA_ERROR)
                    return
                }
                val encoded = intent.getStringExtra(EXTRA_PAYLOAD)
                val replace = intent.getBooleanExtra("replace", false)
                if (encoded == null || (!replace && encoded.isEmpty()) || encoded.length > MAX_BASE64_CHARS ||
                    encoded.length % 4 != 0 ||
                    !encoded.matches(BASE64_RE)
                ) {
                    setFailure(RESULT_INVALID_PAYLOAD)
                    return
                }
                val bytes = try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (_: IllegalArgumentException) {
                    setFailure(RESULT_INVALID_PAYLOAD)
                    return
                }
                if (Base64.encodeToString(bytes, Base64.NO_WRAP) != encoded) {
                    setFailure(RESULT_INVALID_PAYLOAD)
                    return
                }
                if ((!replace && bytes.isEmpty()) || bytes.size > MAX_TEXT_BYTES) {
                    setFailure(RESULT_INVALID_PAYLOAD)
                    return
                }
                val text = try {
                    decodeUtf8(bytes)
                } catch (_: java.nio.charset.CharacterCodingException) {
                    setFailure(RESULT_INVALID_PAYLOAD)
                    return
                }
                val connection = currentInputConnection
                if (connection == null || currentInputEditorInfo == null || currentInputEditorInfo.inputType == 0) {
                    setFailure(RESULT_NO_INPUT_CONNECTION)
                    return
                }
                val targetPackage = intent.getStringExtra("target_package")
                if (targetPackage != null && currentInputEditorInfo.packageName != targetPackage) {
                    setFailure(RESULT_REPLACE_UNAVAILABLE)
                    return
                }
                val before = fullText(connection)
                val expected = if (replace) text else before?.let { value ->
                    val old = value.text.toString()
                    val start = value.selectionStart
                    val end = value.selectionEnd
                    if (start !in 0..old.length || end !in 0..old.length) null
                    else old.replaceRange(minOf(start, end), maxOf(start, end), text)
                }
                if (replace && !selectEntireField(connection, before)) {
                    setFailure(RESULT_REPLACE_UNAVAILABLE)
                    return
                }
                val committed = try {
                    connection.commitText(text, 1)
                } catch (_: RuntimeException) {
                    false
                }
                if (committed) {
                    val verified = expected != null && fullText(connection)?.text?.toString() == expected
                    setResultCode(if (verified) RESULT_VERIFIED else RESULT_SUCCESS)
                    setResultData(RESULT_DATA_OK)
                } else {
                    setFailure(RESULT_COMMIT_FAILED)
                }
            }

            @RequiresApi(34)
            private fun intentSenderUid(): Int = getSentFromUid()

            private fun setFailure(code: Int) {
                setResultCode(code)
                setResultData(RESULT_DATA_ERROR)
            }
        }

        val filter = IntentFilter(inputAction).apply { addAction("$packageName$PROBE_SUFFIX") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                inputReceiver,
                filter,
                Manifest.permission.DUMP,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(inputReceiver, filter, Manifest.permission.DUMP, null)
        }
    }

    override fun onDestroy() {
        if (::inputReceiver.isInitialized) {
            runCatching { unregisterReceiver(inputReceiver) }
        }
        super.onDestroy()
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun fullText(connection: InputConnection): ExtractedText? = runCatching {
        connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_FIELD_CHARS }, 0)
            ?.takeIf { it.startOffset == 0 && it.partialStartOffset < 0 && it.partialEndOffset < 0 &&
                it.text != null && it.text.length < MAX_FIELD_CHARS }
    }.getOrNull()

    /** Never advertise replacement while committing at an arbitrary cursor position. */
    private fun selectEntireField(connection: InputConnection, before: ExtractedText?): Boolean = runCatching {
        val old = before?.text?.toString() ?: return false
        if (!connection.setSelection(0, old.length)) return false
        val selected = fullText(connection) ?: return false
        selected.text.toString() == old && minOf(selected.selectionStart, selected.selectionEnd) == 0 &&
            maxOf(selected.selectionStart, selected.selectionEnd) == old.length &&
            connection.getSelectedText(0)?.toString().orEmpty() == old
    }.getOrDefault(false)

    companion object {
        const val ACTION_SUFFIX = ".INPUT_TEXT"
        const val PROBE_SUFFIX = ".INPUT_PROBE"
        const val EXTRA_PAYLOAD = "payload_base64"
        const val RESULT_SUCCESS = 1
        const val RESULT_UNAUTHORIZED = 2
        const val RESULT_INVALID_PAYLOAD = 3
        const val RESULT_NO_INPUT_CONNECTION = 4
        const val RESULT_COMMIT_FAILED = 5
        const val RESULT_REPLACE_UNAVAILABLE = 6
        const val RESULT_VERIFIED = 7
        const val RESULT_DATA_OK = "ok"
        const val RESULT_DATA_ERROR = "error"
        const val MAX_TEXT_BYTES = 16 * 1024
        private const val MAX_FIELD_CHARS = 65_536
        const val MAX_BASE64_CHARS = ((MAX_TEXT_BYTES + 2) / 3) * 4 + 4
        private val BASE64_RE = Regex("^[A-Za-z0-9+/]*={0,2}$")
    }
}
