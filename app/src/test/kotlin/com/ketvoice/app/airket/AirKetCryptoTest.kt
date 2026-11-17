package com.ketvoice.app.airket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom

class AirKetCryptoTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val okm = Hkdf.derive(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Hkdf.hex(okm))
    }

    @Test fun theGroupIsASafePrimeAndTheBlindingPointsAreInIt() {
        assertEquals(2048, Spake2.P.bitLength())
        assertTrue(Spake2.P.isProbablePrime(30))
        assertTrue(Spake2.Q.isProbablePrime(30))
        for (point in listOf(Spake2.G, Spake2.M, Spake2.N)) {
            assertNotEquals(BigInteger.ONE, point)
            assertEquals(BigInteger.ONE, point.modPow(Spake2.Q, Spake2.P))
        }
        assertNotEquals(Spake2.M, Spake2.N)
    }

    private fun exchange(clientCode: String, serverCode: String): Pair<ByteArray, ByteArray> {
        val client = Spake2(Spake2.Role.Client, clientCode)
        val server = Spake2(Spake2.Role.Server, serverCode)
        return client.finish(server.message) to server.finish(client.message)
    }

    @Test fun sameCodeGivesTheSameSecretAndAWrongCodeDoesNot() {
        val (a, b) = exchange("123456", "123456")
        assertArrayEquals(a, b)
        val (c, d) = exchange("123456", "123457")
        assertFalse(c.contentEquals(d))
    }

    @Test fun anElementOutsideTheGroupIsRefused() {
        val client = Spake2(Spake2.Role.Client, "000000")
        val bad = listOf(BigInteger.ONE, Spake2.P.subtract(BigInteger.ONE), BigInteger.ZERO)
        bad.forEach { value -> assertTrue(runCatching { client.finish(Spake2.pad(value)) }.isFailure) }
        assertTrue(runCatching { client.finish(ByteArray(10)) }.isFailure)
        // 3 is not a square modulo p (p ≡ 7 mod 8 and 3 is a non-residue of this prime): outside the subgroup.
        val outside = (2..50).map { BigInteger.valueOf(it.toLong()) }.first { it.modPow(Spake2.Q, Spake2.P) != BigInteger.ONE }
        assertTrue(runCatching { client.finish(Spake2.pad(outside)) }.isFailure)
    }

    private fun keys(): Pair<AirKetKeys, AirKetKeys> {
        val client = Spake2(Spake2.Role.Client, "424242")
        val server = Spake2(Spake2.Role.Server, "424242")
        val cn = ByteArray(16) { 1 }; val sn = ByteArray(16) { 2 }
        return AirKetKeys.derive(client.finish(server.message), cn, sn, client.message, server.message) to
            AirKetKeys.derive(server.finish(client.message), cn, sn, client.message, server.message)
    }

    @Test fun bothSidesDeriveTheSameKeysAndTheConfirmationsDiffer() {
        val (a, b) = keys()
        assertArrayEquals(a.clientToServer, b.clientToServer)
        assertArrayEquals(a.serverToClient, b.serverToClient)
        assertFalse(a.clientToServer.contentEquals(a.serverToClient))
        assertArrayEquals(a.confirmation(true), b.confirmation(true))
        assertFalse(a.confirmation(true).contentEquals(a.confirmation(false)))
    }

    @Test fun framesRoundTripAndTheLengthIsTheSealedSize() {
        val (a, b) = keys()
        val send = FrameCipher(a.clientToServer); val open = FrameCipher(b.clientToServer)
        val payload = ByteArray(1000) { it.toByte() }
        val wire = send.seal(AirKetFrames.DATA, payload)
        val length = ((wire[0].toInt() and 255) shl 24) or ((wire[1].toInt() and 255) shl 16) or ((wire[2].toInt() and 255) shl 8) or (wire[3].toInt() and 255)
        assertEquals(wire.size - 4, length)
        assertEquals(1 + payload.size + 16, length)
        val (type, body) = open.open(wire.copyOfRange(4, wire.size))
        assertEquals(AirKetFrames.DATA, type)
        assertArrayEquals(payload, body)
        // the same bytes are not the same frame twice: the counter changes the nonce
        assertFalse(send.seal(AirKetFrames.DATA, payload).contentEquals(wire))
    }

    @Test fun anAlteredReplayedReorderedOrWronglyKeyedFrameDoesNotOpen() {
        val (a, b) = keys()
        val send = FrameCipher(a.clientToServer)
        val first = send.seal(1, byteArrayOf(1, 2, 3)).drop(4).toByteArray()
        val second = send.seal(2, byteArrayOf(4, 5, 6)).drop(4).toByteArray()
        // altered
        val flipped = first.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertTrue(runCatching { FrameCipher(b.clientToServer).open(flipped) }.isFailure)
        // reordered: the second frame first
        assertTrue(runCatching { FrameCipher(b.clientToServer).open(second) }.isFailure)
        // replayed: the first frame twice
        val receiver = FrameCipher(b.clientToServer)
        receiver.open(first)
        assertTrue(runCatching { receiver.open(first) }.isFailure)
        // wrong direction key
        assertTrue(runCatching { FrameCipher(b.serverToClient).open(first) }.isFailure)
        // truncated
        assertTrue(runCatching { FrameCipher(b.clientToServer).open(first.copyOf(first.size - 1)) }.isFailure)
        assertTrue(runCatching { FrameCipher(b.clientToServer).open(ByteArray(5)) }.isFailure)
        // too large to seal
        assertTrue(runCatching { send.seal(1, ByteArray(AirKetFrames.MAX_PAYLOAD + 1)) }.isFailure)
    }

    @Test fun codesAreSixDigitsAndTheLinkCarriesThem() {
        repeat(200) { assertTrue(AirKetCode.generate(SecureRandom()).matches(Regex("\\d{6}"))) }
        assertEquals("012345", AirKetCode.normalize("012 345"))
        assertEquals("012345", AirKetCode.normalize("012-345"))
        assertNull(AirKetCode.normalize("12345"))
        assertNull(AirKetCode.normalize("1234567"))
        assertNull(AirKetCode.normalize("12a456"))
        assertEquals("012 345", AirKetCode.display("012345"))
        assertTrue(AirKetCode.link("012345").startsWith("ketvoice://listen?track=012345&kind=airket"))
        assertTrue(AirKetCode.link("012345", "192.168.1.5", 40000).endsWith("&h=192.168.1.5&p=40000"))
    }

    // ------------------------------------------------------------------------------ the manifest and the resume

    private fun entry(kind: AirKetEntry.Kind = AirKetEntry.Kind.Audio, name: String = "a1.mp3", size: Long = 100) = AirKetEntry(kind, name, size)

    @Test fun aManifestRoundTripsAndRefusesUnsafeNames() {
        val manifest = AirKetManifest(listOf(entry(), entry(AirKetEntry.Kind.Canvas, "album-1.mp4", 5_000)), mapOf("kv:um:1" to "{\"file\":\"x.txt\"}"), leftOutDash = 3)
        val back = AirKetManifest.decode(manifest.encode())
        assertEquals(manifest, back)
        assertEquals(2, back.entries.size); assertEquals(1, back.titles); assertEquals(5_100, back.downloadBytes)
        for (name in listOf("../x.mp3", "a/b.mp3", ".hidden", "a..b.mp3", "", "x".repeat(200), "a b.mp3", "a\u0000.mp3")) {
            val bad = String(manifest.encode()).replace("a1.mp3", name.replace("\u0000", "\\u0000"))
            assertTrue("accepted $name", runCatching { AirKetManifest.decode(bad.toByteArray()) }.isFailure)
        }
        val duplicate = AirKetManifest(listOf(entry(), entry())).encode()
        assertTrue(runCatching { AirKetManifest.decode(duplicate) }.isFailure)
    }

    @Test fun theLayoutAcceptsOnlyTheExtensionOfTheKind() {
        val layout = AirKetLayout(File("/data/files"))
        assertTrue(layout.accepts(entry()))
        assertFalse(layout.accepts(entry(name = "a1.exe")))
        assertFalse(layout.accepts(entry(AirKetEntry.Kind.Meta, "a.lyrics.json")))
        assertTrue(layout.accepts(entry(AirKetEntry.Kind.Lyrics, "a.lyrics.json")))
        assertFalse(layout.accepts(entry(AirKetEntry.Kind.Canvas, "a.json")))
        assertEquals(File("/data/files/canvas/c.mp4"), layout.fileOf(entry(AirKetEntry.Kind.Canvas, "c.mp4")))
        assertEquals(File("/data/files/stems/masks/z.kvs"), layout.fileOf(entry(AirKetEntry.Kind.Stem, "z.kvs")))
    }

    @Test fun stagingResumesFromThePartAndKeepsVerifiedFiles() {
        val dir = createTempDir("airket-staging")
        try {
            val staging = AirKetStaging(dir)
            val e = entry(size = 1000)
            assertEquals(0, staging.resumeFrom(e))
            staging.part(e).writeBytes(ByteArray(400))
            assertEquals(400, staging.resumeFrom(e))
            assertEquals(400, staging.held(listOf(e)))
            staging.part(e).writeBytes(ByteArray(1000))
            staging.markVerified(e)
            assertFalse(staging.part(e).exists())
            assertEquals(1000, staging.resumeFrom(e))
            assertEquals(1000, staging.held(listOf(e)))
            // the same id always lands on the same file
            assertEquals(staging.part(e), AirKetStaging(dir).part(entry(size = 1000)))
            staging.clear()
            assertEquals(0, staging.held(listOf(e)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aMetadataRecordIsMadeForThisPhone() {
        val canvasDir = File("/data/user/0/app/files/canvas"); val downloads = File("/data/user/0/app/files/downloads")
        val text = """{"id":"yt:abcdefghijk","title":"T","artist":"A","file":"abc.mp3","canvasFile":"/other/place/files/canvas/c9.mp4","canvasSpotifyId":"c9",
            "coverUrl":"file:///other/place/files/downloads/abc.cover.jpg","dashManifestUrl":"https://x"}"""
        val (rewritten, record) = AirKetInstaller.localMeta(text, canvasDir, downloads)!!
        assertTrue(rewritten.contains("/data/user/0/app/files/canvas/c9.mp4"))
        assertEquals("file:///data/user/0/app/files/downloads/abc.cover.jpg", record.getString("coverUrl"))
        assertFalse(record.has("dashManifestUrl"))
        assertNull(AirKetInstaller.localMeta("""{"id":"x","title":"T","file":"../../etc/passwd"}""", canvasDir, downloads))
        assertNull(AirKetInstaller.localMeta("not json", canvasDir, downloads))
    }
}
