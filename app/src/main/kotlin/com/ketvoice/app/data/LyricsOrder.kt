package com.ketvoice.app.data

import kotlinx.coroutines.async

/**
 * « Ordre des sources de paroles » (8.4): the order in which the automatic lyrics lookup tries the providers. Stored
 * as their ids joined by commas in `lyricsOrder`; empty means the default order, where the automatic lookup keeps
 * its behaviour of before (every source at once, the richest timing wins). With an order of the listener's, the
 * first source in that order that has lyrics wins. A source picked by hand for a title (the lyrics view's menu, or
 * « Source des paroles ») is still used alone.
 */
object LyricsOrder {
    val default: List<String> get() = MusicApi.lyricSources

    /** The stored order, made whole: unknown or repeated ids dropped, sources it does not name added in the default order. */
    fun parse(stored: String?): List<String> {
        val named = stored.orEmpty().split(',').map { it.trim() }.filter { it in default }.distinct()
        return named + default.filter { it !in named }
    }

    /** What is stored for [order]: empty for the default order. */
    fun encode(order: List<String>): String = parse(order.joinToString(",")).let { if (it == default) "" else it.joinToString(",") }

    /** Whether the listener set an order of their own. */
    fun custom(stored: String?): Boolean = parse(stored) != default

    /** A value a backup or the account's sync may carry: known ids only, each once. */
    fun valid(stored: String): Boolean = stored.isEmpty() || stored.length <= 200 &&
        stored.split(',').let { ids -> ids.all { it in default } && ids.distinct().size == ids.size }

    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The first of [sources], in their order, whose answer [has] lyrics, with that answer; null when none has. Every
     * source is asked at once (a slow first source does not hold the others back), but a later source never wins
     * over an earlier one that has lyrics; once the winner is known, the sources after it are stopped. A source that
     * fails counts as having none.
     */
    suspend fun <T> firstInOrder(sources: List<String>, has: (T) -> Boolean, fetch: suspend (String) -> T?): Pair<String, T>? =
        kotlinx.coroutines.coroutineScope {
            val answers = sources.map { src -> async { runCatching { fetch(src) }.getOrNull() } }
            for ((index, answer) in answers.withIndex()) {
                val result = answer.await()?.takeIf(has) ?: continue
                answers.drop(index + 1).forEach { it.cancel() }
                return@coroutineScope sources[index] to result
            }
            null
        }
}
