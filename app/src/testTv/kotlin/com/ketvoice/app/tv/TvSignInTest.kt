package com.ketvoice.app.tv

import com.ketvoice.app.account.LinkAnswer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV's sign-in by QR code, driven by a fake server and a fake clock (no network, no waiting). */
class TvSignInTest {
    private class FakeLink(private val answers: MutableList<LinkAnswer>, private val started: TvSignIn.Started? = GOOD) : TvSignIn.Link {
        val polls = mutableListOf<Long>()
        var signedIn: String? = null
        var kind: String? = null
        var clock: () -> Long = { 0L }
        var failSignIn = false
        override fun start(name: String, kind: String): TvSignIn.Started? { this.kind = kind; return started }
        override fun poll(code: String, secret: String): LinkAnswer { polls += clock(); return answers.removeFirstOrNull() ?: LinkAnswer.Waiting }
        override suspend fun signIn(tokenHash: String) { if (failSignIn) error("verify failed"); signedIn = tokenHash }
    }

    private var now = 1_000_000L
    private val states = mutableListOf<TvSignIn.State>()

    /** Runs the whole flow; each wait moves the fake clock instead of sleeping and records what was shown. */
    private fun run(link: FakeLink) = runBlocking {
        link.clock = { now }
        TvSignIn.run("Salon", link, { now }, { wait -> now += wait; states += TvSignIn.state.value })
        states += TvSignIn.state.value
    }

    @After fun reset() = TvSignIn.cancel()

    @Test fun approvedCodeSignsTheTvIn() {
        val link = FakeLink(mutableListOf(LinkAnswer.Waiting, LinkAnswer.Waiting, LinkAnswer.Approved("t".repeat(32))))
        run(link)
        assertEquals("tv", link.kind)
        assertEquals("t".repeat(32), link.signedIn)
        assertEquals(TvSignIn.State.Done, TvSignIn.state.value)
        // The code was shown while waiting, with its expiry 300 s ahead.
        val shown = states.first() as TvSignIn.State.Showing
        assertEquals("ABCD2345", shown.code)
        assertEquals(1_000_000L + 300_000L, shown.expiresAt)
        // Polled every 3 s as the server asked.
        assertEquals(listOf(1_003_000L, 1_006_000L, 1_009_000L), link.polls)
    }

    @Test fun slowDownAndFailuresSpaceThePolls() {
        val link = FakeLink(mutableListOf(LinkAnswer.SlowDown, LinkAnswer.Failed, LinkAnswer.Approved("u".repeat(20))))
        run(link)
        // 3 s, then 5 s after « slow down », then 10 s after a failure (twice the new interval).
        assertEquals(listOf(1_003_000L, 1_008_000L, 1_018_000L), link.polls)
        assertEquals(TvSignIn.State.Done, TvSignIn.state.value)
    }

    @Test fun deniedExpiredAndUnreachableEndTheFlow() {
        run(FakeLink(mutableListOf(LinkAnswer.Denied)))
        assertEquals(TvSignIn.State.Failed(TvSignIn.DENIED), TvSignIn.state.value)
        run(FakeLink(mutableListOf(LinkAnswer.Expired)))
        assertEquals(TvSignIn.State.Expired, TvSignIn.state.value)
        run(FakeLink(mutableListOf(), started = null))
        assertEquals(TvSignIn.State.Failed(TvSignIn.UNAVAILABLE), TvSignIn.state.value)
        // A malformed answer from the server is never shown as a code.
        run(FakeLink(mutableListOf(), started = TvSignIn.Started("ABCD0000", "s".repeat(43), 300, 3)))
        assertEquals(TvSignIn.State.Failed(TvSignIn.UNAVAILABLE), TvSignIn.state.value)
    }

    @Test fun aCodeNobodyApprovesExpiresOnItsOwn() {
        val link = FakeLink(mutableListOf())
        run(link)
        assertEquals(TvSignIn.State.Expired, TvSignIn.state.value)
        // Never polled past the code's life (5 minutes at one poll every 3 s).
        assertTrue(link.polls.all { it < 1_000_000L + 300_000L })
        assertEquals(99, link.polls.size)
    }

    @Test fun aFailedVerifyIsShownAsAFailure() {
        val link = FakeLink(mutableListOf(LinkAnswer.Approved("v".repeat(32)))).apply { failSignIn = true }
        run(link)
        assertEquals(TvSignIn.State.Failed(TvSignIn.UNAVAILABLE), TvSignIn.state.value)
    }

    @Test fun countdownReadsMinutesAndSeconds() {
        assertEquals("4:05", TvSignIn.countdown(245))
        assertEquals("0:00", TvSignIn.countdown(0))
        assertEquals(0, TvSignIn.secondsLeft(10_000, 20_000))
        assertEquals(9, TvSignIn.secondsLeft(20_000, 10_500))
    }

    companion object {
        val GOOD = TvSignIn.Started("ABCD2345", "s".repeat(43), 300, 3)
    }
}
