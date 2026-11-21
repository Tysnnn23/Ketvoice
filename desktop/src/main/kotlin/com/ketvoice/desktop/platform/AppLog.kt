package com.ketvoice.desktop.platform

import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's log: `Data\logs\ketvoice.log`, rolled at [maxBytes] into `ketvoice.1.log` …
 * `ketvoice.<keep>.log`. Before [init] (and in tests) lines only go to stderr.
 */
object AppLog {
    private var dir: File? = null
    private var maxBytes = 1L shl 20
    private var keep = 4
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()
    private val stderr: PrintStream = System.err

    val logDir: File get() = dir ?: File(android.content.Context.app.dataDir, "logs")

    /** Starts writing to [logDir]; stderr is copied there too (library warnings, stack traces). */
    fun init(logDir: File = File(android.content.Context.app.dataDir, "logs"), maxBytes: Long = 1L shl 20, keep: Int = 4, teeStderr: Boolean = true) {
        logDir.mkdirs()
        synchronized(lock) { dir = logDir; this.maxBytes = maxBytes; this.keep = keep }
        if (teeStderr) System.setErr(PrintStream(Tee(stderr) { raw(it) }, true, Charsets.UTF_8))
        i("app", "Ketvoice ${com.ketvoice.app.BuildConfig.VERSION_NAME} on ${System.getProperty("os.name")} ${System.getProperty("os.version")}, java ${System.getProperty("java.version")}")
    }

    fun i(tag: String, message: String) = line("I", tag, message, null)
    fun w(tag: String, message: String) = line("W", tag, message, null)
    fun e(tag: String, message: String, t: Throwable? = null) = line("E", tag, message, t)

    private fun line(level: String, tag: String, message: String, t: Throwable?) {
        val text = buildString {
            append(LocalDateTime.now().format(stamp)).append(' ').append(level).append(' ').append(tag).append(": ").append(message)
            if (t != null) append('\n').append(stackTrace(t).trimEnd())
            append('\n')
        }
        if (dir == null) stderr.print(text) else write(text)
    }

    private fun raw(text: String) { if (dir != null) write(text) }

    private fun write(text: String) {
        synchronized(lock) {
            val d = dir ?: return
            runCatching {
                val file = File(d, "ketvoice.log")
                if (file.length() + text.length > maxBytes) roll(d)
                file.appendText(text)
            }
        }
    }

    /** ketvoice.log → ketvoice.1.log → … ; the oldest beyond [keep] is dropped. */
    internal fun roll(d: File) {
        File(d, "ketvoice.$keep.log").delete()
        for (n in keep - 1 downTo 1) File(d, "ketvoice.$n.log").takeIf { it.exists() }?.renameTo(File(d, "ketvoice.${n + 1}.log"))
        File(d, "ketvoice.log").takeIf { it.exists() }?.renameTo(File(d, "ketvoice.1.log"))
    }

    internal fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private class Tee(private val out: OutputStream, private val copy: (String) -> Unit) : OutputStream() {
        private val buf = java.io.ByteArrayOutputStream()
        override fun write(b: Int) { out.write(b); synchronized(buf) { buf.write(b); if (b == '\n'.code) flushLine() } }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            synchronized(buf) { buf.write(b, off, len); if (len > 0 && b[off + len - 1] == '\n'.code.toByte()) flushLine() }
        }
        override fun flush() = out.flush()
        private fun flushLine() { copy(buf.toString(Charsets.UTF_8)); buf.reset() }
    }
}

/** Uncaught exceptions: a report file in `Data\logs` (the last [KEEP] are kept) and a log line. */
object CrashHandler {
    private const val KEEP = 10
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun install(dir: File = AppLog.logDir) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching { write(dir, thread.name, t, LocalDateTime.now()) }
            AppLog.e("crash", "uncaught on ${thread.name}", t)
            previous?.uncaughtException(thread, t)
        }
    }

    fun write(dir: File, threadName: String, t: Throwable, now: LocalDateTime): File {
        dir.mkdirs()
        val file = File(dir, "crash-${now.format(fileStamp)}.txt")
        file.writeText(report(threadName, t, now))
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        return file
    }

    fun report(threadName: String, t: Throwable, now: LocalDateTime): String = buildString {
        appendLine("Ketvoice ${com.ketvoice.app.BuildConfig.VERSION_NAME} crash report")
        appendLine("time: $now")
        appendLine("thread: $threadName")
        appendLine("os: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        appendLine("java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        appendLine("memory: ${Runtime.getRuntime().totalMemory() shr 20} MB used of ${Runtime.getRuntime().maxMemory() shr 20} MB")
        appendLine()
        append(AppLog.stackTrace(t))
    }
}
