package com.ketvoice.app.tv

import com.ketvoice.app.account.DeviceCode
import com.ketvoice.app.account.LinkAnswer
import com.ketvoice.app.account.LinkPoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Signing a TV in with the phone (Ketvoice TV 1.0.0): the device-code flow of Windows 2.0. The TV asks the
 * `device-link` edge function for a short code, shows it as a QR code of [DeviceCode.url] and in letters, then asks
 * every few seconds until someone signed in on the website (or the phone's browser) approves it; the one-time token
 * it then gets becomes this TV's own session, kept in the same encrypted store as the phone's. No password is ever
 * typed on the TV, and the publishing account is never signed in this way (the server refuses it).
 */
object TvSignIn {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Showing(val code: String, val expiresAt: Long) : State
        data object SigningIn : State
        data object Done : State
        data object Expired : State
        /** [message] is French source text, translated at display. */
        data class Failed(val message: String) : State
    }

    /** What the server answered to `/start`. */
    data class Started(val code: String, val secret: String, val expiresInS: Long, val intervalS: Long)

    /** The network side, replaced in tests. */
    interface Link {
        /** Null when the server refused or could not be reached; throws [TooMany] on 429. */
        fun start(name: String, kind: String): Started?
        fun poll(code: String, secret: String): LinkAnswer
        suspend fun signIn(tokenHash: String)
    }

    class TooMany : Exception()

    const val KIND = "tv"
    const val UNAVAILABLE = "Connexion impossible pour le moment."
    const val TOO_MANY = "Trop de tentatives. Réessayez dans quelques minutes."
    const val DENIED = "La connexion a été refusée."

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** Shows a new code and waits for its approval (a previous one is dropped). */
    fun start(scope: CoroutineScope, name: String, link: Link = SupabaseLink,
              clock: () -> Long = System::currentTimeMillis, sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try { run(name, link, clock, sleep) } catch (e: CancellationException) { throw e } catch (_: TooMany) {
                _state.value = State.Failed(TOO_MANY)
            } catch (_: Exception) {
                _state.value = State.Failed(UNAVAILABLE)
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _state.value = State.Idle }

    /** For renders and tests: a state shown without any network. */
    internal fun showForTest(state: State) { job?.cancel(); job = null; _state.value = state }

    /** The whole flow, from the code to the session. Public for tests, which drive it with a fake [Link]. */
    internal suspend fun run(name: String, link: Link, clock: () -> Long, sleep: suspend (Long) -> Unit) {
        _state.value = State.Starting
        val started = link.start(name, KIND)?.takeIf { DeviceCode.valid(it.code) && it.secret.matches(SECRET) }
            ?: run { _state.value = State.Failed(UNAVAILABLE); return }
        val expiresAt = clock() + started.expiresInS.coerceIn(30, 600) * 1000
        _state.value = State.Showing(started.code, expiresAt)
        val poll = LinkPoll(expiresAt, started.intervalS.coerceIn(2, 10) * 1000)
        var next: Long? = clock() + poll.intervalMs
        while (next != null) {
            sleep((next - clock()).coerceAtLeast(0))
            when (val answer = link.poll(started.code, started.secret)) {
                is LinkAnswer.Approved -> { finish(link, answer.tokenHash); return }
                LinkAnswer.Denied -> { _state.value = State.Failed(DENIED); return }
                LinkAnswer.Expired -> break
                else -> next = poll.next(clock(), answer)
            }
        }
        _state.value = State.Expired
    }

    private suspend fun finish(link: Link, tokenHash: String) {
        _state.value = State.SigningIn
        try {
            link.signIn(tokenHash)
            _state.value = State.Done
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            _state.value = State.Failed(UNAVAILABLE)
        }
    }

    private val SECRET = Regex("[A-Za-z0-9_-]{32,128}")

    /** Seconds left on a shown code, for the countdown. */
    fun secondsLeft(expiresAt: Long, now: Long): Long = ((expiresAt - now) / 1000).coerceAtLeast(0)

    /** « 4:05 » */
    fun countdown(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** The real server: the `device-link` edge function and the account's own verify call. */
    object SupabaseLink : Link {
        private const val PATH = "/functions/v1/device-link"

        override fun start(name: String, kind: String): Started? {
            val body = JSONObject().put("name", name).put("kind", kind).toString().toByteArray()
            val r = com.ketvoice.app.account.SupabaseHttp.request("POST", "$PATH/start", body)
            if (r.status == 429) throw TooMany()
            val json = runCatching { JSONObject(r.text) }.getOrNull() ?: return null
            if (!r.ok) return null
            return Started(json.optString("code"), json.optString("secret"), json.optLong("expires_in", 300), json.optLong("interval", 3))
        }

        override fun poll(code: String, secret: String): LinkAnswer = runCatching {
            val body = JSONObject().put("code", code).put("secret", secret).toString().toByteArray()
            val r = com.ketvoice.app.account.SupabaseHttp.request("POST", "$PATH/poll", body)
            LinkPoll.parse(r.status, runCatching { JSONObject(r.text) }.getOrNull())
        }.getOrDefault(LinkAnswer.Failed)

        override suspend fun signIn(tokenHash: String) = com.ketvoice.app.account.AccountSession.signInWithLink(tokenHash)
    }
}
