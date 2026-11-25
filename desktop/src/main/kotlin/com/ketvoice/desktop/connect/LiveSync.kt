package com.ketvoice.desktop.connect

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.ServerClock
import com.ketvoice.app.account.SupabaseHttp
import com.ketvoice.app.player.Player
import com.ketvoice.app.remote.RemoteControl
import com.ketvoice.app.sync.CloudLibrary
import com.ketvoice.app.sync.DeviceIdentity
import com.ketvoice.app.sync.DeviceRow
import com.ketvoice.app.sync.DeviceState
import com.ketvoice.app.sync.NowPlayingDoc
import com.ketvoice.app.sync.NowPlayingSync
import com.ketvoice.app.sync.PlaybackMerge
import com.ketvoice.app.sync.PublishPolicy
import com.ketvoice.app.sync.RemoteSession
import com.ketvoice.desktop.remote.DesktopRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Live sync on Windows: while signed in (and « Synchronisation en direct » is on), this PC writes its row of
 * `ketvoice_devices` (name, kind, its session's id, what it plays: the title, position, the queue around it, playing or
 * not) on every change and at [PublishPolicy]'s intervals, and the account's `now_playing` document too, so the phone
 * of today offers to resume what the PC played. It reads the other devices every 30 s, when the window comes to the
 * front and when Home shows, for « Reprendre là où tu en étais » and the list of devices. Live control and transfers
 * between devices that are open go through phone control's private room (`RemoteControl`), which already carries
 * their state.
 */
object LiveSync {
    private const val TABLE = "/rest/v1/ketvoice_devices"
    private const val READ_EVERY_MS = 30_000L
    private val _devices = MutableStateFlow<List<DeviceState>>(emptyList())
    /** The account's devices, this one included, newest first. */
    val devices: StateFlow<List<DeviceState>> = _devices
    private val _offer = MutableStateFlow<RemoteSession?>(null)
    val offer: StateFlow<RemoteSession?> = _offer
    private var dismissedAt = 0L
    private var context: Context? = null
    private var last: PublishPolicy.Mark? = null
    private var lastDoc: JSONObject? = null
    private var lastRead = 0L
    @Volatile private var readSoon = true

    val myId: String get() = context?.let(DeviceIdentity::id).orEmpty()

    fun start(context: Context, scope: CoroutineScope) {
        this.context = context
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { round(System.currentTimeMillis()) }
                delay(1_000)
            }
        }
    }

    /** Read the others now (the window came to the front, Home shows). */
    fun refreshSoon() { readSoon = true }

    private suspend fun round(now: Long) {
        if (AccountSession.userId() == null) { last = null; _devices.value = emptyList(); _offer.value = null; return }
        if (ConnectPrefs.liveSync.value) publishIfDue()
        if (readSoon || now - lastRead >= READ_EVERY_MS) { readSoon = false; lastRead = now; read() }
    }

    private suspend fun publishIfDue() {
        val s = Player.state.value
        val mark = PublishPolicy.Mark(s.current?.id, s.isPlaying, s.positionMs, ServerClock.now())
        val before = last
        if (!PublishPolicy.due(before, mark)) return
        val ctx = context ?: return
        // A paused title written again only says « still here »: what it played, and when, stays as it was.
        val idle = before != null && !before.playing && !mark.playing && before.trackId == mark.trackId
        val playing = if (idle && lastDoc != null) lastDoc else NowPlayingDoc.encode(s, DeviceIdentity.id(ctx), DesktopRemote.computerName(), mark.at)
        if (!writeRow(playing, mark.at)) return
        last = mark
        lastDoc = playing
        // The single document the phone of today reads: while playing and when it pauses, never a stale state over a newer one.
        val paused = before != null && before.playing && !mark.playing
        if (playing != null && (mark.playing || paused)) runCatching { CloudLibrary.putNowPlaying(playing, DeviceIdentity.id(ctx)) }
    }

    private suspend fun writeRow(playing: JSONObject?, at: Long): Boolean {
        val ctx = context ?: return false
        val uid = AccountSession.userId() ?: return false
        val row = DeviceRow.encode(DeviceIdentity.id(ctx), DesktopRemote.computerName(), "pc", at, sessionId(), playing).put("user_id", uid)
        val r = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "$TABLE?on_conflict=user_id,device_id", row.toString().toByteArray(), token,
                headers = mapOf("Prefer" to "return=minimal,resolution=merge-duplicates"))
        }
        return r.ok
    }

    /** The account's devices and the single document, merged; the offer follows. This PC signs out if it was revoked. */
    suspend fun read() {
        val uid = AccountSession.userId() ?: return
        val r = AccountSession.authorized { token ->
            SupabaseHttp.request("GET", "$TABLE?select=device_id,name,kind,state,revoked_at&user_id=eq.$uid&order=updated_at.desc&limit=50", token = token)
        }
        if (!r.ok) return
        val rows = JSONArray(r.text).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(DeviceRow::decode) } }
        val single = runCatching { CloudLibrary.fetch(NowPlayingSync.KIND)?.doc?.let(NowPlayingDoc::decode) }.getOrNull()
        val merged = PlaybackMerge.devices(rows, single)
        _devices.value = merged
        if (merged.any { it.deviceId == myId && it.revoked }) { revokedHere(); return }
        val s = Player.state.value
        _offer.value = PlaybackMerge.offer(merged, myId, ServerClock.now(), s.isPlaying, s.current?.id, dismissedAt)
    }

    /** Another device signed this PC out: its refresh token is already dead, so the local session goes now. */
    private suspend fun revokedHere() {
        _devices.value = emptyList(); _offer.value = null
        AccountSession.endedOnServer("Ce PC a été déconnecté depuis un autre appareil.")
    }

    /** For renders and tests: devices and an offer shown without any network. */
    internal fun showForTest(devices: List<DeviceState>, offer: RemoteSession?) { _devices.value = devices; _offer.value = offer }

    fun dismiss() { _offer.value?.let { dismissedAt = it.at }; _offer.value = null }

    /** « Reprendre »: the same queue, the same title, where it is now (the other device moved on while it played). */
    fun resume(session: RemoteSession) {
        _offer.value = null
        Player.resumeFrom(session.queue, session.index, session.positionAt(ServerClock.now()), true)
    }

    /** « Transférer la lecture ici »: from an open device through the room (it stops there), else from what it wrote. */
    fun transferHere(device: DeviceState): Boolean {
        if (RemoteControl.devices.value.any { it.id == device.deviceId } && RemoteControl.takeFrom(device.deviceId)) return true
        val session = device.session ?: return false
        resume(session)
        return true
    }

    /** « Lire sur cet appareil »: only an open device in the room can take it. */
    fun playThere(deviceId: String): Boolean = RemoteControl.devices.value.any { it.id == deviceId } && RemoteControl.sendTo(deviceId)

    /** This session's id (the `session_id` claim of its access token): what remote sign-out revokes on the server. */
    internal fun sessionId(): String? = runCatching {
        val access = AccountSession.tokens.session?.access ?: return null
        jwtClaim(access, "session_id")
    }.getOrNull()

    internal fun jwtClaim(jwt: String, name: String): String? {
        val part = jwt.split('.').getOrNull(1) ?: return null
        val json = JSONObject(String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')), Charsets.UTF_8))
        return json.optString(name).takeIf { it.isNotBlank() }
    }

    /** « Déconnecter » another device: the `devices` edge function revokes its session on the server. */
    suspend fun signOutDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val r = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "/functions/v1/devices/revoke", JSONObject().put("device_id", deviceId).toString().toByteArray(), token)
        }
        if (r.ok) { readSoon = true }
        r.ok
    }

    /** « Oublier »: a device that will not come back leaves the list (its row only). */
    suspend fun forget(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: return@withContext false
        val r = AccountSession.authorized { token ->
            SupabaseHttp.request("DELETE", "$TABLE?user_id=eq.$uid&device_id=eq.${SupabaseHttp.encode(deviceId)}", token = token)
        }
        if (r.ok) readSoon = true
        r.ok
    }
}
