package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class CapsulePlanTest {
    private fun track(id: String, duration: Long = 180000) = Track(id,id,"QA","",durationMs = duration)
    @Test fun favoritesArePrioritizedWithoutDuplicateTracksOrSourceMutation() {
        val source = listOf(track("a"),track("b"),track("a"),track("c"))
        assertEquals(listOf("c","a"),CapsulePlan.choose(source,setOf("c"),360000,100000000,emptyMap()).map { it.id })
        assertEquals(4,source.size)
    }
    @Test fun durationAndStorageAreBothBounded() {
        assertTrue(CapsulePlan.choose(listOf(track("a")), emptySet(),1000,100000000,emptyMap()).isEmpty())
        assertTrue(CapsulePlan.choose(listOf(track("a")), emptySet(),180000,100,emptyMap()).isEmpty())
    }
    @Test fun existingFilesUseRealBytesAndUnknownDurationIsExplicitlyEstimated() {
        assertEquals(1,CapsulePlan.choose(listOf(track("a")),emptySet(),180000,100,mapOf("a" to 90L)).size)
        assertEquals(CapsulePlan.estimateBytes(track("a")),CapsulePlan.estimateBytes(track("b",0)))
    }
    @Test fun byteLedgerRejectsOverflowAndHandlesMetadataReplacement() {
        val budget = DownloadBudget(100)
        assertTrue(budget.spend(80));assertFalse(budget.spend(21));assertFalse(budget.spend(-1))
        assertTrue(budget.spend(10));assertTrue(budget.replace(10,20));assertEquals(100,budget.used)
        assertFalse(budget.replace(20,21));assertEquals(0,budget.remaining)
    }
    @Test fun maximumLedgerDoesNotOverflowOnReplacement() {
        val budget = DownloadBudget(Long.MAX_VALUE)
        assertTrue(budget.spend(20));assertTrue(budget.replace(20,30));assertEquals(30,budget.used)
    }
}
