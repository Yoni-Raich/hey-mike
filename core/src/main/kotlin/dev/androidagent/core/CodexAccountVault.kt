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

package dev.androidagent.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One Codex sign-in kept on this phone. [label] is what the account reports, normally the email. */
@Serializable
data class SavedAccount(val id: String, val label: String, val addedAt: Long)

/** Every saved sign-in and which one Codex is using now. */
data class AccountVaultState(val accounts: List<SavedAccount> = emptyList(), val activeId: String? = null) {
    val active: SavedAccount? get() = accounts.firstOrNull { it.id == activeId }
}

/**
 * Several Codex sign-ins, one live at a time.
 *
 * Codex reads its credentials from `CODEX_HOME/auth.json` and nothing else
 * about an account lives in CODEX_HOME: chats, rollouts, skills and config
 * are shared. So switching account is swapping that one file while the
 * app-server is stopped. Every chat stays where it is and resumes under the
 * new account on its next turn; only the quota that is read back changes.
 *
 * Saved copies live in [directory], outside CODEX_HOME, so Codex never sees
 * an account that is not the live one. The live file is copied back into its
 * slot before any swap, because Codex rewrites it when it refreshes a token
 * and the old copy would hold a refresh token that no longer works.
 *
 * Callers must stop the app-server before [activate] or [detach]: it may be
 * writing auth.json at the same moment.
 */
class CodexAccountVault(
    private val codexHome: File,
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val liveAuth: File get() = File(codexHome, AUTH_FILE)
    private val index: File get() = File(directory, INDEX_FILE)

    fun state(): AccountVaultState = synchronized(lock) { read().toState() }

    /**
     * Keep the credentials Codex is using now as the account called [label].
     * An account with the same label is updated rather than duplicated, so a
     * second sign-in to the same email does not leave two entries.
     *
     * @return the saved account, or null when Codex has no credentials file.
     */
    fun captureActive(label: String): SavedAccount? = synchronized(lock) {
        if (!liveAuth.isFile) return null
        val name = label.trim().ifBlank { "Codex account" }
        val stored = read()
        val existing = stored.accounts.firstOrNull { it.label.equals(name, ignoreCase = true) }
        val account = existing ?: SavedAccount(UUID.randomUUID().toString(), name, clock())
        copyAtomically(liveAuth, slot(account.id))
        write(Index(account.id, if (existing == null) stored.accounts + account else stored.accounts))
        account
    }

    /**
     * Put the saved account [id] live. The account that was live is saved
     * first, so its latest tokens are the ones kept.
     */
    fun activate(id: String): SavedAccount = synchronized(lock) {
        val stored = read()
        val target = stored.accounts.firstOrNull { it.id == id } ?: error("That account is no longer saved on this phone.")
        val source = slot(id)
        check(source.isFile) { "The sign-in for ${target.label} is missing. Remove it and sign in again." }
        saveLiveLocked(stored)
        copyAtomically(source, liveAuth)
        write(stored.copy(active = id))
        target
    }

    /**
     * Save the live account and take it off Codex, so the next sign-in starts
     * clean and becomes a new account instead of replacing this one.
     */
    fun detach() = synchronized(lock) {
        val stored = read()
        saveLiveLocked(stored)
        Files.deleteIfExists(liveAuth.toPath())
        write(stored.copy(active = null))
    }

    /**
     * Forget a saved account. The live one is only unmarked here: its
     * credentials belong to Codex, and signing out is what removes them.
     */
    fun remove(id: String) = synchronized(lock) {
        val stored = read()
        Files.deleteIfExists(slot(id).toPath())
        write(Index(stored.active.takeIf { it != id }, stored.accounts.filterNot { it.id == id }))
    }

    private fun saveLiveLocked(stored: Index) {
        val active = stored.active ?: return
        if (liveAuth.isFile && stored.accounts.any { it.id == active }) copyAtomically(liveAuth, slot(active))
    }

    private fun slot(id: String): File {
        require(SAFE_ID.matches(id)) { "Bad account id" }
        return File(directory, "$id.auth.json")
    }

    private fun read(): Index = runCatching {
        if (index.isFile) json.decodeFromString(Index.serializer(), index.readText()) else Index()
    }.getOrDefault(Index())

    private fun write(value: Index) {
        writeAtomically(index, json.encodeToString(Index.serializer(), value).toByteArray())
    }

    private fun copyAtomically(from: File, to: File) = writeAtomically(to, from.readBytes())

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("No parent directory for ${target.name}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) error("Cannot create ${parent.absolutePath}")
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            ownerOnly(temp)
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun ownerOnly(file: File) {
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
    }

    @Serializable
    private data class Index(val active: String? = null, val accounts: List<SavedAccount> = emptyList()) {
        fun toState() = AccountVaultState(accounts, active?.takeIf { id -> accounts.any { it.id == id } })
    }

    companion object {
        const val AUTH_FILE = "auth.json"
        private const val INDEX_FILE = "accounts.json"
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
