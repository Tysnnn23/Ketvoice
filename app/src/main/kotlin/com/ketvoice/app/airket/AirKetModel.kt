package com.ketvoice.app.airket

import java.net.InetAddress

/** The sender's screen (old phone). */
internal data class AirKetSendUi(
    val phase: Phase = Phase.Idle,
    val code: String = "",
    /** What the QR code holds. */
    val link: String = "",
    val secondsLeft: Int = 0,
    val wrongTries: Int = 0,
    val titles: Int = 0,
    val downloadBytes: Long = 0,
    val sentBytes: Long = 0,
    val bytesPerSecond: Long = 0,
) {
    enum class Phase { Idle, Preparing, Waiting, Paired, Sending, Done, Expired, Locked, Failed, NoWifi }
}

internal data class AirKetFound(val serviceName: String, val name: String, val address: InetAddress, val port: Int)

/** What the sender announced, shown before the receiver chooses. */
internal data class AirKetOffer(
    val senderName: String, val backupBytes: Long, val titles: Int, val files: Int, val downloadBytes: Long,
    val leftOutDash: Int, val localHasData: Boolean, val heldBytes: Long,
)

internal data class AirKetSummary(
    val playlists: Int, val favourites: Int, val titles: Int, val files: Int, val failed: Int, val bytes: Long, val seconds: Long,
    val leftOutDash: Int, val withDownloads: Boolean, val replaced: Boolean,
)

/** The receiver's screen (new phone). */
internal data class AirKetReceiveUi(
    val phase: Phase = Phase.Idle,
    val found: List<AirKetFound> = emptyList(),
    val offer: AirKetOffer? = null,
    val label: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val summary: AirKetSummary? = null,
    val error: Error? = null,
    /** A code arrived from a QR code or a link, to put in the field. */
    val prefilledCode: String = "",
) {
    enum class Phase { Idle, Searching, Connecting, Offer, Receiving, Installing, Summary, Failed }
    enum class Error { NoWifi, WrongCode, Closed, NoRoom, Interrupted, Cancelled, Other }
}

/** What the foreground notification shows. */
internal data class AirKetOngoing(val sending: Boolean, val label: String, val done: Long, val total: Long, val bytesPerSecond: Long)

internal object AirKetFormat {
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%d Mo".format(bytes shr 20)
        bytes >= 1L shl 10 -> "%d Ko".format(bytes shr 10)
        else -> "$bytes o"
    }

    fun speed(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
