package com.ketvoice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.5 « Recherche et tri dans les listes »: one search and one set of sorts for a playlist, Favoris and Téléchargements. */
class ListOrderTest {
    private val tracks = listOf(
        Track("1", "Halo", "Beyoncé", "I Am... Sasha Fierce", durationMs = 261_000),
        Track("2", "Été indien", "Joe Dassin", "Le Jardin du Luxembourg", durationMs = 214_000),
        Track("3", "Œuvre", "Zaz", "Effet miroir", durationMs = 180_000))
    private fun ids(order: ListOrder, query: String = "") =
        ListOrders.arrange(tracks, query, order, ListOrders.tracks { it.id.toLong() }).map { it.id }

    @Test fun searchIgnoresAccentsCaseAndWordOrder() {
        assertEquals(listOf("1"), ids(ListOrder(ListSort.Added, false), "beyonce"))
        assertEquals(listOf("2"), ids(ListOrder(ListSort.Added, false), "ETE  indien"))
        assertEquals(listOf("1"), ids(ListOrder(ListSort.Added, false), "fierce halo"))
        assertEquals(listOf("3"), ids(ListOrder(ListSort.Added, false), "oeuvre"))
        assertEquals(listOf("2"), ids(ListOrder(ListSort.Added, false), "luxembourg"))
        assertTrue(ids(ListOrder(ListSort.Added), "nothing here").isEmpty())
        assertTrue(ListOrders.matches("  ", "a", "b", "c"))
    }

    @Test fun eachSortGoesBothWays() {
        assertEquals(listOf("3", "2", "1"), ids(ListOrder(ListSort.Added)))
        assertEquals(listOf("1", "2", "3"), ids(ListOrder(ListSort.Added, descending = false)))
        assertEquals(listOf("2", "1", "3"), ids(ListOrder(ListSort.Title)))
        assertEquals(listOf("1", "2", "3"), ids(ListOrder(ListSort.Artist)))
        assertEquals(listOf("3", "1", "2"), ids(ListOrder(ListSort.Album)))
        assertEquals(listOf("3", "2", "1"), ids(ListOrder(ListSort.Duration)))
        assertEquals(listOf("1", "2", "3"), ids(ListOrder(ListSort.Duration, descending = true)))
    }

    @Test fun choosingTheSameSortTurnsItAroundAndTheCodeRoundTrips() {
        val title = ListOrder(ListSort.Added).choose(ListSort.Title)
        assertFalse(title.descending)
        assertTrue(title.choose(ListSort.Title).descending)
        assertTrue(title.choose(ListSort.Added).descending)
        for (sort in ListSort.entries) for (down in listOf(false, true))
            assertEquals(ListOrder(sort, down), ListOrder.parse(ListOrder(sort, down).code, ListOrder(ListSort.Title)))
        val fallback = ListOrder(ListSort.Album)
        assertEquals(fallback, ListOrder.parse(null, fallback))
        assertEquals(fallback, ListOrder.parse("Nope:desc", fallback))
    }
}
