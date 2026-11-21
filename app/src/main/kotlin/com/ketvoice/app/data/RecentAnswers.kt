package com.ketvoice.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * 8.1 (owner: « chargement et latence … catastrophique »): the answers of a service asked a moment ago. The same
 * question asked again within [keepMs] is answered from memory, and a question asked while the same one is on its way
 * waits for that answer instead of asking again (a tile's prefetch and its page, a search and its duplicate source).
 * Only answers [keep] accepts are kept: an empty answer may be a network failure, so it is asked again next time.
 */
internal class RecentAnswers<V>(private val keepMs: Long, private val max: Int = 64, private val keep: (V) -> Boolean = { true }) {
    private val kept = object : LinkedHashMap<String, Pair<Long, V>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, V>>?) = size > max
    }
    private val asking = HashMap<String, CompletableDeferred<V>>()

    suspend fun get(key: String, ask: suspend () -> V): V {
        val now = System.currentTimeMillis()
        var waiting: CompletableDeferred<V>? = null
        val mine = synchronized(this) {
            kept[key]?.takeIf { now - it.first in 0..keepMs }?.let { return it.second }
            waiting = asking[key]
            if (waiting == null) CompletableDeferred<V>().also { asking[key] = it } else null
        }
        // The one asking was stopped (its page closed) or failed: this caller asks for itself.
        waiting?.let { return try { it.await() } catch (e: CancellationException) { kotlinx.coroutines.currentCoroutineContext().ensureActiveOrThrow(e); ask() } catch (_: Exception) { ask() } }
        try {
            val answer = ask()
            synchronized(this) { if (keep(answer)) kept[key] = System.currentTimeMillis() to answer }
            mine!!.complete(answer)
            return answer
        } catch (e: Throwable) {
            mine!!.completeExceptionally(e)
            throw e
        } finally {
            synchronized(this) { if (asking[key] === mine) asking.remove(key) }
        }
    }

    private fun kotlin.coroutines.CoroutineContext.ensureActiveOrThrow(e: CancellationException) {
        if (this[kotlinx.coroutines.Job]?.isActive == false) throw e
    }

    fun clear() = synchronized(this) { kept.clear() }
}
