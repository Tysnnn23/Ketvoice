package com.ketvoice.app.handoff

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ketvoice.app.player.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

data class HandoffPeer(val id: String, val serviceName: String, val name: String, val address: InetAddress, val port: Int)
data class HandoffStatus(val listening: Boolean = false, val peers: List<HandoffPeer> = emptyList(),
    val message: String = "Transfert Wi-Fi désactivé", val sending: Boolean = false)

/** Opt-in, foreground-only mDNS + direct LAN transfer. No cloud, QR or auth tokens. */
object WifiHandoff {
    private const val TYPE="_ketvoice._tcp."
    private val main=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val _status=MutableStateFlow(HandoffStatus())
    val status: StateFlow<HandoffStatus> = _status
    private val _pending=MutableStateFlow<IncomingHandoff?>(null)
    val pending: StateFlow<IncomingHandoff?> = _pending
    private val instance=UUID.randomUUID().toString()
    private val name="${if(com.ketvoice.app.BuildConfig.IS_QUEST) "Quest" else "Android"} · ${instance.take(4)}"
    private var context:Context?=null
    private var callback:ConnectivityManager.NetworkCallback?=null
    private var boundary:LanBoundary?=null
    private var networkKey:String?=null
    private var server:LanTransferServer?=null
    private var multicast:WifiManager.MulticastLock?=null
    private var registration:NsdManager.RegistrationListener?=null
    private var discovery:NsdManager.DiscoveryListener?=null
    private val found=linkedSetOf<String>()
    private val resolvingQueue=ArrayDeque<NsdServiceInfo>()
    private var resolving=false
    private var generation=0
    private var expiryJob:Job?=null
    private var sendJob:Job?=null

    fun start(app:Context) {
        if(context!=null)return
        context=app.applicationContext
        val cm=app.getSystemService(ConnectivityManager::class.java)
        val cb=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network){main.post{refresh()}}
            override fun onLost(network:Network){main.post{refresh()}}
            override fun onLinkPropertiesChanged(network:Network, properties:android.net.LinkProperties){main.post{refresh()}}
        }
        callback=cb
        runCatching{cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),cb)}
        refresh()
    }
    private fun refresh() {
        val app=context ?:return
        val cm=app.getSystemService(ConnectivityManager::class.java)
        val wifi=cm.allNetworks.firstOrNull{cm.getNetworkCapabilities(it)?.let { c ->
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }==true}
        val link=wifi?.let(cm::getLinkProperties)
        val address=link?.linkAddresses?.firstOrNull{it.address is Inet4Address&&it.address.isSiteLocalAddress&&it.prefixLength in 8..32}
        if(wifi==null||address==null){stopServices();_status.value=HandoffStatus(message="Connectez les deux appareils au même Wi-Fi privé IPv4");return}
        val key="${wifi}:${address}"
        if(key==networkKey&&server!=null)return
        stopServices();networkKey=key
        val lan=LanBoundary(address.address,address.prefixLength);boundary=lan
        val epoch=generation
        try {
            val receiver=LanTransferServer(lan,name,{offer -> main.post { if(epoch==generation)_pending.value=offer } },
                {offer,position,playing -> scope.launch {
                    if(epoch!=generation)return@launch
                    val ready=runCatching {
                        val message=offer.message
                        Player.playTrack(message.current,message.queue,message.index,position,playing)
                        Player.setShuffle(message.shuffle);Player.setRepeatOne(message.repeatOne);Player.setAutoplay(message.autoplay)
                        Player.awaitReady(message.current.id)
                    }.getOrDefault(false)
                    if(epoch==generation){
                        server?.complete(offer.id,ready)
                        _status.value=_status.value.copy(message=if(ready)"Lecture reçue" else "Impossible de reprendre ce titre")
                    }
                }})
            server=receiver
            val wm=app.getSystemService(WifiManager::class.java)
            multicast=wm.createMulticastLock("Ketvoice local discovery").apply{setReferenceCounted(false);acquire()}
            _status.value=HandoffStatus(listening=true,message="Appareils sur le même Wi-Fi · confirmation obligatoire")
            val nsd=app.getSystemService(NsdManager::class.java)
            val info=NsdServiceInfo().apply{
                serviceName="Ketvoice-${instance.take(8)}";serviceType=TYPE;port=receiver.port
                setAttribute("id",instance);setAttribute("name",name);setAttribute("version","1")
                if(Build.VERSION.SDK_INT>=33) network=wifi
            }
            registration=object:NsdManager.RegistrationListener{
                override fun onServiceRegistered(info:NsdServiceInfo){}
                override fun onServiceUnregistered(info:NsdServiceInfo){}
                override fun onRegistrationFailed(info:NsdServiceInfo,error:Int){main.post{if(epoch==generation)_status.value=_status.value.copy(message="Annonce locale indisponible sur ce réseau")}}
                override fun onUnregistrationFailed(info:NsdServiceInfo,error:Int){}
            }.also{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,it)}
            discovery=object:NsdManager.DiscoveryListener{
                override fun onDiscoveryStarted(type:String){}
                override fun onDiscoveryStopped(type:String){}
                override fun onStartDiscoveryFailed(type:String,error:Int){main.post{if(epoch==generation)_status.value=_status.value.copy(message="Découverte indisponible · vérifiez les autorisations réseau")}}
                override fun onStopDiscoveryFailed(type:String,error:Int){}
                override fun onServiceFound(info:NsdServiceInfo){main.post{
                    if(epoch==generation&&found.size<16&&found.add(info.serviceName)){resolvingQueue.addLast(info);resolveNext(epoch)}
                }}
                override fun onServiceLost(info:NsdServiceInfo){main.post{
                    if(epoch==generation){found.remove(info.serviceName);_status.value=_status.value.copy(peers=_status.value.peers.filterNot{it.serviceName==info.serviceName})}
                }}
            }.also{nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,it)}
            expiryJob=scope.launch{while(isActive){receiver.tick();delay(1000)}}
        }catch(_:Exception){stopServices();_status.value=HandoffStatus(message="Transfert local indisponible sur ce réseau")}
    }
    @Suppress("DEPRECATION")
    private fun resolveNext(epoch:Int) {
        if(resolving||epoch!=generation)return
        val info=resolvingQueue.removeFirstOrNull() ?:return
        val nsd=context?.getSystemService(NsdManager::class.java) ?:return
        resolving=true
        val listener=object:NsdManager.ResolveListener{
            override fun onResolveFailed(info:NsdServiceInfo,error:Int){main.post{if(epoch==generation){resolving=false;resolveNext(epoch)}}}
            override fun onServiceResolved(info:NsdServiceInfo){main.post{
                if(epoch!=generation)return@post
                resolving=false
                val id=info.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
                val version=info.attributes["version"]?.toString(Charsets.UTF_8)
                val host=info.host
                val peerName=runCatching{HandoffMessage.clean(info.attributes["name"]?.toString(Charsets.UTF_8).orEmpty(),64)}.getOrDefault("")
                if(id!=instance&&id.length in 1..64&&version=="1"&&host!=null&&boundary?.allows(host)==true&&
                    info.port in 1024..65535&&info.serviceName in found&&peerName.isNotBlank()) {
                    val peer=HandoffPeer(id,info.serviceName,peerName,host,info.port)
                    _status.value=_status.value.copy(peers=(_status.value.peers.filterNot{it.id==id}+peer).take(16))
                }
                resolveNext(epoch)
            }}
        }
        runCatching{nsd.resolveService(info,listener)}.onFailure{resolving=false;resolveNext(epoch)}
    }
    fun approve(id:String, accepted:Boolean){server?.approve(id,accepted)}
    fun send(peer:HandoffPeer) {
        if(sendJob?.isActive==true)return
        val lan=boundary ?:return
        val state=Player.state.value
        val current=state.current ?:return
        if(!HandoffMessage.compatible(current)){
            _status.value=_status.value.copy(message="Seuls les titres du catalogue en ligne peuvent être transférés");return
        }
        val selected=state.queue.mapIndexedNotNull{i,t->if(HandoffMessage.compatible(t))i to t else null}
        val originalCurrent=selected.indexOfFirst{it.first==state.index}
        val start=(originalCurrent-20).coerceAtLeast(0)
        val queue=selected.drop(start).take(HandoffMessage.MAX_QUEUE)
        val index=queue.indexOfFirst{it.first==state.index}
        if(index<0)return
        val message=HandoffMessage(name,queue.map{it.second},index,state.shuffle,state.repeatOne,state.autoplay)
        val epoch=Player.playbackEpoch
        val netEpoch=generation
        sendJob=scope.launch{
            _status.value=_status.value.copy(sending=true,message="En attente de confirmation sur l'autre appareil…")
            var receipt:JSONObject?=null
            try {
                withTimeout(95_000){
                    val hello=withContext(Dispatchers.IO){LanHttp.call(lan,peer.address,peer.port,"/info")}
                    require(hello.getInt("version")==1)
                    receipt=withContext(Dispatchers.IO){LanHttp.call(lan,peer.address,peer.port,"/offer",JSONObject().put("nonce",hello.getString("nonce")).put("message",message.json()))}
                    val auth=JSONObject().put("id",receipt!!.getString("id")).put("receipt",receipt!!.getString("receipt"))
                    var started=false
                    while(isActive){
                        val reply=withContext(Dispatchers.IO){LanHttp.call(lan,peer.address,peer.port,"/status",auth)}
                        when(reply.getString("state")){
                            "approved" -> if(!started){
                                require(epoch==Player.playbackEpoch)
                                val startBody=JSONObject(auth.toString()).put("positionMs",Player.currentPositionMs().coerceIn(0,86_400_000)).put("playing",Player.state.value.playWhenReady)
                                withContext(Dispatchers.IO){LanHttp.call(lan,peer.address,peer.port,"/start",startBody)}
                                started=true;_status.value=_status.value.copy(message="Reprise en cours sur l'autre appareil…")
                            }
                            "ready" -> {
                                if(epoch==Player.playbackEpoch)Player.pause()
                                _status.value=_status.value.copy(message="Lecture transférée",sending=false)
                                return@withTimeout
                            }
                            "rejected","failed","cancelled" -> error("Rejected")
                        }
                        delay(700)
                    }
                }
            }catch(_:Exception){
                receipt?.let{r->withContext(NonCancellable+Dispatchers.IO){runCatching{LanHttp.call(lan,peer.address,peer.port,"/cancel",r)}}}
                if(netEpoch==generation)_status.value=_status.value.copy(message="Transfert interrompu ou refusé · la lecture locale est conservée",sending=false)
            }finally{if(netEpoch==generation)_status.value=_status.value.copy(sending=false)}
        }
    }
    private fun stopServices() {
        generation++;networkKey=null;boundary=null
        expiryJob?.cancel();expiryJob=null;sendJob?.cancel();sendJob=null
        server?.close();server=null;_pending.value=null
        val nsd=context?.getSystemService(NsdManager::class.java)
        registration?.let{runCatching{nsd?.unregisterService(it)}};registration=null
        discovery?.let{runCatching{nsd?.stopServiceDiscovery(it)}};discovery=null
        runCatching{multicast?.release()};multicast=null
        found.clear();resolvingQueue.clear();resolving=false
    }
    fun stop() {
        val app=context ?:return
        stopServices()
        callback?.let{runCatching{app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)}}
        callback=null;context=null;_status.value=HandoffStatus()
    }
}
