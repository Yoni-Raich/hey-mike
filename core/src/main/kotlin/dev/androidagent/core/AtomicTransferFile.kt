package dev.androidagent.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Same-filesystem publication: leave the previous target intact if commit fails. */
object AtomicTransferFile {
    fun commit(part: File, target: File, replace: Boolean) {
        if (replace) Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else {
            // ATOMIC_MOVE may overwrite even without REPLACE_EXISTING. link is an atomic no-clobber operation.
            Files.createLink(target.toPath(), part.toPath())
            part.delete()
        }
    }
}
