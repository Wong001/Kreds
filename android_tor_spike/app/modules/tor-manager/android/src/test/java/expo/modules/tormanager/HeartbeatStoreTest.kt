package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatStoreTest {
    @Test fun capsAtFiftyKeepingNewest() {
        var list = emptyList<Beat>()
        for (i in 1..60) list = HeartbeatStore.append(list, Beat(i.toLong(), true, 10, null))
        assertEquals(50, list.size)
        assertEquals(60L, list.first().ts)   // newest first
        assertEquals(11L, list.last().ts)    // oldest kept
    }

    @Test fun jsonRoundTrips() {
        val list = listOf(Beat(5, false, 0, "io"), Beat(4, true, 123, null))
        assertEquals(list, HeartbeatStore.fromJsonArray(HeartbeatStore.toJsonArray(list)))
    }
}
