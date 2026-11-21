package com.ketvoice.app.perf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Function

/**
 * 8.1 perf81load: a fake network for host timing tests. Every http(s) request of the app's own code (HttpURLConnection,
 * the way the app talks to its services) is answered by [route] after a realistic delay, and logged; a request no
 * route answers fails at once, as on a dead network. While no test uses it, requests go to the JDK's own handlers.
 */
object FakeNet {
    class Answer(val delayMs: Long, val body: String, val code: Int = 200)
    class Call(val url: String, val method: String, val body: String, val atMs: Long)

    @Volatile var route: ((url: String, method: String, body: String) -> Answer?)? = null
    val calls = ConcurrentLinkedQueue<Call>()
    @Volatile private var epoch = System.nanoTime()

    fun reset(r: (url: String, method: String, body: String) -> Answer?) { calls.clear(); epoch = System.nanoTime(); route = r; install() }
    fun off() { route = null }
    fun count(part: String) = calls.count { part in it.url }
    private fun nowMs() = (System.nanoTime() - epoch) / 1_000_000

    private const val KEY = "ketvoice.perf81.fakeNet"

    /** Installed once per JVM; the router lives in the system properties so that every Robolectric sandbox finds it. */
    @Synchronized private fun install() {
        @Suppress("UNCHECKED_CAST")
        System.getProperties()[KEY] = Function<URL, URLConnection?> { u -> open(u) }
        if (System.getProperties()["$KEY.installed"] == true) return
        val defaults = mapOf("http" to URL("http://example.invalid/"), "https" to URL("https://example.invalid/"))
        URL.setURLStreamHandlerFactory { protocol ->
            val base = defaults[protocol] ?: return@setURLStreamHandlerFactory null
            object : URLStreamHandler() {
                @Suppress("UNCHECKED_CAST")
                private fun fake(u: URL) = (System.getProperties()[KEY] as? Function<URL, URLConnection?>)?.apply(u)
                override fun openConnection(u: URL): URLConnection = fake(u) ?: URL(base, u.toExternalForm()).openConnection()
                override fun openConnection(u: URL, p: Proxy): URLConnection = fake(u) ?: URL(base, u.toExternalForm()).openConnection(p)
            }
        }
        System.getProperties()["$KEY.installed"] = true
    }

    private fun open(u: URL): URLConnection? = if (route == null) null else Connection(u)

    private class Connection(u: URL) : HttpURLConnection(u) {
        private val sent = ByteArrayOutputStream()
        private var answer: Answer? = null
        override fun connect() { connected = true }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        private fun answer(): Answer {
            answer?.let { return it }
            val body = sent.toString(Charsets.UTF_8.name())
            calls += Call(url.toString(), method, body, nowMs())
            val a = route?.invoke(url.toString(), method, body) ?: throw IOException("offline: $url")
            if (a.delayMs > 0) try { Thread.sleep(a.delayMs) } catch (_: InterruptedException) { throw IOException("cancelled") }
            answer = a; responseCode = a.code
            return a
        }
        override fun getResponseCode(): Int = answer().code
        override fun getInputStream(): InputStream {
            val a = answer(); if (a.code !in 200..299) throw IOException("HTTP ${a.code}")
            return ByteArrayInputStream(a.body.toByteArray())
        }
        override fun getErrorStream(): InputStream? = answer?.takeIf { it.code !in 200..299 }?.let { ByteArrayInputStream(it.body.toByteArray()) }
        override fun getHeaderField(name: String?): String? = null
        override fun getContentLengthLong(): Long = answer?.body?.length?.toLong() ?: -1L
    }
}
