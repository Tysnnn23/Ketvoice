package com.ketvoice.app.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordOAuthTest {

    @Test fun challengeMatchesTheRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DiscordOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test fun verifierIsUrlSafeAndUnique() {
        val first = DiscordOAuth.newVerifier()
        val second = DiscordOAuth.newVerifier()
        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(first, second)
    }

    @Test fun stateIsRandomAndUrlSafe() {
        val first = DiscordOAuth.newState()
        assertEquals(22, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(first, DiscordOAuth.newState())
    }

    @Test fun authorizationUrlCarriesPkceScopesAndRedirect() {
        val url = DiscordOAuth.authorizeUrl("state123", "challenge456")
        assertTrue(url.startsWith("https://discord.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=1566454532890820668"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge=challenge456"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state123"))
        assertTrue(url.contains("redirect_uri=discord-1566454532890820668%3A%2Fauthorize%2Fcallback"))
        assertTrue(url.contains("scope=openid%20sdk.social_layer_presence"))
    }
}
