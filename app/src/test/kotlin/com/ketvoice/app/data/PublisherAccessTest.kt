package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class PublisherAccessTest {
    @Test fun onlyTheExactMailboxIsAHint() {
        assertTrue(PublisherAccess.matches("KonnektSupport@gmail.com"))
        assertFalse(PublisherAccess.matches("konnektsupport+admin@gmail.com"))
        assertFalse(PublisherAccess.matches("konnektsupport@gmail.com.example.org"))
        assertFalse(PublisherAccess.matches("other@gmail.com"))
    }
    @Test fun acceptsOtpOrTheExpectedProjectsVerificationLinkOnly() {
        assertEquals(PublisherAccess.Verification("token", "123456", "email"),
            PublisherAccess.verification("123456"))
        assertEquals(PublisherAccess.Verification("token", "12345678", "email"),
            PublisherAccess.verification(" 12345678 "))
        val hash = "a".repeat(64)
        assertEquals(PublisherAccess.Verification("token_hash", hash, "magiclink"), PublisherAccess.verification(
            "https://${PublisherAccess.HOST}/auth/v1/verify?token_hash=$hash&type=magiclink"))
        assertEquals(PublisherAccess.Verification("token_hash", hash, "signup"), PublisherAccess.verification(
            "https://${PublisherAccess.HOST}/auth/v1/verify?token=$hash&type=signup"))
        assertEquals(PublisherAccess.Verification("token_hash", hash, "email"), PublisherAccess.verification(
            "https://${PublisherAccess.HOST}/auth/v1/verify?token=$hash&type=email"))
    }
    @Test fun readsTheSessionFromTheAppLinkOnly() {
        val values = PublisherAccess.redirect(
            "ketvoice://auth#access_token=access&refresh_token=refresh&expires_at=123&token_type=bearer")
        assertEquals("access", values["access_token"])
        assertEquals("refresh", values["refresh_token"])
        assertEquals("123", values["expires_at"])
        assertTrue(runCatching {
            PublisherAccess.redirect("https://${PublisherAccess.HOST}/auth/v1/verify?token=abc&type=email")
        }.isFailure)
        assertTrue(runCatching {
            PublisherAccess.redirect("ketvoice://evil#access_token=access")
        }.isFailure)
        assertEquals("access_denied", PublisherAccess.redirect("ketvoice://auth#error=access_denied")["error"])
    }
    @Test fun rejectsUntrustedLinksAndRedirectedTokens() {
        listOf("http://${PublisherAccess.HOST}/auth/v1/verify?token=abc&type=email",
            "https://evil.example/auth/v1/verify?token=${"a".repeat(64)}&type=email",
            "https://${PublisherAccess.HOST}@evil.example/auth/v1/verify?token=abc&type=email",
            "javascript:alert(1)", "000").forEach {
            assertTrue(it, runCatching { PublisherAccess.verification(it) }.isFailure)
        }
    }
    @Test fun rejectsAmbiguousOrMalformedEmailLinks() {
        val base = "https://${PublisherAccess.HOST}/auth/v1/verify"
        val hash = "a".repeat(56)
        listOf("$base?token=$hash&type=magiclink&type=email",
            "$base?token=$hash&token=other&type=email",
            "$base?token=$hash&type=recovery",
            "$base?token=%ZZ&type=email",
            "$base?token=$hash&type=email#access_token=fake",
            "https://${PublisherAccess.HOST}:444/auth/v1/verify?token=$hash&type=email",
            "$base?token=${"a".repeat(17_000)}&type=email").forEach {
            assertTrue(runCatching { PublisherAccess.verification(it) }.isFailure)
        }
    }
    @Test fun rejectsAmbiguousOrMalformedAppLinks() {
        listOf("ketvoice://user@auth#access_token=fake",
            "ketvoice://auth:123#access_token=fake",
            "ketvoice://auth/unexpected#access_token=fake",
            "ketvoice://auth?access_token=query#access_token=fragment",
            "ketvoice://auth#refresh_token=a&refresh_token=b",
            "ketvoice://auth#refresh_token=%ZZ",
            "ketvoice://auth#access_token=${"a".repeat(17_000)}",
            "ketvoice://auth").forEach {
            assertTrue(runCatching { PublisherAccess.redirect(it) }.isFailure)
        }
    }
    @Test fun decodesRedirectValuesWithoutSplittingEncodedSeparators() {
        val values = PublisherAccess.redirect("ketvoice://auth/#error_description=expired%20link%26retry&error=access_denied")
        assertEquals("expired link&retry", values["error_description"])
        assertEquals(2, values.size)
    }
}
