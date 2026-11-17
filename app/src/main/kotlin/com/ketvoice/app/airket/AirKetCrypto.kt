package com.ketvoice.app.airket

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Air Ket's cryptography (7.0): everything here is pure JVM so that it is unit tested on the host.
 *
 * The pairing code is only six digits, so it is never used as a key or sent, not even hashed. Both phones run
 * SPAKE2 (a password-authenticated key exchange) over the 2048-bit group of RFC 3526: each side sends one group
 * element blinded by the code, both reach the same secret only when the codes are equal, and a listener on the
 * network (or a phone that guesses) learns nothing it can test the code against offline. The one test a guesser
 * gets per connection is answered by the receiver, and the sender stops after five wrong tries.
 *
 * Keys: HKDF-SHA256 over the shared secret and the transcript (both nonces and both group elements) gives a
 * confirmation key for each side and one AES-256-GCM key per direction. Frames are numbered by the nonce
 * (a counter), so a dropped, repeated, reordered or altered frame fails to open.
 */
internal object AirKetCode {
    const val DIGITS = 6
    /** The code lives five minutes, then the sender closes. */
    const val LIFETIME_MS = 5 * 60_000L
    const val MAX_WRONG = 5

    fun generate(random: SecureRandom = SecureRandom()): String =
        (random.nextInt(1_000_000)).toString().padStart(DIGITS, '0')

    /** The digits of what was typed or scanned, or null when it is not a code. */
    fun normalize(text: String): String? =
        text.takeIf { t -> t.all { it.isDigit() || it == ' ' || it == '-' } }?.filter { it.isDigit() }?.takeIf { it.length == DIGITS }

    fun display(code: String): String = code.take(3) + " " + code.drop(3)

    /** What the QR code of the sender holds: the phone's camera opens Ketvoice with the code filled in. */
    fun link(code: String, host: String? = null, port: Int = 0): String =
        "ketvoice://listen?track=$code&kind=airket&title=Air%20Ket" + if (host != null && port > 0) "&h=$host&p=$port" else ""
}

internal object Hkdf {
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    fun derive(secret: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray =
        derive(secret, salt, info.toByteArray(Charsets.UTF_8), length)

    /** HKDF-SHA256 (RFC 5869). */
    fun derive(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(salt, secret)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
            out.write(block); counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach(d::update) }.digest()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** SPAKE2 in the order-q subgroup of the RFC 3526 group 14 (p = 2q + 1, both prime). */
internal class Spake2(role: Role, code: String, random: SecureRandom = SecureRandom()) {
    enum class Role { Client, Server }

    private val w = BigInteger(1, Hkdf.derive(code.toByteArray(Charsets.US_ASCII), "airket-spake2-v1".toByteArray(), "password", 32))
    private val x = BigInteger(256, random).let { if (it.signum() == 0) BigInteger.ONE else it }
    private val mine = if (role == Role.Client) M else N
    private val theirs = if (role == Role.Client) N else M
    /** The element to send: g^x blinded by the code. */
    val message: ByteArray = pad(G.modPow(x, P).multiply(mine.modPow(w, P)).mod(P))

    /** The secret both sides share when the codes are equal; throws when the peer's element is not in the group. */
    fun finish(peer: ByteArray): ByteArray {
        require(peer.size == WIDTH)
        val y = BigInteger(1, peer)
        require(y > BigInteger.ONE && y < P.subtract(BigInteger.ONE) && y.modPow(Q, P) == BigInteger.ONE) { "Invalid element" }
        val unblinded = y.multiply(theirs.modPow(w, P).modInverse(P)).mod(P)
        return pad(unblinded.modPow(x, P))
    }

    companion object {
        const val WIDTH = 256
        val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16)
        val Q: BigInteger = P.subtract(BigInteger.ONE).shiftRight(1)
        val G: BigInteger = BigInteger.valueOf(4)
        /** Points with no known discrete log: squares of hashes of fixed labels. */
        val M: BigInteger = point("airket-spake2-M")
        val N: BigInteger = point("airket-spake2-N")

        private fun point(label: String): BigInteger {
            val wide = (0 until 8).map { Hkdf.sha256(label.toByteArray(), byteArrayOf(it.toByte())) }.reduce { a, b -> a + b }
            return BigInteger(1, wide).mod(P).let { it.multiply(it).mod(P) }
        }

        fun pad(value: BigInteger): ByteArray {
            val raw = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            require(raw.size <= WIDTH)
            return ByteArray(WIDTH - raw.size) + raw
        }
    }
}

/** The keys of one session, derived from the SPAKE2 secret and the transcript. */
internal class AirKetKeys private constructor(private val clientConfirm: ByteArray, private val serverConfirm: ByteArray,
                                              val clientToServer: ByteArray, val serverToClient: ByteArray) {
    companion object {
        fun derive(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray,
                   clientElement: ByteArray, serverElement: ByteArray): AirKetKeys {
            val transcript = Hkdf.sha256("AirKet1".toByteArray(), clientNonce, serverNonce, clientElement, serverElement)
            fun k(info: String) = Hkdf.derive(secret, transcript, info)
            return AirKetKeys(k("confirm client"), k("confirm server"), k("data client to server"), k("data server to client"))
        }
    }

    /** What each side proves it holds before any data moves; the client goes first, so a guesser learns nothing from the server. */
    fun confirmation(fromClient: Boolean): ByteArray =
        Hkdf.hmac(if (fromClient) clientConfirm else serverConfirm, (if (fromClient) "client" else "server").toByteArray())
}

/** Seals and opens the frames of one direction: AES-256-GCM, a counter for the nonce, the length as associated data. */
internal class FrameCipher(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private var counter = 0L

    private fun nonce(): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
        return n
    }

    private fun header(cipherLength: Int) = byteArrayOf((cipherLength ushr 24).toByte(), (cipherLength ushr 16).toByte(),
        (cipherLength ushr 8).toByte(), cipherLength.toByte())

    /** The wire bytes of one frame: the length of what follows, then the sealed type and payload. */
    fun seal(type: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        require(length in 0..AirKetFrames.MAX_PAYLOAD)
        val plain = ByteArray(1 + length).also { it[0] = type.toByte(); System.arraycopy(payload, 0, it, 1, length) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce()))
        cipher.updateAAD(header(plain.size + 16))
        val sealed = cipher.doFinal(plain)
        counter++
        return header(sealed.size) + sealed
    }

    /** Opens the bytes that followed the length; throws when anything was changed, lost or replayed. */
    fun open(sealed: ByteArray): Pair<Int, ByteArray> {
        require(sealed.size in 17..(AirKetFrames.MAX_PAYLOAD + 17))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce()))
        cipher.updateAAD(header(sealed.size))
        val plain = cipher.doFinal(sealed)
        counter++
        return (plain[0].toInt() and 0xFF) to plain.copyOfRange(1, plain.size)
    }
}
