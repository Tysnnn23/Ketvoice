package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class QueueEntryIdsTest {
    @Test fun repeatedSongsHaveDistinctOccurrenceKeys() {
        var i=0
        val keys=QueueEntryIds.reconcile(emptyList(),emptyList(),listOf("a","a","b")){"key-${i++}"}
        assertEquals(3,keys.distinct().size)
    }
    @Test fun insertedAndRetainedSongsKeepStableKeys() {
        val keys=QueueEntryIds.reconcile(listOf("a","b"),listOf("A","B"),listOf("b","c","a")){"NEW"}
        assertEquals(listOf("B","NEW","A"),keys)
    }
    @Test fun duplicateOccurrencesAreMatchedInOrder() {
        assertEquals(listOf("first","second"),QueueEntryIds.reconcile(listOf("a","b","a"),listOf("first","B","second"),listOf("a","a")))
    }
}
