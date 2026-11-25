package com.ketvoice.desktop.social

import android.content.Context
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.together.HostKey
import com.ketvoice.app.together.Names
import com.ketvoice.app.together.PlayerPort
import com.ketvoice.app.together.PlayerView
import com.ketvoice.app.together.Policy
import com.ketvoice.app.together.PresenceMember
import com.ketvoice.app.together.RealtimeTransport
import com.ketvoice.app.together.RoomCode
import com.ketvoice.app.together.TogetherLink
import com.ketvoice.app.together.TogetherPhase
import com.ketvoice.app.together.TogetherRole
import com.ketvoice.app.together.TogetherSession
import com.ketvoice.app.together.TogetherTransport
import com.ketvoice.app.together.TogetherUi
import com.ketvoice.app.together.TogetherWire
import com.ketvoice.app.together.TrackRef
import com.ketvoice.app.together.TransportListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Ketogether on Windows (2.0): the phone's listening rooms, with the same protocol and the same session
 * ([TogetherSession], [RealtimeTransport], signed host state, drift correction), so phones and PCs share a room.
 * This object only binds the session to the desktop [Player] and runs it on the window's thread, as the phone's
 * `Ketogether` does on its main thread. A guest's Ketmix waits ([com.ketvoice.desktop.ketmix.DesktopAutomix.held]):
 * the host's music decides the transitions.
 */
object DesktopTogether {
    private const val ENDPOINT = "wss://xodklrunqlzyeiqtovgx.supabase.co/realtime/v1/websocket"
    private const val PREFS = "ketogether"
    private const val MAX_CODE_TRIES = 3

    private val _ui = MutableStateFlow(TogetherUi())
    val ui: StateFlow<TogetherUi> = _ui
    /** A link asked to join this room; the « Ensemble » tab asks before joining. */
    val invitation = MutableStateFlow<TogetherLink.Target?>(null)

    /** The room's transport; tests give an in-memory one. */
    internal var transports: (topic: String, me: String, meta: JSONObject, listener: TransportListener) -> TogetherTransport =
        { topic, me, meta, listener ->
            RealtimeTransport(ENDPOINT, com.ketvoice.app.data.KetvoiceApi.ANON_KEY, topic, me, meta, listener, { r -> post(r) }, client)
        }
    /** Where the session runs: the window's thread (tests run it in place). */
    internal var post: (Runnable) -> Unit = { r -> javax.swing.SwingUtilities.invokeLater(r) }
    /** A monotonic clock in ms. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    /** The player the room drives; tests give a fake. */
    internal var port: PlayerPort = DesktopPort

    private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    private var session: TogetherSession? = null
    private var transport: TogetherTransport? = null
    private var codeTries = 0
    private var savedModes: List<Boolean>? = null
    private val removedFrom = HashSet<String>()

    val active: Boolean get() = session?.ended == false
    val guest: Boolean get() = session?.let { !it.ended && it.role == TogetherRole.Guest } == true

    /** The name shown in a room: the profile's name, else the computer's. Never an e-mail address. */
    fun displayName(): String = Names.clean(com.ketvoice.app.account.Profiles.profile.value.displayName)
        ?: Names.clean(com.ketvoice.desktop.remote.DesktopRemote.computerName()) ?: "PC"

    private fun prefs() = runCatching { Context.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    fun savedPolicy(): Policy = prefs()?.let {
        Policy(it.getBoolean("everyoneControls", false), it.getBoolean("everyoneAdds", true), Policy.limit(it.getInt("limit", Policy.MAX_LIMIT)))
    } ?: Policy()

    /** Starts the twice-a-second beat of the session (the host's ticks, a guest's drift checks). */
    fun start(scope: CoroutineScope) {
        scope.launch { while (isActive) { delay(500); post(Runnable { tick() }) } }
    }

    internal fun tick() {
        val s = session ?: return
        if (s.ended) return
        s.tick(clock())
        if (s.collision && s.role == TogetherRole.Host) {
            // Someone already hosts this code: another code, before anyone was told this one.
            if (++codeTries < MAX_CODE_TRIES) open(TogetherRole.Host, RoomCode.generate(), myName, null)
            else s.finish("refused", clock(), announce = false)
        }
    }

    private var myName = ""

    fun create(name: String = displayName()) {
        if (active) return
        codeTries = 0
        open(TogetherRole.Host, RoomCode.generate(), name, null)
    }

    /** Joins by a typed code (« ABC 234 ») or a pasted link; false when it is neither. */
    fun joinTyped(text: String, name: String = displayName()): Boolean {
        val target = com.ketvoice.app.social.ShareUrls.parse(text)?.let { (it as? com.ketvoice.app.social.ShareUrls.Link.Room)?.target }
            ?: RoomCode.normalize(text)?.let { TogetherLink.Target(it, null) } ?: return false
        join(target.code, name, target.fingerprint)
        return true
    }

    fun join(code: String, name: String = displayName(), fingerprint: String? = null) {
        if (active) return
        invitation.value = null
        if (code in removedFrom) { _ui.value = TogetherUi(TogetherPhase.Ended, TogetherRole.Guest, code, endReason = "removed"); return }
        open(TogetherRole.Guest, code, name, fingerprint)
    }

    private fun open(role: TogetherRole, code: String, name: String, fingerprint: String?) {
        transport?.close(); transport = null
        myName = name
        com.ketvoice.desktop.ketmix.DesktopAutomix.held = { guest }
        val me = TogetherWire.memberId()
        val key = if (role == TogetherRole.Host) HostKey.generate() else null
        val created = TogetherSession(role, me, name, code, gated, { payload -> transport?.send(payload) == true },
            { next -> _ui.value = next; if (next.phase == TogetherPhase.Ended) { if (next.endReason == "removed") removedFrom += code; ended() } },
            key, fingerprint, if (role == TogetherRole.Host) savedPolicy() else Policy(),
            shareable = { track -> TrackRef.of(track) })
        session = created
        _ui.value = created.ui
        if (role == TogetherRole.Guest && savedModes == null) {
            val s = Player.state.value
            savedModes = listOf(s.shuffle, s.repeatOne, s.repeatAll, s.autoplay)
            // A guest's player follows the host's queue: no shuffle, repeat or radio of its own meanwhile.
            runCatching { Player.setShuffle(false); Player.setRepeat(false, false); Player.setAutoplay(false) }
        }
        if (port === DesktopPort) Player.transportGate = ::gate
        val meta = JSONObject().put("n", name).put("r", if (role == TogetherRole.Host) "h" else "g")
        val t = transports(RoomCode.topic(code), me, meta, object : TransportListener {
            override fun onJoined() { if (session === created) created.joined(clock()) }
            override fun onDown(reason: String) { if (session === created) created.down(clock()) }
            override fun onRefused(reason: String) { if (session === created) created.finish("refused", clock(), announce = false) }
            override fun onBroadcast(payload: JSONObject) { if (session === created) created.receive(payload, clock()) }
            override fun onPresence(members: List<PresenceMember>) { if (session === created) created.presence(members, clock()) }
        })
        transport = t
        t.start()
    }

    /** Leaves the room; a host ends it for everyone. */
    fun leave() {
        val s = session ?: return
        if (!s.ended) s.finish(if (s.role == TogetherRole.Host) "ended" else "left", clock(), announce = true)
        ended()
    }

    /** Back to the start of the tab after an end. */
    fun dismiss() {
        if (active) return
        session = null
        _ui.value = TogetherUi()
    }

    private fun ended() {
        val t = transport
        transport = null
        // The host's last message has to leave before the socket closes.
        if (t != null) Thread({ Thread.sleep(300); t.close() }, "ketogether-close").apply { isDaemon = true }.start()
        if (port === DesktopPort) Player.transportGate = null
        savedModes?.let { (shuffle, repeatOne, repeatAll, autoplay) ->
            runCatching { Player.setShuffle(shuffle); Player.setRepeat(repeatOne, repeatAll); Player.setAutoplay(autoplay) }
        }
        savedModes = null
    }

    fun setPolicy(policy: Policy) {
        prefs()?.edit()?.putBoolean("everyoneControls", policy.everyoneControls)?.putBoolean("everyoneAdds", policy.everyoneAdds)
            ?.putInt("limit", Policy.limit(policy.limit))?.apply()
        session?.setPolicy(policy, clock())
    }

    fun accept(proposal: String) { session?.accept(proposal, clock()) }
    fun refuse(proposal: String) { session?.refuse(proposal, clock()) }
    /** The host takes a listener out of the room (8.4). */
    fun remove(memberId: String) { session?.remove(memberId, clock()) }
    fun react(emoji: String) { session?.react(emoji, clock()) }

    /** « Ajouter à Ensemble » from a title's menu: the host adds it, a guest proposes it. */
    fun add(track: Track): Boolean = session?.takeIf { !it.ended }?.propose(track, clock()) == true

    /** A link asked for this room: open the tab on it (joining is the listener's choice). */
    fun openLink(target: TogetherLink.Target) {
        if (active && _ui.value.code == target.code) return
        invitation.value = target
    }

    /** The room as « Activité des amis » shares it: only a room with a known host key can be joined from a friend. */
    fun roomWire(): String? = _ui.value.takeIf { it.active }?.let { com.ketvoice.app.social.FriendsNow.roomWire(it.code, it.fingerprint) }

    // ------------------------------------------------------------------- player
    private var applying = false

    private inline fun applyingDo(block: () -> Unit) {
        val before = applying
        applying = true
        try { block() } finally { applying = before }
    }

    /** The player's transport on a guest's PC: see [TogetherSession.transport]. */
    private fun gate(action: String, positionMs: Long): Boolean {
        if (applying) return false
        val s = session ?: return false
        return s.transport(action, positionMs, clock())
    }

    /** The session's own calls pass the gate. */
    private val gated = object : PlayerPort {
        override fun view(): PlayerView = port.view()
        override fun load(track: Track, queue: List<Track>, positionMs: Long, playing: Boolean) = applyingDo { port.load(track, queue, positionMs, playing) }
        override fun setPlaying(on: Boolean) = applyingDo { port.setPlaying(on) }
        override fun seek(positionMs: Long) = applyingDo { port.seek(positionMs) }
        override fun next() = applyingDo { port.next() }
        override fun previous() = applyingDo { port.previous() }
        override fun enqueue(track: Track): String? = port.enqueue(track)
    }

    /** For tests: back to no room, nothing remembered. */
    internal fun reset() {
        transport?.close(); transport = null; session = null; savedModes = null; removedFrom.clear()
        _ui.value = TogetherUi(); invitation.value = null; Player.transportGate = null
    }
}

/** The desktop [Player] as a room sees it. */
internal object DesktopPort : PlayerPort {
    override fun view(): PlayerView {
        val s = Player.state.value
        val loading = Player.loading.value
        val upcoming = ((s.index + 1) until s.queue.size).mapNotNull { i -> s.queueEntryIds.getOrNull(i)?.let { it to s.queue[i] } }
        return PlayerView(s.current, s.queueEntryIds.getOrNull(s.index), Player.positionNow(), s.playWhenReady, s.isPlaying && !loading,
            loading && s.playWhenReady, false, s.durationMs.takeIf { it > 0 } ?: (s.current?.durationMs ?: 0L), upcoming)
    }

    override fun load(track: Track, queue: List<Track>, positionMs: Long, playing: Boolean) =
        Player.resumeFrom(queue.ifEmpty { listOf(track) }, 0, positionMs.coerceAtLeast(0L), playing)

    override fun setPlaying(on: Boolean) { if (on) Player.play() else Player.pause() }
    override fun seek(positionMs: Long) = Player.seekTo(positionMs)
    override fun next() = Player.next()
    override fun previous() = Player.previous()

    override fun enqueue(track: Track): String? {
        val before = Player.state.value.queueEntryIds.toSet()
        Player.enqueue(track)
        return Player.state.value.queueEntryIds.firstOrNull { it !in before }
    }
}
