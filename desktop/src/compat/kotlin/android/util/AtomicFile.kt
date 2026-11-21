package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Android's AtomicFile on the JVM: writes go to `<name>.new` and are moved over the base file
 * on [finishWrite] (an atomic rename where the file system has one); [failWrite] drops them.
 */
class AtomicFile(val baseFile: File) {
    private val newFile = File(baseFile.path + ".new")

    fun startWrite(): FileOutputStream {
        baseFile.parentFile?.mkdirs()
        return FileOutputStream(newFile)
    }

    fun finishWrite(str: FileOutputStream?) {
        str ?: return
        runCatching { str.fd.sync() }
        str.close()
        try {
            Files.move(newFile.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(newFile.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun failWrite(str: FileOutputStream?) {
        runCatching { str?.close() }
        newFile.delete()
    }

    fun openRead(): FileInputStream = FileInputStream(if (!baseFile.exists() && newFile.exists()) newFile else baseFile)

    fun readFully(): ByteArray = openRead().use { it.readBytes() }

    fun delete() { baseFile.delete(); newFile.delete() }
}
