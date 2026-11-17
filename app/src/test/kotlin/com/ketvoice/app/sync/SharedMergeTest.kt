package com.ketvoice.app.sync

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

/** The rules of a playlist edited by several accounts: how writes combine, what an edit means, what is cancelled. */
class SharedMergeTest {
    private fun track(id: String) = Track(id = id, title = "Title $id", artist = "Artist", album = "Album", coverUrl = "https://example.com/$id.jpg")
    private fun row(id: String, pos: Double, at: String = "2026-11-18T10:00:00+00:00") = SharedRow("row-$id", track(id), "u", at, pos)
    private fun list(vararg rows: SharedRow, owner: String = "me") = SharedList("pl", "Road trip", owner, rows.toList())
    private fun entry(id: String) = PlaylistEntry("row-$id", "Title $id", "Artist", null, track(id))
    private fun newEntry(id: String) = PlaylistEntry("new-$id", "Title $id", "Artist", null, track(id))
    private fun ids(list: SharedList) = list.rows.map { it.track.id }

    @Test fun titlesAreOrderedByPositionThenAddedTimeThenId() {
        val shown = SharedMerge.display(list(row("c", 2.0, "2026-11-18T10:00:02+00:00"), row("b", 2.0, "2026-11-18T10:00:01+00:00"),
            row("a", 1.0), row("d", 2.0, "2026-11-18T10:00:01+00:00")), emptyList())
        assertEquals(listOf("a", "b", "d", "c"), ids(shown))
    }

    @Test fun unsentWritesAreShownOnTopOfWhatTheServerSays() {
        val ops = listOf(SharedOp.Add("pl", "row-x", track("x"), 5.0), SharedOp.Remove("pl", "row-a"), SharedOp.Move("pl", "row-b", 9.0),
            SharedOp.Rename("pl", "Summer"), SharedOp.Add("other", "row-z", track("z"), 1.0))
        val shown = SharedMerge.display(list(row("a", 1.0), row("b", 2.0), row("c", 3.0)), ops)
        assertEquals(listOf("c", "x", "b"), ids(shown))
        assertEquals("Summer", shown.name)
    }

    @Test fun theSameTitleAddedByTwoPeopleStaysOnceAndAMoveOfARemovedTitleIsDropped() {
        val shown = SharedMerge.display(list(row("a", 1.0)), listOf(SharedOp.Add("pl", "row-other", track("a"), 4.0),
            SharedOp.Remove("pl", "row-a"), SharedOp.Move("pl", "row-a", 8.0)))
        assertTrue(shown.rows.isEmpty())
    }

    @Test fun twoPeopleAddingBetweenTheSameNeighboursBothLand() {
        val mine = SharedMerge.diff(list(row("a", 1.0), row("b", 2.0)), listOf(entry("a"), newEntry("m"), entry("b")))
        val theirs = SharedMerge.diff(list(row("a", 1.0), row("b", 2.0)), listOf(entry("a"), newEntry("t"), entry("b")))
        val server = list(row("a", 1.0), row("b", 2.0), row("m", (mine.single() as SharedOp.Add).pos), row("t", (theirs.single() as SharedOp.Add).pos))
        assertEquals(setOf("a", "b", "m", "t"), ids(SharedMerge.display(server, emptyList())).toSet())
        assertEquals("a", ids(SharedMerge.display(server, emptyList())).first())
        assertEquals("b", ids(SharedMerge.display(server, emptyList())).last())
    }

    @Test fun anEditTurnsIntoTheWritesItNeeds() {
        val shown = list(row("a", 1.0), row("b", 2.0), row("c", 3.0))
        assertTrue(SharedMerge.diff(shown, listOf(entry("a"), entry("b"), entry("c"))).isEmpty())
        assertEquals(listOf(SharedOp.Remove("pl", "row-b")), SharedMerge.diff(shown, listOf(entry("a"), entry("c"))))
        val append = SharedMerge.diff(shown, listOf(entry("a"), entry("b"), entry("c"), newEntry("d"))).single() as SharedOp.Add
        assertEquals("new-d", append.row); assertEquals(4.0, append.pos, 0.0)
        val middle = SharedMerge.diff(shown, listOf(entry("a"), newEntry("d"), entry("b"), entry("c"))).single() as SharedOp.Add
        assertTrue(middle.pos > 1.0 && middle.pos < 2.0)
        val first = SharedMerge.diff(shown, listOf(newEntry("d"), entry("a"), entry("b"), entry("c"))).single() as SharedOp.Add
        assertTrue(first.pos < 1.0)
    }

    @Test fun draggingOneTitleIsOneMove() {
        val shown = list(row("a", 1.0), row("b", 2.0), row("c", 3.0), row("d", 4.0))
        val ops = SharedMerge.diff(shown, listOf(entry("a"), entry("c"), entry("d"), entry("b")))
        val moved = ops.single() as SharedOp.Move
        assertEquals("row-b", moved.row); assertTrue(moved.pos > 4.0)
        val up = SharedMerge.diff(shown, listOf(entry("a"), entry("d"), entry("b"), entry("c"))).single() as SharedOp.Move
        assertEquals("row-d", up.row); assertTrue(up.pos > 1.0 && up.pos < 2.0)
    }

    @Test fun aRunOfNewTitlesSharesTheRoomEvenly() {
        val shown = list(row("a", 1.0), row("b", 2.0))
        val ops = SharedMerge.diff(shown, listOf(entry("a"), newEntry("x"), newEntry("y"), newEntry("z"), entry("b"))).map { (it as SharedOp.Add).pos }
        assertEquals(3, ops.size)
        assertTrue(ops.zipWithNext().all { it.first < it.second } && ops.first() > 1.0 && ops.last() < 2.0)
    }

    @Test fun whenNeighboursAreTooCloseTheListIsRespaced() {
        val shown = list(row("a", 1.0), row("b", 1.00000001))
        val ops = SharedMerge.diff(shown, listOf(entry("a"), newEntry("x"), entry("b")))
        assertTrue(ops.any { it is SharedOp.Move && it.row == "row-b" && it.pos == 3.0 })
        assertTrue(ops.any { it is SharedOp.Add && it.row == "new-x" && it.pos == 2.0 })
    }

    @Test fun aTitleOnlyThisDeviceHasIsNeverSharedAndANewDuplicateIsIgnored() {
        val local = Track("local:1", "My file", "Me", "")
        val ops = SharedMerge.diff(list(row("a", 1.0)), listOf(entry("a"), PlaylistEntry("e", "My file", "Me", null, local),
            PlaylistEntry("dup", "Title a", "Artist", null, track("a"))))
        assertTrue(ops.isEmpty())
    }

    private fun state(owner: String = "me", vararg ops: SharedOp) = SharedState("me", mapOf("pl" to list(row("a", 1.0), owner = owner)), ops.toList())
    private fun local(name: String = "Road trip", vararg entries: PlaylistEntry) = LocalPlaylist(PlaylistRules.sharedLocalId("pl"), name, entries.toList())

    @Test fun deletingTheRowDeletesForTheOwnerAndLeavesForAnEditor() {
        assertEquals(listOf<SharedOp>(SharedOp.Delete("pl")), SharedMerge.edit(state("me"), emptyList(), setOf("pl")).state.ops)
        val left = SharedMerge.edit(state("someone"), emptyList(), setOf("pl")).state
        assertEquals(listOf<SharedOp>(SharedOp.Leave("pl")), left.ops)
        assertTrue(left.lists.isEmpty())
    }

    @Test fun aListNotYetInTheLibraryIsNeverTakenForDeleted() {
        val edit = SharedMerge.edit(state("me"), emptyList(), shown = emptySet())
        assertTrue(edit.state.ops.isEmpty()); assertEquals(1, edit.state.lists.size)
    }

    @Test fun onlyTheOwnerRenames() {
        val owner = SharedMerge.edit(state("me"), listOf(local("Summer", entry("a"))), setOf("pl"))
        assertEquals(listOf<SharedOp>(SharedOp.Rename("pl", "Summer")), owner.state.ops); assertFalse(owner.remirror)
        val editor = SharedMerge.edit(state("someone"), listOf(local("Summer", entry("a"))), setOf("pl"))
        assertTrue(editor.state.ops.isEmpty()); assertTrue(editor.remirror)
    }

    @Test fun anEditAddsToTheQueueWithoutTouchingWhatWaits() {
        val waiting = SharedOp.Add("pl", "row-q", track("q"), 9.0)
        val edit = SharedMerge.edit(state("me", waiting), listOf(local("Road trip", entry("a"), PlaylistEntry("row-q", "Title q", "Artist", null, track("q")), newEntry("n"))), setOf("pl"))
        assertEquals(waiting, edit.state.ops.first())
        assertEquals(2, edit.state.ops.size)
        assertEquals("new-n", (edit.state.ops[1] as SharedOp.Add).row)
    }

    @Test fun aTitleAddedThenRemovedBeforeItWentOutLeavesNothing() {
        val ops = SharedMerge.compact(listOf(SharedOp.Add("pl", "r", track("a"), 1.0), SharedOp.Move("pl", "r", 2.0), SharedOp.Remove("pl", "r")))
        assertTrue(ops.isEmpty())
    }

    @Test fun aTitleAlreadyOnItsWayIsFollowedByItsRemovalNotCancelled() {
        val add = SharedOp.Add("pl", "r", track("a"), 1.0)
        assertEquals(listOf(add, SharedOp.Remove("pl", "r")), SharedMerge.compact(listOf(add, SharedOp.Remove("pl", "r")), sent = listOf(add)))
    }

    @Test fun movesCollapseIntoTheLastOne() {
        assertEquals(listOf<SharedOp>(SharedOp.Add("pl", "r", track("a"), 7.0)),
            SharedMerge.compact(listOf(SharedOp.Add("pl", "r", track("a"), 1.0), SharedOp.Move("pl", "r", 7.0))))
        assertEquals(listOf<SharedOp>(SharedOp.Move("pl", "r", 3.0)),
            SharedMerge.compact(listOf(SharedOp.Move("pl", "r", 1.0), SharedOp.Move("pl", "r", 2.0), SharedOp.Move("pl", "r", 3.0))))
    }

    @Test fun aPlaylistCreatedThenDeletedBeforeItWentOutLeavesNothingButOneAlreadyThereIsDeleted() {
        assertTrue(SharedMerge.compact(listOf(SharedOp.Create("pl", "x"), SharedOp.Add("pl", "r", track("a"), 1.0), SharedOp.Delete("pl"))).isEmpty())
        assertEquals(listOf<SharedOp>(SharedOp.Delete("pl")), SharedMerge.compact(listOf(SharedOp.Move("pl", "r", 1.0), SharedOp.Delete("pl"))))
    }

    @Test fun theLibraryRowsKeepTheirPinAndUseTheRowIdsAsEntryIds() {
        val current = listOf(LocalPlaylist(PlaylistRules.sharedLocalId("pl"), "old", pinned = true, createdAt = 5))
        val rows = SharedMerge.mirror(state("me", SharedOp.Add("pl", "row-n", track("n"), 9.0)), current)
        assertEquals(1, rows.size)
        assertTrue(rows[0].pinned); assertEquals(5L, rows[0].createdAt)
        assertEquals(listOf("row-a", "row-n"), rows[0].entries.map { it.id })
        assertEquals("shared:pl", rows[0].id)
        assertEquals("pl", PlaylistRules.sharedServerId(rows[0].id))
    }

    @Test fun theSavedStateReadsBackExactly() {
        val state = SharedState("me", mapOf("pl" to list(row("a", 1.5), row("b", 2.5, "2026-11-18T10:00:00.123456+00:00"))),
            listOf(SharedOp.Create("n", "New"), SharedOp.Add("n", "r", track("z"), 1.0), SharedOp.Remove("pl", "row-a"),
                SharedOp.Move("pl", "row-b", 0.25), SharedOp.Rename("pl", "Renamed"), SharedOp.Delete("x"), SharedOp.Leave("y")))
        val back = SharedSnapshot.decode(SharedSnapshot.encode(state))!!
        assertEquals(state.user, back.user)
        assertEquals(state.ops, back.ops)
        assertEquals(state.lists.getValue("pl").rows.map { it.id to it.pos }, back.lists.getValue("pl").rows.map { it.id to it.pos })
        assertEquals("https://example.com/a.jpg", back.lists.getValue("pl").rows[0].track.coverUrl)
        assertNull(SharedSnapshot.decode("{not json"))
    }
}
