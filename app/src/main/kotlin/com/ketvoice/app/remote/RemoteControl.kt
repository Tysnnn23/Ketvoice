package com.ketvoice.app.remote

import android.content.Context
import com.ketvoice.app.together.PresenceMember
import com.ketvoice.app.together.RealtimeTransport
import com.ketvoice.app.together.TogetherTransport
import com.ketvoice.app.together.TransportListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Where the account's phone-control secret lives on this device: its own preferences file (not the settings,
 * so no backup or export carries it), tied to the account it came from. [SettingsKind] carries it to the
 * account's other devices inside the owner-only synced settings document.
 */
internal object RemoteKeyStore {
    const val KEY = "remoteControlKey"
    private const val PREFS = "ketvoice-remote"
    private val _secret = MutableStateFlow<String?>(null)
    val secret: StateFlow<String?> = _secret

    fun load(context: Context, uid: String?): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString("secret", null)?.takeIf { uid != null && prefs.getString("uid", null) == uid && RemoteKey.valid(it) }
        _secret.value = value
        return value
    }

    /** The secret this device sends with its settings: its own, else the one the account last gave, else a new one. */
    fun ensure(context: Context, uid: String, fromAccount: String?): String =
        load(context, uid) ?: (fromAccount?.takeIf(RemoteKey::valid) ?: RemoteKey.generate()).also { adopt(context, uid, it) }

    /** The account's merged copy wins: two devices that each made one end up on the same. */
    fun adopt(context: Context, uid: String, value: String) {
        if (!RemoteKey.valid(value) || (_secret.value == value && load(context, uid) == value)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("uid", uid).putString("secret", value).commit()
        _secret.value = value
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        _secret.value = null
    }
}

/**
 * Phone control, both ways (like Spotify Connect): each signed-in device joins the account's private Realtime
 * room (Ketogether's transport, a broadcast and presence channel named from [RemoteKey], no table), shows the
 * others, what they play, and drives them. Runs on the thread given to [start] (the UI thread).
 */
object RemoteControl {
    private const val ENDPOINT = "wss://xodklrunqlzyeiqtovgx.supabase.co/realtime/v1/websocket"
    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private var session: RemoteSession? = null
    private var transport: TogetherTransport? = null
    private var ticker: ScheduledExecutorService? = null
    private var joinedSecret: String? = null
    private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }

    /** Host tests replace the socket. */
    internal var transportFactory: (room: String, me: String, meta: JSONObject, TransportListener, (Runnable) -> Unit) -> TogetherTransport =
        { room, me, meta, listener, deliver ->
            RealtimeTransport(ENDPOINT, com.ketvoice.app.data.KetvoiceApi.ANON_KEY, room, me, meta, listener, deliver, client)
        }

    val running: Boolean get() = session != null

    /** Joins the room of [secret] (again only when it changed). [me] is this device's id, [kind] phone, tablet, quest or pc. */
    fun start(secret: String, me: String, name: String, kind: String, port: RemotePort, deliver: (Runnable) -> Unit,
              clock: () -> Long = com.ketvoice.app.account.ServerClock::now) {
        if (session != null && joinedSecret == secret) return
        stop()
        val member = me.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
        if (!RemoteSeal.ID.matches(member)) return
        val created = RemoteSession(name, kind, RemoteSeal(RemoteKey.macKey(secret), member, clock),
            { payload -> transport?.send(payload) == true }, port, clock) { list -> _devices.value = list }
        session = created
        joinedSecret = secret
        val meta = JSONObject().put("n", name.take(24)).put("r", "g")
        val t = transportFactory(RemoteKey.room(secret), member, meta, object : TransportListener {
            override fun onJoined() { if (session === created) { _online.value = true; created.joined() } }
            override fun onDown(reason: String) { if (session === created) { _online.value = false; _devices.value = emptyList() } }
            override fun onRefused(reason: String) { if (session === created) stop() }
            override fun onBroadcast(payload: JSONObject) { if (session === created) created.receive(payload) }
            override fun onPresence(members: List<PresenceMember>) { if (session === created) created.presence(members) }
        }, deliver)
        transport = t
        t.start()
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ketvoice-remote").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ deliver(Runnable { if (session === created) created.tick() }) }, 5, 5, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        transport?.close(); transport = null
        ticker?.shutdownNow(); ticker = null
        session = null; joinedSecret = null
        _online.value = false
        _devices.value = emptyList()
    }

    /** The player changed on this device. */
    fun changed() { session?.localChanged() }

    fun control(device: String, op: RemoteOp): Boolean = session?.control(device, op) == true
    fun sendTo(device: String): Boolean = session?.sendTo(device) == true
    fun takeFrom(device: String): Boolean = session?.takeFrom(device) == true
}
