package com.ketvoice.desktop.connect

import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.DeviceCode
import com.ketvoice.app.account.LinkAnswer
import com.ketvoice.app.account.LinkPoll
import com.ketvoice.app.account.SupabaseHttp
import com.ketvoice.desktop.remote.DesktopRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * « Se connecter avec un QR code »: this PC asks the `device-link` edge function for a code, shows it (QR code of
 * [DeviceCode.url] and in letters), then asks every few seconds until someone signed in on the website approves it;
 * the one-time token it then gets signs this PC in. Nothing about the PC but its name goes to the server.
 */
object DeviceLink {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Showing(val code: String, val expiresAt: Long) : State
        data object SigningIn : State
        data object Done : State
        data object Expired : State
        data class Failed(val message: String) : State
    }

    private const val PATH = "/functions/v1/device-link"
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** Shows a new code and waits for its approval (a previous one is dropped). */
    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try { run() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "Connexion impossible pour le moment.")
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _state.value = State.Idle }

    /** For renders and tests: a state shown without any network. */
    internal fun showForTest(state: State) { _state.value = state }

    private suspend fun run() {
        _state.value = State.Starting
        val body = JSONObject().put("name", DesktopRemote.computerName()).put("kind", "pc")
        val r = SupabaseHttp.request("POST", "$PATH/start", body.toString().toByteArray())
        val json = runCatching { JSONObject(r.text) }.getOrNull()
        if (r.status == 429) error("Trop de tentatives. Réessayez dans quelques minutes.")
        val code = json?.optString("code")?.takeIf(DeviceCode::valid)
        val secret = json?.optString("secret")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{32,128}")) }
        if (!r.ok || code == null || secret == null) error("Connexion impossible pour le moment.")
        val expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 300).coerceIn(30, 600) * 1000
        _state.value = State.Showing(code, expiresAt)
        val poll = LinkPoll(expiresAt, json.optLong("interval", 3).coerceIn(2, 10) * 1000)
        var next: Long? = System.currentTimeMillis() + poll.intervalMs
        while (next != null) {
            delay((next - System.currentTimeMillis()).coerceAtLeast(0))
            val answer = ask(code, secret)
            when (answer) {
                is LinkAnswer.Approved -> { finish(answer.tokenHash); return }
                LinkAnswer.Denied -> { _state.value = State.Failed("La connexion a été refusée."); return }
                LinkAnswer.Expired -> break
                else -> next = poll.next(System.currentTimeMillis(), answer)
            }
        }
        _state.value = State.Expired
    }

    private fun ask(code: String, secret: String): LinkAnswer = runCatching {
        val body = JSONObject().put("code", code).put("secret", secret).toString().toByteArray()
        val r = SupabaseHttp.request("POST", "$PATH/poll", body)
        LinkPoll.parse(r.status, runCatching { JSONObject(r.text) }.getOrNull())
    }.getOrDefault(LinkAnswer.Failed)

    private suspend fun finish(tokenHash: String) {
        _state.value = State.SigningIn
        AccountSession.signInWithLink(tokenHash)
        _state.value = State.Done
    }
}
