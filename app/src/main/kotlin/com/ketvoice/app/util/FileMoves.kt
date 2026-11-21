package com.ketvoice.app.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Renames this file onto [target], replacing it. `File.renameTo` does not replace an existing file on Windows
 * (the desktop app compiles this shared code), so a finished temporary file goes over the old one here instead.
 */
fun File.moveOver(target: File): Boolean = runCatching {
    Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    true
}.recoverCatching {
    Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    true
}.getOrDefault(false)
