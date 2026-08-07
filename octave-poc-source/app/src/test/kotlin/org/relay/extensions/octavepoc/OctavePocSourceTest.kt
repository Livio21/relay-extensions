package org.relay.extensions.octavepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OctavePocSourceTest {
    @Test
    fun acceptsOnlyPositiveNumericTrackIds() {
        assertEquals("2954912511", trackId(" 2954912511 "))
        assertNull(trackId("0"))
        assertNull(trackId("2954912511&other=value"))
    }
}
