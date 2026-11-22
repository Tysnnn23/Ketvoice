package com.ketvoice.app.together

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Ketogether (6.1, owner 2026-11-17: « écouter la même musique en même temps à plusieurs, avec file d'attente
 * partagée »). A host opens a room on Supabase Realtime (public broadcast + presence, no table); guests join by code
 * or link and play the same catalogue titles through their own sources, kept in time by the host's signed state.
 *
 * Everything runs on the main thread: the transport hands its events over, a loop calls [TogetherSession.tick].
 * The single-device Wi-Fi handoff is separate and unchanged.
 */
object Ketogether {
    private const val ENDPOINT = "wss://xodklrunqlzyeiqtovgx.supabase.co/realtime/v1/websocket"
    private const val PREFS = "ketogether"
    private const val MAX_CODE_TRIES = 3

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }

    private val _ui = MutableStateFlow(TogetherUi())
    val ui: StateFlow<TogetherUi> = _ui

    /** The Ketogether sheet is open. */
    val sheet = mutableStateOf(false)
    /** A link or a QR code asked to join this room; the sheet asks before joining. */
    val invitation = mutableStateOf<TogetherLink.Target?>(null)

    private var session: TogetherSession? = null
    private var transport: TogetherTransport? = null
    private var loop: Job? = null
    private var context: Context? = null
    private var codeTries = 0
    private var savedModes: Triple<Boolean, Boolean, Boolean>? = null
    /** True while the session itself drives the player, so that its own calls pass the guest's gate. */
    private var applying = false

    private fun now() = SystemClock.elapsedRealtime()

    val active: Boolean get() = session?.ended == false

    /** Ketmix stays off for a guest: the host's music decides the transitions (the host's own Ketmix is fine). */
    val holdsKetmix: Boolean get() = session?.let { !it.ended && it.role == TogetherRole.Guest } == true

    // ---------------------------------------------------------------- names
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Discord name when linked, else the nickname chosen once. Never an e-mail address. */
    fun displayName(context: Context): String? =
        com.ketvoice.app.discord.Discord.state.value.takeIf { it.linked }?.userName?.let(Names::clean)
            ?: prefs(context).getString("nickname", null)?.let(Names::clean)

    fun discordName(): String? = com.ketvoice.app.discord.Discord.state.value.takeIf { it.linked }?.userName?.let(Names::clean)

    fun saveNickname(context: Context, name: String): Boolean {
        val clean = Names.clean(name) ?: return false
        prefs(context).edit().putString("nickname", clean).apply()
        return true
    }

    fun savedPolicy(context: Context): Policy = prefs(context).let {
        Policy(it.getBoolean("everyoneControls", false), it.getBoolean("everyoneAdds", true),
            Policy.limit(runCatching { it.getInt("limit", Policy.MAX_LIMIT) }.getOrDefault(Policy.MAX_LIMIT)))
    }

    /** Sessions this phone was taken out of (8.4): it does not join them again. Kept while the app runs. */
    private val removedFrom = HashSet<String>()

    // ---------------------------------------------------------------- rooms
    fun create(context: Context, name: String) {
        if (active) return
        codeTries = 0
        open(context.applicationContext, TogetherRole.Host, RoomCode.generate(), name, null)
    }

    fun join(context: Context, code: String, name: String, fingerprint: String? = null) {
        if (active) return
        invitation.value = null
        if (code in removedFrom) { _ui.value = TogetherUi(TogetherPhase.Ended, TogetherRole.Guest, code, endReason = "removed"); return }
        open(context.applicationContext, TogetherRole.Guest, code, name, fingerprint)
    }

    private fun open(app: Context, role: TogetherRole, code: String, name: String, fingerprint: String?) {
        stopTransport()
        context = app
        val me = TogetherWire.memberId()
        val key = if (role == TogetherRole.Host) HostKey.generate() else null
        val policy = if (role == TogetherRole.Host) savedPolicy(app) else Policy()
        val created = TogetherSession(role, me, name, code, port, { payload -> transport?.send(payload) == true },
            { next ->
                _ui.value = next
                if (next.phase == TogetherPhase.Ended) { if (next.endReason == "removed") removedFrom += code; onEnded() }
            }, key, fingerprint, policy,
            shareable = { track -> TrackRef.of(track, com.ketvoice.app.data.ArtworkRepo.covers.value["t:${track.id}"] ?: track.coverUrl) })
        session = created
        _ui.value = created.ui
        if (role == TogetherRole.Guest) {
            val s = Player.state.value
            savedModes = Triple(s.shuffle, s.repeatOne, s.autoplay)
            // A guest's player follows the host's queue: no shuffle, repeat or radio of its own meanwhile.
            Player.adoptModes(false, false, false)
        }
        Player.transportGate = ::gate
        val meta = JSONObject().put("n", name).put("r", if (role == TogetherRole.Host) "h" else "g")
        val t = RealtimeTransport(ENDPOINT, com.ketvoice.app.data.KetvoiceApi.ANON_KEY, RoomCode.topic(code), me, meta,
            object : TransportListener {
                override fun onJoined() { if (session === created) created.joined(now()) }
                override fun onDown(reason: String) { if (session === created) created.down(now()) }
                override fun onRefused(reason: String) {
                    if (session !== created) return
                    android.util.Log.w("Ketogether", "Realtime refused the room: $reason")
                    created.finish("refused", now(), announce = false)
                }
                override fun onBroadcast(payload: JSONObject) { if (session === created) created.receive(payload, now()) }
                override fun onPresence(members: List<PresenceMember>) { if (session === created) created.presence(members, now()) }
            }, { r -> main.post(r) }, client)
        transport = t
        t.start()
        loop?.cancel()
        loop = scope.launch {
            while (isActive && session === created && !created.ended) {
                created.tick(now())
                if (created.collision && role == TogetherRole.Host) {
                    // Someone already hosts this code: another code, before anyone was told this one.
                    if (++codeTries < MAX_CODE_TRIES) { open(app, role, RoomCode.generate(), name, null); return@launch }
                    created.finish("refused", now(), announce = false)
                }
                delay(500)
            }
        }
    }

    /** Leaves the room; a host ends it for everyone. */
    fun leave() {
        val s = session ?: return
        if (!s.ended) s.finish(if (s.role == TogetherRole.Host) "ended" else "left", now(), announce = true)
        onEnded()
    }

    /** Back to the start of the sheet after an end. */
    fun dismiss() {
        if (active) return
        session = null
        _ui.value = TogetherUi()
    }

    private fun onEnded() {
        val t = transport ?: return
        transport = null
        // The host's last message has to leave before the socket closes.
        main.postDelayed({ t.close() }, 300)
        Player.transportGate = null
        savedModes?.let { (shuffle, repeatOne, autoplay) -> Player.adoptModes(shuffle, repeatOne, autoplay) }
        savedModes = null
        loop?.cancel(); loop = null
    }

    private fun stopTransport() {
        transport?.close()
        transport = null
    }

    fun setPolicy(policy: Policy) {
        val s = session ?: return
        context?.let { prefs(it).edit().putBoolean("everyoneControls", policy.everyoneControls).putBoolean("everyoneAdds", policy.everyoneAdds)
            .putInt("limit", Policy.limit(policy.limit)).apply() }
        s.setPolicy(policy, now())
    }

    fun accept(proposal: String) { session?.accept(proposal, now()) }
    /** The host takes a listener out of the session (8.4). */
    fun remove(memberId: String) { session?.remove(memberId, now()) }
    fun refuse(proposal: String) { session?.refuse(proposal, now()) }
    fun skip() { applyingDo { session?.skip() } }
    fun react(emoji: String) { session?.react(emoji, now()) }

    /** « Ajouter à Ketogether » from a title's menu. */
    fun add(track: Track): Boolean = session?.takeIf { !it.ended }?.propose(track, now()) == true

    fun openLink(target: TogetherLink.Target) {
        if (active && _ui.value.code == target.code) { sheet.value = true; return }
        invitation.value = target
        sheet.value = true
    }

    // ---------------------------------------------------------------- player
    private inline fun applyingDo(block: () -> Unit) {
        val before = applying
        applying = true
        try { block() } finally { applying = before }
    }

    /** [Player]'s transport buttons on a guest's phone: see [TogetherSession.transport]. */
    private fun gate(action: String, positionMs: Long): Boolean {
        if (applying) return false
        val s = session ?: return false
        return s.transport(action, positionMs, now())
    }

    private val port = object : PlayerPort {
        override fun view(): PlayerView {
            val s = Player.state.value
            val upcoming = ((s.index + 1) until s.queue.size).mapNotNull { i -> s.queueEntryIds.getOrNull(i)?.let { it to s.queue[i] } }
            return PlayerView(s.current, s.queueEntryIds.getOrNull(s.index), Player.currentPositionMs(), s.playWhenReady, s.isPlaying,
                s.buffering, s.failed, s.durationMs, upcoming)
        }
        override fun load(track: Track, queue: List<Track>, positionMs: Long, playing: Boolean) = applyingDo {
            Player.playTrack(track, queue, 0, positionMs.coerceAtLeast(0), playing, keepOrder = true)
        }
        override fun setPlaying(on: Boolean) = applyingDo { if (Player.state.value.playWhenReady != on) Player.toggle() }
        override fun seek(positionMs: Long) = applyingDo { Player.seekTo(positionMs) }
        override fun next() = applyingDo { Player.next() }
        override fun previous() = applyingDo { Player.previous() }
        override fun enqueue(track: Track): String? = Player.queueWithUndo(track, next = false)
    }
}
