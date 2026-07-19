package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenSetTest {
    @Test fun addCompactsContiguous() {
        val s = SeenSet()
        assertTrue(s.add(2)); assertTrue(s.add(1))   // 1 then rolls up: 2 already sparse
        // after adding 2 (sparse={2}), adding 1 rolls contiguous to 2
        assertEquals(2, s.toJson()["contiguous"])
        assertEquals(emptyList<Int>(), s.toJson()["sparse"])
    }

    @Test fun hasAndDedup() {
        val s = SeenSet()
        assertTrue(s.add(1)); assertFalse(s.add(1))  // dedup
        assertTrue(s.has(1)); assertFalse(s.has(2))
        assertFalse(s.add(0))                        // seq < 1 rejected
    }

    @Test fun sparseAboveGap() {
        val s = SeenSet()
        s.add(1); s.add(3)                           // gap at 2
        assertEquals(1, s.toJson()["contiguous"])
        assertEquals(listOf(3), s.toJson()["sparse"])
        s.add(2)                                     // fills gap, rolls to 3
        assertEquals(3, s.toJson()["contiguous"])
        assertEquals(emptyList<Int>(), s.toJson()["sparse"])
    }

    @Test fun jsonRoundTrip() {
        val s = SeenSet(2, mutableSetOf(5, 7))
        val back = SeenSet.fromJson(s.toJson())
        assertEquals(2, back.toJson()["contiguous"])
        assertEquals(listOf(5, 7), back.toJson()["sparse"])
    }
}
