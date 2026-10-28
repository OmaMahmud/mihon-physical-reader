package eu.kanade.tachiyomi.ui.reader.viewer.physical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalBookPaginationTest {
    @Test
    fun closedEndsReopenTheLastSpreadWithoutSkippingASheet() {
        val cases = mapOf(0 to -1, 1 to -1, 2 to 1, 3 to 1, 4 to 3, 5 to 3, 24 to 23, 25 to 23)
        cases.forEach { (last, previous) ->
            assertEquals("Last face $last", previous, PhysicalBookPagination.beforeClosedEnd(last))
        }
    }

    @Test
    fun shortBooksCloseWithoutAnOutOfBoundsPage() {
        assertEquals(0, PhysicalBookPagination.afterForward(-1, 0))
        assertEquals(1, PhysicalBookPagination.afterForward(-1, 1))
        assertEquals(1, PhysicalBookPagination.afterForward(-1, 2))
        assertTrue(PhysicalBookPagination.closesEnd(-1, 0))
        assertTrue(PhysicalBookPagination.closesEnd(-1, 1))
        assertFalse(PhysicalBookPagination.closesEnd(-1, 2))
    }

    @Test
    fun finalTurnsCloseForBothOddAndEvenFaceCounts() {
        assertEquals(2, PhysicalBookPagination.afterForward(1, 2))
        assertEquals(3, PhysicalBookPagination.afterForward(1, 3))
        assertTrue(PhysicalBookPagination.closesEnd(1, 2))
        assertTrue(PhysicalBookPagination.closesEnd(1, 3))
        assertFalse(PhysicalBookPagination.closesEnd(1, 5))
    }
}
