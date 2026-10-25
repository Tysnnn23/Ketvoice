package com.ketvoice.app.data

class DownloadBudget(val limit: Long) {
    init { require(limit >= 0) }
    var used: Long = 0; private set
    val remaining: Long get() = limit - used
    fun spend(bytes: Long): Boolean {
        if (bytes < 0 || bytes > remaining) return false
        used += bytes; return true
    }
    fun replace(oldSize: Long, newSize: Long): Boolean {
        if (oldSize < 0 || oldSize > used || newSize < 0 || newSize > remaining + oldSize) return false
        used = used - oldSize + newSize; return true
    }
}

object CapsulePlan {
    fun estimateBytes(track: Track): Long = (track.durationMs.takeIf { it > 0 } ?: 180_000L)
        .coerceAtMost(86_400_000L) * 192_000 / 8000 + 1_048_576L
    fun choose(candidates: List<Track>, favorites: Set<String>, durationMs: Long, budget: Long,
               existing: Map<String, Long>): List<Track> {
        var time = 0L; var bytes = 0L
        return candidates.distinctBy { it.id }.sortedBy { if (it.id in favorites) 0 else 1 }.filter { track ->
            val length = (track.durationMs.takeIf { it > 0 } ?: 180_000L).coerceAtMost(86_400_000L)
            val size = existing[track.id] ?: estimateBytes(track)
            if (length > durationMs - time || size > budget - bytes) false
            else { time += length; bytes += size; true }
        }.take(100)
    }
}
