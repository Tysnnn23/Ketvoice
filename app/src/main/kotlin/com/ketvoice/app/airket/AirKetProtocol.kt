package com.ketvoice.app.airket

import com.ketvoice.app.handoff.LanBoundary
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Air Ket (7.0, owner: « Sauvegarde complète transférable lorsque 2 appareils sont sur le même Wi-Fi, donc transfert
 * Air Ket »): everything Ketvoice keeps on a phone moves to another phone on the same Wi-Fi, directly, with no
 * account and no server.
 *
 * Roles. The old phone (Settings → Air Ket) is the SENDER: it shows a six-digit code and a QR code, announces
 * `_airket._tcp` on the local network (NSD) and listens on an ephemeral TCP port bound to the Wi-Fi address only.
 * The new phone is the RECEIVER (« Recevoir via Air Ket », also offered in the onboarding import step): it finds
 * the sender, the listener types the code (or opens the QR code with the camera, which opens Ketvoice with the
 * code filled in), and the receiver connects.
 *
 * Why not LanHttp: its requests are JSON bodies of 64 KiB at most and a fixed list of paths; Air Ket streams
 * megabytes in both directions inside one encrypted session, so it uses its own frames. It does reuse
 * [LanBoundary]: a peer outside the sender's private IPv4 subnet is closed before a byte is read, and loopback
 * is accepted only through [AirKetGuard.loopbackForTests], which a release build ignores.
 *
 * Security (see AirKetCrypto.kt). SPAKE2 over RFC 3526 group 14 with the code as the password; key confirmation
 * with the client first; AES-256-GCM frames with a counter nonce per direction. A wrong code never reveals
 * anything: the sender answers nothing, counts the failure and, after [AirKetCode.MAX_WRONG], closes for good.
 * The code expires after [AirKetCode.LIFETIME_MS]. Nothing readable crosses the network. What a listener on the
 * Wi-Fi can still see: that an Air Ket session exists, its size and timing.
 *
 * Wire (all lengths big endian):
 *   handshake  C→S "AKT1" · 16-byte nonce · 256-byte SPAKE2 element
 *              S→C 16-byte nonce · 256-byte element
 *              C→S 32-byte confirmation · S→C 32-byte confirmation (only when the first was right)
 *   frames     4-byte length of the sealed bytes · AES-GCM(type byte · payload), payload ≤ 64 KiB
 *   messages   S→C HELLO (json summary) right after the handshake;
 *              C→S DOC{id} → S→C BEGIN{id,size,sha256,offset} · DATA… · END   (ids "backup", "manifest")
 *              C→S FILE{id,offset} → the same answer, from the byte [offset] (resume);
 *              C→S BYE; either side ERROR{reason}.
 *   A file is verified by its SHA-256 (the sender hashes it when asked, the receiver compares after the last byte,
 *   hashing the part it already had when it resumes); a bad file is asked again once from the start.
 *
 * What is sent: the personal backup (PersonalBackup: settings, favourites, playlists, artists, history, Replay and
 * Wrapped counters, lyrics offsets, Ketmix mix points, blocked and favourite artists), the custom lyrics files,
 * and, when the listener asks for it, the downloaded titles with their lyrics, covers, Canvas (one file per album)
 * and voice separations. Left out on purpose: the Ketvoice account session, the Discord and YouTube Music
 * sessions and every token or key, custom source configuration, local music links (they point at this phone's
 * storage), caches, the queue and the optional separation models (downloaded again from Settings). Downloads in
 * DASH form are streamed from the network and have no file to send: they are listed as left out.
 */
internal object AirKetFrames {
    const val MAX_PAYLOAD = 65_536
    const val CHUNK = 48 * 1024
    const val MAGIC = "AKT1"
    const val HELLO = 1
    const val DOC = 0x20
    const val FILE = 0x22
    const val BEGIN = 0x32
    const val DATA = 0x30
    const val END = 0x31
    const val ERROR = 0x7E
    const val BYE = 0x7F
    const val NONCE = 16
    const val CONFIRM = 32
    const val SOCKET_TIMEOUT_MS = 20_000
    const val HANDSHAKE_TIMEOUT_MS = 10_000
}

/** A code that was wrong, expired, locked, or a sender that is busy: the receiver reads it as "no answer". */
internal class AirKetRefused(val reason: Reason) : Exception(reason.name) {
    enum class Reason { WrongCode, Closed }
}

/** Where the sender may listen and whom it may talk to. */
internal object AirKetGuard {
    /** Set by the loopback integration test only; a release build ignores it, whatever its value. */
    @Volatile var loopbackForTests = false

    private val loopbackAllowed get() = com.ketvoice.app.BuildConfig.DEBUG && loopbackForTests

    fun accepts(boundary: LanBoundary, peer: InetAddress): Boolean =
        (!peer.isLoopbackAddress || loopbackAllowed) && boundary.allows(peer)

    fun requireUsable(boundary: LanBoundary) {
        require(!boundary.address.isLoopbackAddress || loopbackAllowed) { "Loopback is refused" }
    }
}

/** Frames over a socket's streams. */
internal class FrameChannel(input: InputStream, private val output: OutputStream, private val sealer: FrameCipher, private val opener: FrameCipher) {
    private val data = DataInputStream(input)

    @Synchronized fun send(type: Int, payload: ByteArray = ByteArray(0), length: Int = payload.size) {
        output.write(sealer.seal(type, payload, length)); output.flush()
    }

    fun send(type: Int, json: JSONObject) = send(type, json.toString().toByteArray(Charsets.UTF_8))

    fun receive(): Pair<Int, ByteArray> {
        val length = data.readInt()
        require(length in 17..(AirKetFrames.MAX_PAYLOAD + 17)) { "Bad frame length" }
        val sealed = ByteArray(length)
        data.readFully(sealed)
        return opener.open(sealed)
    }

    fun receiveJson(type: Int): JSONObject {
        val (kind, body) = receive()
        if (kind == AirKetFrames.ERROR) throw AirKetRefused(AirKetRefused.Reason.Closed)
        require(kind == type) { "Unexpected frame" }
        return JSONObject(String(body, Charsets.UTF_8))
    }
}

/** The two halves of the handshake, over plain streams. */
internal object AirKetHandshake {
    private fun read(input: DataInputStream, size: Int) = ByteArray(size).also { input.readFully(it) }

    /** The sender's side: returns the channel once the receiver proved it knows the code; throws [AirKetRefused] otherwise. */
    fun serve(input: InputStream, output: OutputStream, code: String, random: SecureRandom = SecureRandom()): FrameChannel {
        val data = DataInputStream(input)
        require(String(read(data, 4), Charsets.US_ASCII) == AirKetFrames.MAGIC)
        val clientNonce = read(data, AirKetFrames.NONCE)
        val clientElement = read(data, Spake2.WIDTH)
        val spake = Spake2(Spake2.Role.Server, code, random)
        val serverNonce = ByteArray(AirKetFrames.NONCE).also(random::nextBytes)
        output.write(serverNonce + spake.message); output.flush()
        val keys = AirKetKeys.derive(spake.finish(clientElement), clientNonce, serverNonce, clientElement, spake.message)
        val proof = read(data, AirKetFrames.CONFIRM)
        if (!MessageDigest.isEqual(proof, keys.confirmation(true))) throw AirKetRefused(AirKetRefused.Reason.WrongCode)
        output.write(keys.confirmation(false)); output.flush()
        return FrameChannel(data, output, FrameCipher(keys.serverToClient), FrameCipher(keys.clientToServer))
    }

    /** The receiver's side. A wrong code ends with [AirKetRefused] (WrongCode): the sender never answers the proof. */
    fun join(input: InputStream, output: OutputStream, code: String, random: SecureRandom = SecureRandom()): FrameChannel {
        val data = DataInputStream(input)
        val spake = Spake2(Spake2.Role.Client, code, random)
        val clientNonce = ByteArray(AirKetFrames.NONCE).also(random::nextBytes)
        output.write(AirKetFrames.MAGIC.toByteArray(Charsets.US_ASCII) + clientNonce + spake.message); output.flush()
        val serverNonce = try { read(data, AirKetFrames.NONCE) } catch (_: java.io.IOException) { throw AirKetRefused(AirKetRefused.Reason.Closed) }
        val serverElement = read(data, Spake2.WIDTH)
        val keys = AirKetKeys.derive(spake.finish(serverElement), clientNonce, serverNonce, spake.message, serverElement)
        output.write(keys.confirmation(true)); output.flush()
        val answer = try { read(data, AirKetFrames.CONFIRM) } catch (_: java.io.IOException) { throw AirKetRefused(AirKetRefused.Reason.WrongCode) }
        if (!MessageDigest.isEqual(answer, keys.confirmation(false))) throw AirKetRefused(AirKetRefused.Reason.WrongCode)
        return FrameChannel(data, output, FrameCipher(keys.clientToServer), FrameCipher(keys.serverToClient))
    }
}
