package com.ketvoice.app.discord

import org.junit.Assert.*
import org.junit.Test

/** The OAuth path is verified without a device: PKCE, redirect parsing and exchange. */
class DiscordOAuthTest {
    // RFC 7636 Appendix B test vector.
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test fun pkceChallengeMatchesTheSpecificationVector() {
        assertEquals(challenge, DiscordOAuth.challenge(verifier))
        val other = DiscordOAuth.newVerifier()
        assertEquals(43, DiscordOAuth.newVerifier().length)
        assertTrue(other.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals("16 random bytes encode to 22 base64url chars", 22, DiscordOAuth.newState().length)
    }

    @Test fun authorizeUrlCarriesPkceRedirectAndScopes() {
        val url = DiscordOAuth.authorizeUrl("state-123", challenge)
        assertTrue(url.startsWith("https://discord.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=${DiscordOAuth.APPLICATION_ID}"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge=$challenge"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-123"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fketvoice.netlify.app%2Fdiscord.html"))
        assertFalse("The public client must never send a secret", url.contains("client_secret"))
    }

    @Test fun exchangeFormSendsExactlyThePublicClientFields() {
        val form = DiscordOAuth.exchangeForm("the-code", verifier)
        val fields = form.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(setOf("grant_type", "client_id", "code", "redirect_uri", "code_verifier"), fields.keys)
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals("the-code", fields["code"])
        assertEquals(verifier, fields["code_verifier"])
        assertEquals(DiscordOAuth.APPLICATION_ID.toString(), fields["client_id"])
        assertFalse(form.contains("client_secret"))
    }

    @Test fun exchangeParsesTokensAndKeepsServerErrorsSilent() {
        val ok = DiscordOAuth.exchange("code", verifier) { body ->
            assertTrue(body.contains("code_verifier="))
            200 to """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":604800}"""
        }
        assertEquals("access-1", ok?.access)
        assertEquals("refresh-1", ok?.refresh)
        assertEquals(604800L, ok?.expiresIn)

        assertNull(DiscordOAuth.exchange("code", verifier) { 400 to """{"error":"invalid_grant"}""" })
        assertNull(DiscordOAuth.exchange("code", verifier) { 0 to "" })
        assertNull(DiscordOAuth.exchange("code", verifier) { 200 to """{"access_token":"only"}""" })
        assertNull("A blank code must not hit the network", DiscordOAuth.exchange("", verifier) { error("no call") })
    }

    @Test fun everyAcceptedRedirectFormIsParsedWithAStrictStateCheck() {
        val state = "s3cr3t"
        val https = "https://ketvoice.netlify.app/discord.html?code=abc&state=$state"
        assertEquals("abc", DiscordOAuth.redirect(https, state)?.code)
        val custom = "discord-${DiscordOAuth.APPLICATION_ID}:/authorize/callback?code=abc&state=$state"
        assertEquals("abc", DiscordOAuth.redirect(custom, state)?.code)
        val bridge = "ketvoice://discord?code=abc&state=$state"
        assertEquals("abc", DiscordOAuth.redirect(bridge, state)?.code)
        val withFragment = "ketvoice://discord?code=abc&state=$state#_"
        assertEquals("abc", DiscordOAuth.redirect(withFragment, state)?.code)
    }

    @Test fun forgedOrMalformedCallbacksAreRefused() {
        val state = "s3cr3t"
        assertNull("State must match", DiscordOAuth.redirect(
            "https://ketvoice.netlify.app/discord.html?code=abc&state=other", state))
        assertNull("Wrong host", DiscordOAuth.redirect(
            "https://evil.example/discord.html?code=abc&state=$state", state))
        assertNull("Wrong path", DiscordOAuth.redirect(
            "https://ketvoice.netlify.app/other.html?code=abc&state=$state", state))
        assertNull("Missing code", DiscordOAuth.redirect(
            "https://ketvoice.netlify.app/discord.html?state=$state", state))
        assertNull("Empty state expectation", DiscordOAuth.redirect(
            "https://ketvoice.netlify.app/discord.html?code=abc&state=$state", ""))
        assertNull(DiscordOAuth.redirect(null, state))
    }

    @Test fun encodedValuesSurviveTheRoundTrip() {
        val state = "a b+c/d"
        val encoded = java.net.URLEncoder.encode(state, "UTF-8")
        val redirect = "https://ketvoice.netlify.app/discord.html?code=x%2By&state=$encoded"
        val parsed = DiscordOAuth.redirect(redirect, state)
        assertEquals("x+y", parsed?.code)
    }
}
