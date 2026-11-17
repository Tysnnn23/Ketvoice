package com.ketvoice.app.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The listener's session in memory: one refresh at a time (Supabase refresh tokens are single use),
 * renewed a minute before it expires or when the server answered 401, saved through [persist] each time
 * it changes. A refused session is forgotten ([onRejected]); a network failure keeps it for later.
 */
internal class AccountTokens(
    private val refresher: suspend (String) -> AuthSession,
    private val persist: (AuthSession?) -> Unit,
    private val onRejected: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    @Volatile var session: AuthSession? = null
        private set

    fun set(next: AuthSession?) { session = next; persist(next) }

    /** A valid access token, renewed first when needed; throws when signed out or when the session was refused. */
    suspend fun access(): String = mutex.withLock {
        val current = session ?: throw SessionRejected("Connexion requise")
        if (!current.needsRefresh(clock())) return@withLock current.access
        val next = try { refresher(current.refresh) } catch (error: SessionRejected) {
            session = null; persist(null); onRejected(error.message.orEmpty()); throw error
        }
        // The server may answer without the user's e-mail on a refresh: the one known is kept.
        val kept = next.copy(email = next.email.ifBlank { current.email })
        session = kept; persist(kept)
        kept.access
    }

    /** The server did not accept the access token (401): the next call renews it. */
    suspend fun expire() = mutex.withLock { session = session?.copy(access = "", expiresAt = 0L) }
}
