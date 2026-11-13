package org.projectdrive.app.core.location

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.navigation.Coordinate

class LocationFixTest {
    private val point = Coordinate(0.0, 36.0)
    private fun fix(accuracy: Float, time: Long = 10_000L) = LocationFix(point, accuracy, time)

    @Test fun guidanceRequiresFreshPositiveBoundedAccuracy() {
        assertTrue(fix(8f).canGuide(11_000))
        assertTrue(fix(50f).canGuide(25_000))
        for (accuracy in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 50.1f)) {
            assertFalse(fix(accuracy).canGuide(11_000))
        }
        assertFalse(fix(8f).canGuide(25_001))
        assertFalse(fix(8f).canGuide(9_999))
    }
    @Test fun coarseUpdatesCannotMaskRecentPreciseFixesOrExtendTheirFreshness() {
        val precise = fix(8f)
        assertSame(precise, preferredFix(precise, fix(100f, 11_000), 11_000))
        assertSame(precise, preferredFix(precise, fix(100f, 13_000), 13_000))
        val coarse = fix(100f, 13_001)
        assertSame(coarse, preferredFix(precise, coarse, 13_001))
        assertFalse(coarse.canGuide(13_001))
    }
    @Test fun oldCallbacksCannotRewindPositionAndStaleFixesAreNotRetained() {
        val current = fix(12f)
        assertSame(current, preferredFix(current, fix(5f, 9_000), 10_000))
        assertNull(preferredFix(current, fix(3f, 8_000), 30_000))
        val fresh = fix(20f, 30_000)
        assertSame(fresh, preferredFix(current, fresh, 30_000))
    }
    @Test fun accuracyDisplayNeverTruncatesToPerfectPrecision() {
        assertTrue(fix(0f).accuracyStatus().contains("unknown"))
        assertTrue(fix(0.7f).accuracyStatus().contains("<1 m"))
        assertTrue(fix(8.2f).accuracyStatus().contains("±9 m"))
        assertTrue(fix(82f).accuracyStatus().contains("too approximate"))
    }
}
