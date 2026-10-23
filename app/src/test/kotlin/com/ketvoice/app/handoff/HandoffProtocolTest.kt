package com.ketvoice.app.handoff

import com.ketvoice.app.data.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class HandoffProtocolTest {
    private fun fixture()=HandoffMessage("Test phone",listOf(Track("yt:abcdefghijk","Owned QA tone","QA","Test",120000,
        streamUrl="https://example.invalid/private-token",downloadUrl="https://example.invalid/cookie")),0,false,false,true)
    private fun rejected(block:()->Unit){assertTrue(runCatching(block).isFailure)}
    @Test fun credentialsUrlsAndSourceConfigurationAreNeverSerialized() {
        val text=fixture().json().toString()
        assertFalse(text.contains("private-token"));assertFalse(text.contains("cookie"));assertFalse(text.contains("Url"))
        val result=HandoffMessage.decode(JSONObject(text))
        assertNull(result.current.streamUrl);assertNull(result.current.videoId)
        assertFalse(result.current.youtubeCatalogVerified)
    }
    @Test fun unknownFieldsCannotSmuggleAnUrlOrAnAuthSession() {
        val a=fixture().json().put("access_token","fake");rejected{HandoffMessage.decode(a)}
        val b=fixture().json();b.getJSONArray("queue").getJSONObject(0).put("streamUrl","file:///private");rejected{HandoffMessage.decode(b)}
    }
    @Test fun localAndCustomSourcesAreNotExported() {
        assertFalse(HandoffMessage.compatible(Track("local:1","x","y","")))
        assertFalse(HandoffMessage.compatible(Track("cs:personal:1","x","y","")))
        assertTrue(HandoffMessage.compatible(Track("kv:um:12345","x","y","")))
    }
    @Test fun queueIndexAndCountAreValidated() {
        rejected{HandoffMessage.decode(fixture().json().put("index",8))}
        val a=fixture().json();val rows=a.getJSONArray("queue");repeat(50){rows.put(rows.getJSONObject(0))}
        rejected{HandoffMessage.decode(a)}
    }
    @Test fun wifiBoundaryRejectsPublicIpsOtherSubnetsAndIpv6() {
        val b=LanBoundary(InetAddress.getByName("192.168.1.10"),24)
        assertTrue(b.allows(InetAddress.getByName("192.168.1.20")))
        assertFalse(b.allows(InetAddress.getByName("192.168.2.20")))
        assertFalse(b.allows(InetAddress.getByName("8.8.8.8")))
        assertFalse(b.allows(InetAddress.getByName("::1")))
    }
    @Test fun browserOriginsChunkingDuplicateHeadersAndOversizedBodiesAreRejected() {
        listOf("Origin: https://evil.invalid\r\n", "Transfer-Encoding: chunked\r\n",
            "Content-Length: 1\r\n", "Content-Encoding: gzip\r\n").forEach{extra ->
            val request="POST /offer HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\n${extra}\r\n{}"
            rejected{LanHttp.readRequest(request.byteInputStream())}
        }
        rejected{LanHttp.readRequest("POST /offer HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n".byteInputStream())}
        rejected{LanHttp.readRequest("GET /info HTTP/1.1\r\nContent-Length: abc\r\n\r\n".byteInputStream())}
    }
    @Test fun remoteTextControlsAreRemovedButUnicodeNamesAreKept() {
        assertEquals("Titre 日本語",HandoffMessage.clean("Titre \u202e日本語\n"))
    }
    @Test fun socketRoundTripRequiresApprovalThenARealReadyAck() {
        val b=LanBoundary(InetAddress.getByName("127.0.0.1"),8)
        var pending:IncomingHandoff?=null
        val starts=AtomicInteger(0)
        LanTransferServer(b,"Receiver",{pending=it},{_,position,playing->
            assertEquals(12345L,position);assertTrue(playing);starts.incrementAndGet()
        }).use{server ->
            fun call(path:String,body:JSONObject?=null)=LanHttp.call(b,b.address,server.port,path,body)
            val hello=call("/info")
            val offer=call("/offer",JSONObject().put("nonce",hello.getString("nonce")).put("message",fixture().json()))
            val auth=JSONObject().put("id",offer.getString("id")).put("receipt",offer.getString("receipt"))
            val start=JSONObject(auth.toString()).put("positionMs",12345).put("playing",true)
            rejected{call("/start",start)}
            assertEquals(0,starts.get());assertNotNull(pending)
            server.approve(offer.getString("id"),true)
            assertEquals("approved",call("/status",auth).getString("state"))
            call("/start",start);assertEquals(1,starts.get())
            assertEquals("starting",call("/status",auth).getString("state"))
            rejected{call("/start",start)} // one-use start, not an unattended remote control
            server.complete(offer.getString("id"),true)
            assertEquals("ready",call("/status",auth).getString("state"))
            rejected{call("/status",JSONObject(auth.toString()).put("receipt","wrong"))}
        }
    }
    @Test fun rejectionAndNonceReplayCannotStartPlayback() {
        val b=LanBoundary(InetAddress.getByName("127.0.0.1"),8)
        val starts=AtomicInteger(0)
        LanTransferServer(b,"Receiver",{},{_,_,_->starts.incrementAndGet()}).use{server ->
            fun call(path:String,body:JSONObject?=null)=LanHttp.call(b,b.address,server.port,path,body)
            val nonce=call("/info").getString("nonce")
            val body=JSONObject().put("nonce",nonce).put("message",fixture().json())
            val offer=call("/offer",body);rejected{call("/offer",body)}
            val id=offer.getString("id");server.approve(id,false)
            val auth=JSONObject().put("id",id).put("receipt",offer.getString("receipt"))
            assertEquals("rejected",call("/status",auth).getString("state"))
            rejected{call("/start",auth.put("positionMs",0).put("playing",true))}
            assertEquals(0,starts.get())
        }
    }
}
