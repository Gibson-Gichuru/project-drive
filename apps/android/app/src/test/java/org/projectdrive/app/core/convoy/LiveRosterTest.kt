package org.projectdrive.app.core.convoy

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.navigation.Coordinate

class LiveRosterTest {
    private fun marker(id: String = "a", observed: Long = 1000) = Participant(id, Coordinate(-1.2, 36.8), 12.0, observed, 10000, 30000)
    @Test fun agesWithoutHeartbeatsAndExpiresAtBoundary() {
        val roster = LiveRoster(); roster.snapshot("one", 5, listOf(marker()))
        assertFalse(roster.visible(10999).single().stale(10999))
        assertTrue(roster.visible(11000).single().stale(11000))
        assertEquals(1, roster.visible(30999).size)
        assertTrue(roster.visible(31000).isEmpty())
    }
    @Test fun dropsOldSequenceAndRequiresSnapshotForInstanceChanges() {
        val roster = LiveRoster()
        assertFalse(roster.update("one", 10, marker()))
        roster.snapshot("one", 5, listOf(marker()))
        assertFalse(roster.update("one", 5, removed = "a"))
        assertFalse(roster.update("two", 6, removed = "a"))
        assertEquals(1, roster.visible(1000).size)
        assertTrue(roster.update("one", 6, removed = "a")); assertTrue(roster.visible(1000).isEmpty())
    }
    @Test fun reconnectSnapshotResetsBaselineAndNeverResurrectsCachedMarkers() {
        val roster = LiveRoster(); roster.snapshot("one", 50, listOf(marker()))
        roster.clear(); assertTrue(roster.visible(1000).isEmpty())
        roster.snapshot("one", 0, emptyList()); assertTrue(roster.update("one", 1, marker("b")))
        assertEquals("b", roster.visible(1000).single().id)
        roster.snapshot("two", 0, emptyList()); assertTrue(roster.visible(1000).isEmpty())
    }
    @Test fun retryBackoffIsBoundedAndAcceptsBoundedJitter() {
        assertEquals(1000L, retryDelayMillis(0)); assertEquals(8500L, retryDelayMillis(3, 1000))
        assertEquals(30500L, retryDelayMillis(Int.MAX_VALUE, 500))
    }
}
