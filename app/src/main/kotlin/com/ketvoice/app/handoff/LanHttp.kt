package com.ketvoice.app.handoff

import org.json.JSONObject
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.CodingErrorAction

object LanHttp {
    data class Request(val method: String, val path: String, val body: JSONObject)
    fun readRequest(input: InputStream): Request {
        var headerBytes = 0
        fun line(): String {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = input.read(); require(n >= 0)
                headerBytes++; require(headerBytes <= 8192)
                if (n == 10) { require(out.size() > 0 && out.toByteArray().last() == 13.toByte()); return out.toString("US-ASCII").dropLast(1) }
                require(n == 13 || n in 32..126)
                out.write(n)
            }
        }
        val first = line().split(' ')
        require(first.size == 3 && first[2] == "HTTP/1.1" && first[0] in setOf("GET", "POST"))
        require(first[1] in setOf("/info", "/offer", "/status", "/start", "/cancel"))
        val headers = mutableMapOf<String, String>()
        while (true) {
            val text = line(); if (text.isEmpty()) break
            val pair = text.split(':', limit = 2); require(pair.size == 2)
            val key = pair[0].lowercase(); require(key !in headers)
            require(key.matches(Regex("[a-z0-9-]+")))
            headers[key] = pair[1].trim()
        }
        require("origin" !in headers && "transfer-encoding" !in headers && "content-encoding" !in headers)
        val length = if ("content-length" in headers) headers.getValue("content-length").toIntOrNull() ?: error("Invalid length")
            else if (first[0] == "GET") 0 else error("Length required")
        require(length in 0..HandoffMessage.MAX_BYTES)
        if (first[0] == "GET") require(first[1] == "/info" && length == 0)
        else require(headers["content-type"]?.substringBefore(';') == "application/json")
        val bytes = ByteArray(length); var at = 0
        while (at < length) { val n = input.read(bytes, at, length - at); require(n > 0); at += n }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = if (length == 0) JSONObject() else JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        return Request(first[0], first[1], body)
    }
    fun respond(socket: Socket, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = if (status in 200..299) "OK" else "Rejected"
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
    fun call(boundary: LanBoundary, peer: InetAddress, port: Int, path: String, body: JSONObject? = null): JSONObject {
        require(boundary.allows(peer) && port in 1024..65535)
        require(path in setOf("/info", "/offer", "/status", "/start", "/cancel"))
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        require(bytes.size <= HandoffMessage.MAX_BYTES)
        return Socket().use { socket ->
            socket.bind(InetSocketAddress(boundary.address, 0))
            socket.connect(InetSocketAddress(peer, port), 3000); socket.soTimeout = 4000
            val method = if (body == null) "GET" else "POST"
            val header = "$method $path HTTP/1.1\r\nHost: ${peer.hostAddress}:$port\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val out=java.io.ByteArrayOutputStream()
                while (true) { val n=input.read(); require(n>=0 && out.size()<8192); if(n==10) return out.toString("US-ASCII").trimEnd('\r'); out.write(n) }
            }
            val first=line().split(' ');require(first.size>=2 && first[0]=="HTTP/1.1")
            val fields=mutableMapOf<String,String>();var total=0
            while(true){val text=line();total+=text.length;require(total<8192);if(text.isEmpty())break
                val pair=text.split(':',limit=2);require(pair.size==2);fields[pair[0].lowercase()]=pair[1].trim()}
            require("transfer-encoding" !in fields)
            val length=fields["content-length"]?.toIntOrNull() ?: error("Invalid response")
            require(length in 0..HandoffMessage.MAX_BYTES)
            val result=ByteArray(length);var at=0
            while(at<length){val n=input.read(result,at,length-at);require(n>0);at+=n}
            require(first[1].toInt() in 200..299) { "Transfert local refusé" }
            JSONObject(String(result,Charsets.UTF_8))
        }
    }
}
