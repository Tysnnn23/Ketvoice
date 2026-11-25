package com.ketvoice.app.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Sign-in by QR code: the code as typed and shown, the poll's pace and the function's answers. */
class DeviceCodeTest {
    @Test fun codesTypedByHandAreNormalised() {
        assertEquals("ABCDEFGH", DeviceCode.normalize("abcd-efgh"))
        assertEquals("ABCD2345", DeviceCode.normalize(" abcd 2345 "))
        assertNull(DeviceCode.normalize("ABCD-EFG"))
        assertNull(DeviceCode.normalize("ABCD-EFG0")) // 0, O, 1 and I are not in the alphabet
        assertNull(DeviceCode.normalize("ABCD-EFGI"))
        assertEquals("ABCD-EFGH", DeviceCode.format("ABCDEFGH"))
        assertEquals("https://ketvoice-web.vercel.app/link?code=ABCDEFGH", DeviceCode.url("ABCDEFGH"))
    }

    @Test fun pollingFollowsTheServerAndStopsAtExpiry() {
        val poll = LinkPoll(expiresAt = 20_000, intervalMs = 3_000)
        assertEquals(3_000L, poll.next(0, LinkAnswer.Waiting))
        assertEquals(8_000L, poll.next(3_000, LinkAnswer.SlowDown))
        assertEquals(5_000L, poll.intervalMs)
        assertEquals(18_000L, poll.next(8_000, LinkAnswer.Failed))
        assertNull(poll.next(18_000, LinkAnswer.Waiting))
        assertNull(LinkPoll(60_000).next(0, LinkAnswer.Approved("x".repeat(20))))
        assertNull(LinkPoll(60_000).next(0, LinkAnswer.Denied))
    }

    @Test fun answersOfTheFunction() {
        assertEquals(LinkAnswer.Waiting, LinkPoll.parse(200, JSONObject().put("status", "pending")))
        assertEquals(LinkAnswer.Approved("abcdefghijklmnop1234"),
            LinkPoll.parse(200, JSONObject().put("status", "approved").put("token_hash", "abcdefghijklmnop1234")))
        assertEquals(LinkAnswer.Failed, LinkPoll.parse(200, JSONObject().put("status", "approved").put("token_hash", "x y")))
        assertEquals(LinkAnswer.Expired, LinkPoll.parse(200, JSONObject().put("status", "used")))
        assertEquals(LinkAnswer.Expired, LinkPoll.parse(410, JSONObject().put("status", "expired")))
        assertEquals(LinkAnswer.SlowDown, LinkPoll.parse(429, null))
        assertEquals(LinkAnswer.Failed, LinkPoll.parse(500, null))
        assertEquals(LinkAnswer.Denied, LinkPoll.parse(200, JSONObject().put("status", "denied")))
    }
}
