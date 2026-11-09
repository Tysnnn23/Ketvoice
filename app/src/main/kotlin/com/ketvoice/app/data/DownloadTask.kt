package com.ketvoice.app.data

enum class DownloadPhase { Resolving, Transferring, Paused, WaitingWifi, Finalizing, Failed, Cancelled, Complete }

data class DownloadTask(
    val phase: DownloadPhase = DownloadPhase.Resolving,
    val bytes: Long = 0,
    val total: Long? = null,
    val restarted: Boolean = false
) {
    val remaining: Long? get() = total?.takeIf { it > 0 }?.let { (it - bytes).coerceAtLeast(0) }
}
