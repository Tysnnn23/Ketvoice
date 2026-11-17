package com.ketvoice.app.account

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** The Ketvoice account: sessions, their renewal, what the forms accept, the photo's frame. */
class AccountSessionTest {
    private val user = "0b8f6d1e-7f3a-4c62-9a55-2f1c0e9d4b11"
    private fun session(access: String, refresh: String, expiresAt: Long, email: String = "a@b.example") =
        AuthSession(access, refresh, expiresAt, user, email)

    @Test fun aValidTokenIsUsedAsItIs() = runBlocking {
        var refreshes = 0
        val tokens = AccountTokens({ refreshes++; error("not called") }, {}, {}, clock = { 1_000L })
        tokens.set(session("A1", "R1", expiresAt = 1_000L + 10 * 60_000))
        assertEquals("A1", tokens.access())
        assertEquals(0, refreshes)
    }

    @Test fun aTokenAboutToExpireIsRenewedOnceAndSaved() = runBlocking {
        val saved = mutableListOf<AuthSession?>()
        var refreshes = 0
        val tokens = AccountTokens({ refresh ->
            refreshes++; delay(20); assertEquals("R1", refresh); session("A2", "R2", 10_000_000, email = "")
        }, { saved += it }, {}, clock = { 1_000L })
        tokens.set(session("A1", "R1", expiresAt = 1_000L + 30_000))
        // Three calls at once: Supabase refresh tokens are single use, so only one renewal goes out.
        val results = (1..3).map { async { tokens.access() } }.awaitAll()
        assertEquals(listOf("A2", "A2", "A2"), results)
        assertEquals(1, refreshes)
        assertEquals("R2", saved.last()?.refresh)
        assertEquals("the e-mail known is kept when the refresh does not repeat it", "a@b.example", tokens.session?.email)
    }

    @Test fun aRefusedSessionIsForgottenAndTheListenerToldWhy() = runBlocking {
        var told: String? = null
        val saved = mutableListOf<AuthSession?>()
        val tokens = AccountTokens({ throw SessionRejected("Votre session a expiré. Reconnectez-vous.") }, { saved += it }, { told = it }, clock = { 1_000L })
        tokens.set(session("", "R1", 0))
        assertThrows(SessionRejected::class.java) { runBlocking { tokens.access() } }
        assertNull(tokens.session)
        assertNull(saved.last())
        assertEquals("Votre session a expiré. Reconnectez-vous.", told)
    }

    @Test fun withoutNetworkTheSessionIsKeptForLater() = runBlocking {
        val tokens = AccountTokens({ throw IOException("offline") }, {}, { fail("not rejected") }, clock = { 1_000L })
        tokens.set(session("", "R1", 0))
        assertThrows(IOException::class.java) { runBlocking { tokens.access() } }
        assertEquals("R1", tokens.session?.refresh)
    }

    @Test fun a401MakesTheNextCallRenewTheToken() = runBlocking {
        var refreshes = 0
        val tokens = AccountTokens({ refreshes++; session("A2", "R2", 10_000_000) }, {}, {}, clock = { 1_000L })
        tokens.set(session("A1", "R1", 10_000_000))
        tokens.expire()
        assertEquals("A2", tokens.access())
        assertEquals(1, refreshes)
    }

    @Test fun supabaseSessionsAreReadStrictly() {
        val json = JSONObject().put("access_token", "AT").put("refresh_token", "RT").put("expires_in", 3600)
            .put("user", JSONObject().put("id", user).put("email", "a@b.example"))
        val parsed = AuthApi.parseSession(json, now = 1_000)
        assertEquals(1_000 + 3_600_000L, parsed.expiresAt)
        assertTrue(parsed.needsRefresh(parsed.expiresAt - 30_000))
        assertFalse(parsed.needsRefresh(parsed.expiresAt - 120_000))
        // A refresh answer without a new refresh token keeps the previous one.
        assertEquals("OLD", AuthApi.parseSession(JSONObject(json.toString()).apply { remove("refresh_token") }, 0, "OLD").refresh)
        assertThrows(IllegalArgumentException::class.java) {
            AuthApi.parseSession(JSONObject(json.toString()).put("user", JSONObject().put("id", "not-a-uuid")), 0)
        }
    }

    @Test fun storedSessionsRoundTrip() {
        val original = session("A", "R", 42)
        assertEquals(original, AccountSession.decode(AccountSession.encode(original)))
        assertNull(AccountSession.decode("{\"refresh\":\"\",\"user\":\"x\"}"))
        assertNull(AccountSession.decode("garbage"))
    }

    @Test fun serverErrorsBecomePlainMessages() {
        assertEquals("E-mail ou mot de passe incorrect.", AuthApi.message(400, JSONObject().put("error_code", "invalid_credentials")))
        assertEquals("Un compte existe déjà avec cet e-mail.", AuthApi.message(422, JSONObject().put("error_code", "user_already_exists")))
        assertEquals("Trop de tentatives. Réessayez dans quelques minutes.", AuthApi.message(429, null))
        assertEquals("Cette méthode de connexion n'est pas encore disponible.", AuthApi.message(400, JSONObject().put("error_code", "provider_disabled")))
        assertTrue(AuthApi.rejected(400, JSONObject().put("error_code", "refresh_token_not_found")))
        assertFalse(AuthApi.rejected(500, JSONObject().put("error_code", "unexpected_failure")))
    }

    @Test fun theFormsCheckBeforeSendingAnything() {
        AccountRules.checkCredentials("ami@exemple.fr", "12345678")
        assertThrows(IllegalArgumentException::class.java) { AccountRules.checkCredentials("ami@", "12345678") }
        assertThrows(IllegalArgumentException::class.java) { AccountRules.checkCredentials("ami@exemple.fr", "1234567") }
        AccountRules.checkCredentials("ami@exemple.fr", "1", signUp = false)
        assertEquals("Léa Martin", AccountRules.displayName("  Léa   Martin "))
        assertThrows(IllegalArgumentException::class.java) { AccountRules.displayName("") }
        assertEquals("lea.m", AccountRules.handle("@Lea.M"))
        assertNull(AccountRules.handle("  "))
        assertThrows(IllegalArgumentException::class.java) { AccountRules.handle("a b") }
    }

    @Test fun thePhotosFrameStaysInsideThePicture() {
        assertEquals(CropSquare(0, 50, 600), AvatarImage.square(600, 800, 1f, 0f, -0.5f))
        assertEquals(CropSquare(200, 0, 400), AvatarImage.square(800, 400, 1f, 0f, 0f))
        val zoomed = AvatarImage.square(1000, 1000, 2f, 1f, 1f)
        assertEquals(CropSquare(500, 500, 500), zoomed)
        val wild = AvatarImage.square(300, 200, 50f, 9f, -9f)
        assertTrue(wild.left + wild.side <= 300 && wild.top >= 0 && wild.side >= 1)
    }

    @Test fun avatarFilesAreNamedAfterTheirStoredObject() {
        assertEquals("avatar-1763380000000.jpg", Profiles.localName("$user/avatar-1763380000000.jpg"))
        assertFalse(Profiles.localName("$user/avatar-../../x.jpg").contains("/"))
    }
}
