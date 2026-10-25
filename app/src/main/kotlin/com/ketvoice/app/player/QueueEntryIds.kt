package com.ketvoice.app.player

object QueueEntryIds {
    fun reconcile(oldIds: List<String>, oldKeys: List<String>, ids: List<String>,
                  newKey: () -> String = { java.util.UUID.randomUUID().toString() }): List<String> {
        val pools = mutableMapOf<String, ArrayDeque<String>>()
        oldIds.forEachIndexed { i, id -> oldKeys.getOrNull(i)?.let { pools.getOrPut(id) { ArrayDeque() }.addLast(it) } }
        return ids.map { id -> pools[id]?.removeFirstOrNull() ?: newKey() }
    }
}
