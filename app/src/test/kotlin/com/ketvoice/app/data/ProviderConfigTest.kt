package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class ProviderConfigTest {
    private val defaults = listOf(ProviderConfig.Rule("deezer", "https://api.deezer.com/", true, true),
        ProviderConfig.Rule("supabase-auth", "", true, false), ProviderConfig.Rule("stream-um", "", true, false))
    @Test fun remappingIsRestrictedToTheExactPublicBaseAndPreservesPaths() {
        val rules = ProviderConfig.parseRules("""[{"id":"deezer","base":"https://example.com/json/","enabled":true}]""", defaults)
        assertEquals("https://example.com/json/search?q=test", ProviderConfig.resolve("https://api.deezer.com/search?q=test", defaults, rules))
        assertEquals("https://api.deezer.com.evil.org/search", ProviderConfig.resolve("https://api.deezer.com.evil.org/search", defaults, rules))
        assertEquals("https://music.youtube.com/", ProviderConfig.resolve("https://music.youtube.com/", defaults, rules))
    }
    @Test fun disablingSkipsRequestsAndEmptyRulesRestoreDefaults() {
        val rules = ProviderConfig.parseRules("""[{"id":"deezer","base":"https://api.deezer.com/","enabled":false}]""", defaults)
        assertNull(ProviderConfig.resolve("https://api.deezer.com/search", defaults, rules))
        assertEquals("https://api.deezer.com/search", ProviderConfig.resolve("https://api.deezer.com/search", defaults, emptyMap()))
    }
    @Test fun invalidRulesNeverReplaceTheLastValidConfiguration() {
        for (base in listOf("http://example.com/", "https://127.0.0.1/", "https://localhost/", "https://a.local/", "https://user:pass@example.com/", "https://example.com/?key=secret")) {
            assertTrue(runCatching { ProviderConfig.parseRules("""[{"id":"deezer","base":"$base","enabled":true}]""", defaults) }.isFailure)
        }
        assertTrue(runCatching { ProviderConfig.parseRules("""[{"id":"supabase-auth","base":"","enabled":false}]""", defaults) }.isFailure)
    }
}
