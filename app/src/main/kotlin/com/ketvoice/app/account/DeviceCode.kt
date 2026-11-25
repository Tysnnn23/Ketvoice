package com.ketvoice.app.account

/*
 * Sign-in by QR code (Windows 2.0, shared so a later phone release can approve or use it): a device without a session
 * asks the `device-link` edge function for a short code, shows it as a QR code of [DeviceCode.url] and in letters,
 * then asks every few seconds whether a signed-in person approved it on the website. The code lives 5 minutes and
 * works once; the server keeps only a digest of the device's poll secret.
 */
object DeviceCode {
    /** No 0/O or 1/I: a code read aloud or typed by hand stays unambiguous. The edge function uses the same alphabet. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    const val TTL_MS = 5 * 60_000L
    const val PAGE = "https://ketvoice-web.vercel.app/link"

    fun valid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** A code typed by hand (« abcd-efgh », spaces, lower case) as the server knows it, or null. */
    fun normalize(typed: String): String? = typed.uppercase().filter { it.isLetterOrDigit() }.takeIf(::valid)

    /** « ABCD-EFGH »: how it is shown. */
    fun format(code: String): String = if (code.length == LENGTH) code.take(4) + "-" + code.drop(4) else code

    /** What the QR code holds: the website's approval page for this code. */
    fun url(code: String): String = "$PAGE?code=$code"
}

/** What the device learnt from one poll. */
sealed interface LinkAnswer {
    data object Waiting : LinkAnswer
    /** Asked too often: wait longer between polls. */
    data object SlowDown : LinkAnswer
    /** Approved: [tokenHash] signs this device in once (`/auth/v1/verify`, type magiclink). */
    data class Approved(val tokenHash: String) : LinkAnswer
    data object Denied : LinkAnswer
    data object Expired : LinkAnswer
    /** The network or the server failed this time; the device tries again. */
    data object Failed : LinkAnswer
}

/**
 * When the device asks again, as RFC 8628 has it: every [intervalMs], 2 s more after each « slow down », backing off
 * after failures, and never past the code's expiry (null: stop).
 */
class LinkPoll(private val expiresAt: Long, intervalMs: Long = 3_000L) {
    var intervalMs: Long = intervalMs.coerceAtLeast(2_000L)
        private set
    private var failures = 0

    fun next(now: Long, answer: LinkAnswer): Long? = when (answer) {
        is LinkAnswer.Approved, LinkAnswer.Denied, LinkAnswer.Expired -> null
        LinkAnswer.SlowDown -> { intervalMs += 2_000L; schedule(now, intervalMs) }
        LinkAnswer.Failed -> { failures++; schedule(now, intervalMs * (1L shl minOf(failures, 3))) }
        LinkAnswer.Waiting -> { failures = 0; schedule(now, intervalMs) }
    }

    private fun schedule(now: Long, wait: Long): Long? = (now + wait).takeIf { it < expiresAt }

    companion object {
        /** The function's answer (`{status, token_hash?}`) to a [LinkAnswer]. */
        fun parse(status: Int, body: org.json.JSONObject?): LinkAnswer {
            if (status == 429) return LinkAnswer.SlowDown
            if (status !in 200..299 || body == null) return if (status in 400..499 && body?.optString("status") == "expired") LinkAnswer.Expired else LinkAnswer.Failed
            return when (body.optString("status")) {
                "pending" -> LinkAnswer.Waiting
                "slow_down" -> LinkAnswer.SlowDown
                "approved" -> body.optString("token_hash").takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,200}")) }
                    ?.let { LinkAnswer.Approved(it) } ?: LinkAnswer.Failed
                "denied" -> LinkAnswer.Denied
                "expired", "used" -> LinkAnswer.Expired
                else -> LinkAnswer.Failed
            }
        }
    }
}
