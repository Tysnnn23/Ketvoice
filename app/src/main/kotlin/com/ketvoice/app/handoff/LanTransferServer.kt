package com.ketvoice.app.handoff

import org.json.JSONObject
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class IncomingHandoff(val id: String, val message: HandoffMessage, val address: String = "")

class LanTransferServer(val boundary: LanBoundary, private val name: String,
    private val onPending: (IncomingHandoff?) -> Unit,
    private val onStart: (IncomingHandoff, Long, Boolean) -> Unit) : AutoCloseable {
    private data class Session(val offer: IncomingHandoff, val receipt: String, var expires: Long, var state: String = "pending")
    private val server=ServerSocket().apply { reuseAddress=true; bind(InetSocketAddress(boundary.address,0),4) }
    val port: Int get() = server.localPort
    private val executor=Executors.newFixedThreadPool(4)
    private val slots=Semaphore(4)
    private val nonces=linkedMapOf<String,Long>()
    private var session: Session?=null
    private var lastOffer:Long?=null
    @Volatile private var closed=false
    private fun now()=System.nanoTime()/1_000_000
    private fun random()=java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID().toString()
    private fun matches(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    init {
        Thread({
            while(!closed) try {
                val socket=server.accept();socket.soTimeout=3000
                if(!boundary.allows(socket.inetAddress)||!slots.tryAcquire()){socket.close();continue}
                try { executor.execute { socket.use {
                    try { val request=LanHttp.readRequest(it.getInputStream());val reply=handle(request,it.inetAddress.hostAddress.orEmpty());LanHttp.respond(it,200,reply) }
                    catch(_:Exception){runCatching{LanHttp.respond(it,400,JSONObject().put("error","Rejected"))}}
                    finally { slots.release() }
                } } } catch (_:Exception) { socket.close();slots.release() }
            } catch(_:Exception) { if(closed)break }
        },"ketvoice-lan-accept").apply { isDaemon=true;start() }
    }
    @Synchronized private fun expire() {
        nonces.entries.removeAll { now()>it.value }
        session?.takeIf { now()>it.expires }?.let { session=null;onPending(null) }
    }
    @Synchronized private fun handle(request:LanHttp.Request, peer:String):JSONObject {
        require(!closed);expire()
        if(request.path=="/info") {
            require(request.method=="GET")
            while(nonces.size>=8)nonces.remove(nonces.keys.first())
            val nonce=random();nonces[nonce]=now()+30_000
            return JSONObject().put("version",1).put("name",name).put("nonce",nonce)
        }
        require(request.method=="POST")
        val body=request.body
        if(request.path=="/offer") {
            val nonce=body.getString("nonce")
            require(nonces.remove(nonce)!=null && lastOffer?.let { now()-it>3000 } != false)
            require(session==null || session!!.state in setOf("ready","rejected","failed","cancelled"))
            val message=HandoffMessage.decode(body.getJSONObject("message"))
            val offer=IncomingHandoff(random(),message,peer)
            val s=Session(offer,random(),now()+30_000);session=s;lastOffer=now();onPending(offer)
            return JSONObject().put("id",offer.id).put("receipt",s.receipt).put("state",s.state)
        }
        val s=session ?: error("Expired")
        require(body.optString("id").length<=100 && body.optString("receipt").length<=100)
        require(matches(body.getString("id"),s.offer.id)&&matches(body.getString("receipt"),s.receipt))
        if(request.path=="/cancel") { s.state="cancelled";onPending(null) }
        if(request.path=="/start") {
            require(s.state=="approved")
            val position=body.getLong("positionMs");require(position in 0..86_400_000)
            s.state="starting";s.expires=now()+50_000;onPending(null)
            onStart(s.offer,position,body.getBoolean("playing"))
        }
        return JSONObject().put("state",s.state)
    }
    @Synchronized fun approve(id:String, accepted:Boolean) {
        expire();val s=session ?: return
        if(s.offer.id!=id || s.state!="pending")return
        s.state=if(accepted)"approved" else "rejected";s.expires=now()+50_000;onPending(null)
    }
    @Synchronized fun complete(id:String, ready:Boolean) {
        val s=session ?:return
        if(s.offer.id==id && s.state=="starting") { s.state=if(ready)"ready" else "failed";s.expires=now()+30_000 }
    }
    @Synchronized fun tick()=expire()
    override fun close() {
        closed=true;runCatching{server.close()};executor.shutdownNow()
        synchronized(this){nonces.clear();session=null;onPending(null)}
    }
}
