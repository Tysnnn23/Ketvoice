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
        val hash = "a".repeat(64)
        assertEquals(PublisherAccess.Verification("token_hash", hash, "magiclink"), PublisherAccess.verification(
            "https://${PublisherAccess.HOST}/auth/v1/verify?token_hash=$hash&type=magiclink"))
        assertEquals(PublisherAccess.Verification("token", hash, "signup"), PublisherAccess.verification(
            "https://${PublisherAccess.HOST}/auth/v1/verify?token=$hash&type=signup"))
    }
    @Test fun rejectsUntrustedLinksAndRedirectedTokens() {
        listOf("http://${PublisherAccess.HOST}/auth/v1/verify?token=abc&type=email",
            "https://evil.example/auth/v1/verify?token=${"a".repeat(64)}&type=email",
            "https://${PublisherAccess.HOST}@evil.example/auth/v1/verify?token=abc&type=email",
            "javascript:alert(1)", "000").forEach {
            assertTrue(it, runCatching { PublisherAccess.verification(it) }.isFailure)
        }
    }
}
