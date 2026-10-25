package io.github.mnesimiyilmaz.extinction.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassTimesTest {
    @Test
    fun reportsMeanMillisecondsPerPassAndFrameThenResets() {
        val times = PassTimes(listOf("bg", "fx"))
        times.add(0, 1_000_000)
        times.add(1, 3_000_000)
        times.frameDone()
        times.add(1, 1_000_000)
        times.frameDone()
        assertEquals("gpu ms/frame: bg 0.50, fx 2.00, total 2.50 (2 frames)", times.report())
        assertNull(times.report())
    }
}
