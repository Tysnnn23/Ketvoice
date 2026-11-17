package com.ketvoice.app.airket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ketvoice.app.handoff.LanBoundary
import java.net.Inet4Address

/** The phone's private Wi-Fi: its network and the subnet Air Ket is allowed to talk to. */
internal data class AirKetWifi(val network: Network, val boundary: LanBoundary)

internal object AirKetNet {
    const val TYPE = "_airket._tcp."

    /** The Wi-Fi network with a private IPv4 address, never a VPN or mobile data; null when there is none. */
    fun wifi(context: Context): AirKetWifi? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        } ?: return null
        val address = cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 8..32 } ?: return null
        return AirKetWifi(network, LanBoundary(address.address, address.prefixLength))
    }
}

/** The sender's announcement on the local network (`_airket._tcp`). */
internal class AirKetAnnouncer(private val context: Context) {
    private var registration: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    fun start(wifi: AirKetWifi, serviceName: String, deviceName: String, port: Int, onFailure: () -> Unit) {
        multicast = context.getSystemService(WifiManager::class.java)?.createMulticastLock("Ketvoice Air Ket")
            ?.apply { setReferenceCounted(false); acquire() }
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName; serviceType = AirKetNet.TYPE; this.port = port
            setAttribute("v", "1"); setAttribute("name", deviceName)
            if (Build.VERSION.SDK_INT >= 33) network = wifi.network
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Handler(Looper.getMainLooper()).post(onFailure) }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }.also { runCatching { context.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    fun stop() {
        registration?.let { runCatching { context.getSystemService(NsdManager::class.java).unregisterService(it) } }
        registration = null
        runCatching { multicast?.release() }; multicast = null
    }
}

/** The receiver's search for senders on the local network. */
internal class AirKetFinder(private val context: Context, private val boundary: LanBoundary,
                            private val onChange: (List<AirKetFound>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val seen = linkedSetOf<String>()
    private var resolving = false
    private var found = emptyList<AirKetFound>()
    @Volatile private var stopped = false

    fun start() {
        multicast = context.getSystemService(WifiManager::class.java)?.createMulticastLock("Ketvoice Air Ket search")
            ?.apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {}
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { if (!stopped && seen.size < 16 && seen.add(info.serviceName)) { queue.addLast(info); next() } } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                seen.remove(info.serviceName); found = found.filterNot { it.serviceName == info.serviceName }; onChange(found)
            } }
        }
        discovery = listener
        runCatching { context.getSystemService(NsdManager::class.java).discoverServices(AirKetNet.TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || stopped) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        val done = { resolving = false; next() }
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { main.post(done) }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post { accept(info); done() } }
        }
        runCatching { context.getSystemService(NsdManager::class.java).resolveService(info, resolver) }.onFailure { resolving = false }
    }

    private fun accept(info: NsdServiceInfo) {
        val host = info.host ?: return
        val name = info.attributes["name"]?.toString(Charsets.UTF_8)?.filterNot { it.isISOControl() }?.take(48).orEmpty()
        if (stopped || info.attributes["v"]?.toString(Charsets.UTF_8) != "1" || name.isBlank() || info.port !in 1024..65535 || !AirKetGuard.accepts(boundary, host)) return
        found = (found.filterNot { it.serviceName == info.serviceName } + AirKetFound(info.serviceName, name, host, info.port)).take(16)
        onChange(found)
    }

    fun stop() {
        stopped = true
        discovery?.let { runCatching { context.getSystemService(NsdManager::class.java).stopServiceDiscovery(it) } }
        discovery = null; queue.clear()
        runCatching { multicast?.release() }; multicast = null
    }
}
