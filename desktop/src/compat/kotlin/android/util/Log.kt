package android.util

/** android.util.Log for the shared code: errors and warnings go to stderr. */
object Log {
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable? = null): Int { System.err.println("E/$tag: $msg${tr?.let { " ($it)" } ?: ""}"); return 0 }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable? = null): Int { System.err.println("W/$tag: $msg${tr?.let { " ($it)" } ?: ""}"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
}
