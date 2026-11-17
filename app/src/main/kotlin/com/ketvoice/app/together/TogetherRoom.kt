package com.ketvoice.app.together

import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Room codes: six letters and digits that cannot be confused (no 0/O, 1/I/L), read in two groups of three. */
object RoomCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: java.util.Random = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** What the listener typed, made a code: spaces and dashes go, lower case is raised; null when it cannot be one. */
    fun normalize(input: String): String? {
        if (input.length > 20) return null
        val text = input.uppercase().filterNot { it == ' ' || it == '-' || it == '·' }
        return text.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun display(code: String): String = if (code.length == LENGTH) code.take(3) + " " + code.drop(3) else code

    fun topic(code: String): String = "ketogether:$code"
}

/**
 * The link of a session. It goes through the existing share page (`listen.html`, kind `together`), which opens
 * the app when it is installed; the `track` parameter carries the code and the host key's fingerprint, so a guest
 * who joins from the link or the QR code knows the real host from the first message.
 */
object TogetherLink {
    const val PAGE = "https://tysnnn23.github.io/Ketvoice/listen.html"
    const val KIND = "together"

    fun of(code: String, fingerprint: String): String =
        "$PAGE?track=" + enc("$code-$fingerprint") + "&title=Ketogether&artist=" + enc("Code " + RoomCode.display(code)) + "&kind=$KIND"

    data class Target(val code: String, val fingerprint: String?)

    /** The `track` value of a shared link of kind `together`. */
    fun parse(track: String): Target? {
        val parts = track.trim().split('-')
        val code = RoomCode.normalize(parts.firstOrNull().orEmpty()) ?: return null
        val fingerprint = parts.getOrNull(1)?.takeIf { it.length == Fingerprint.LENGTH && it.all { c -> c in Fingerprint.ALPHABET } }
        return Target(code, fingerprint)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

object Fingerprint {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8

    /** Eight characters from the SHA-256 of the host's public key. */
    fun of(publicKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray(Charsets.US_ASCII))
        var value = 0L
        for (i in 0 until 6) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return buildString { repeat(LENGTH) { append(ALPHABET[(value % ALPHABET.length).toInt()]); value /= ALPHABET.length } }
    }
}

/**
 * The host's key for one session (ECDSA P-256). Every host message is signed, so another member of a public
 * channel cannot pretend to be the host: guests keep the key they first saw, or the one the link names.
 */
class HostKey private constructor(private val pair: KeyPair) {
    val publicKey: String = Base64.getEncoder().encodeToString(pair.public.encoded)
    val fingerprint: String = Fingerprint.of(publicKey)

    fun sign(text: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(text.toByteArray(Charsets.UTF_8)); Base64.getEncoder().encodeToString(sign())
    }

    companion object {
        fun generate(): HostKey = HostKey(KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair())

        fun verify(publicKey: String, text: String, signature: String): Boolean = runCatching {
            if (publicKey.length > 200 || signature.length > 200) return false
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(text.toByteArray(Charsets.UTF_8)); verify(Base64.getDecoder().decode(signature))
            }
        }.getOrDefault(false)
    }
}

/**
 * What a broadcast carries: `{"v":1,"f":member,"b":"<message text>"}`, plus the signature `s` and key `k` for host
 * messages. The message stays a string so that the relay, which reorders JSON keys, cannot break its signature.
 */
object TogetherWire {
    const val VERSION = 1
    const val EVENT = "kt"
    private val member = Regex("[A-Za-z0-9_-]{8,40}")

    data class Envelope(val from: String, val body: String, val signature: String?, val key: String?)

    fun wrap(from: String, msg: TogetherMsg, key: HostKey? = null): JSONObject {
        val body = TogetherCodec.encode(msg)
        return JSONObject().put("v", VERSION).put("f", from).put("b", body).apply {
            if (key != null) { put("s", key.sign(body)); put("k", key.publicKey) }
        }
    }

    fun unwrap(payload: JSONObject): Envelope {
        require(payload.getInt("v") == VERSION)
        val from = payload.getString("f").also { require(member.matches(it)) }
        val body = payload.getString("b").also { require(it.length <= TogetherCodec.MAX_TEXT) }
        val signature = if (payload.has("s")) payload.getString("s") else null
        val key = if (payload.has("k")) payload.getString("k") else null
        return Envelope(from, body, signature, key)
    }

    fun memberId(random: java.util.Random = SecureRandom()): String =
        (1..16).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
}
