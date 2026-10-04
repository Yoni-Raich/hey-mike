package dev.androidagent.remote

import dev.androidagent.core.FilePlace
import java.io.File

/**
 * One saved computer as a place for `copy_file`, reached over SFTP on the
 * computer's own SSH link. It is named by the computer's name, or its id.
 */
class ComputerPlace(private val hub: RemoteHub, private val computer: RemoteComputer) : FilePlace {
    override val name: String = computer.label
    override val aliases: Set<String> = setOf(computer.id)

    override suspend fun download(path: String, base: String?, target: File, progress: (Long, Long) -> Unit): FilePlace.Fetched {
        val full = fullPath(path, base, computer.label)
        hub.download(computer.id, full, target, Long.MAX_VALUE, progress)
        return FilePlace.Fetched(target.length(), fileName(full))
    }

    override suspend fun stat(path: String, base: String?): FilePlace.Stat {
        val full = fullPath(path, base, computer.label)
        return FilePlace.Stat(hub.size(computer.id, full), full, fileName(full))
    }

    override suspend fun upload(source: File, path: String, base: String?, replace: Boolean, progress: (Long, Long) -> Unit): String {
        val full = fullPath(path, base, computer.label)
        hub.upload(computer.id, source, full, replace, progress)
        return "${computer.label}:$full"
    }

    companion object {
        /**
         * An absolute path as given; a relative one against [base], in the
         * computer's own style. Without a base (a phone chat naming a
         * computer) a relative path has nothing to mean.
         */
        internal fun fullPath(path: String, base: String?, label: String): String {
            val absolute = path.matches(Regex("^[A-Za-z]:[\\\\/].*")) || path.startsWith("\\\\") || path.startsWith("/")
            if (absolute) return path
            requireNotNull(base) { "Give a full path on $label, such as $label:C:\\Users\\me\\file.txt or $label:/home/me/file.txt." }
            if (base.startsWith("/")) return base.trimEnd('/') + "/" + path.trimStart('/')
            return base.trimEnd('\\', '/') + "\\" + path.replace('/', '\\').trimStart('\\')
        }

        internal fun fileName(path: String): String =
            path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { "file" }
    }
}
