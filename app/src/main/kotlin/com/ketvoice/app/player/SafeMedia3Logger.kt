package com.ketvoice.app.player

import androidx.media3.common.util.Log
import androidx.media3.datasource.HttpDataSource

/** Media3 exceptions may contain signed DataSpec URLs; never forward their text or stack traces. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SafeMedia3Logger : Log.Logger {
    override fun d(tag: String, message: String, throwable: Throwable?) = Unit
    override fun i(tag: String, message: String, throwable: Throwable?) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = report("warning", throwable)
    override fun e(tag: String, message: String, throwable: Throwable?) = report("error", throwable)

    private fun report(level: String, error: Throwable?) {
        val status = generateSequence(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        android.util.Log.w("KetvoicePlayback", "media3=$level${status?.let { " http=$it" }.orEmpty()}")
    }
}
