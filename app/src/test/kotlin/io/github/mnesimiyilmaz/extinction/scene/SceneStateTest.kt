package io.github.mnesimiyilmaz.extinction.scene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

// Runs the state machine vectors (src/test/resources/vectors.json).
class SceneStateTest {
    private val vectors = JSONObject(javaClass.getResource("/vectors.json")!!.readText())
    private val params = SceneParams.fromJson(vectors.getJSONObject("params"))
    private val tol = 1e-4

    private fun near(actual: Double, expected: Double, label: String) =
        assertTrue("$label: expected $expected, got $actual", abs(actual - expected) <= tol)

    private fun events(arr: org.json.JSONArray) = (0 until arr.length()).map {
        val e = arr.getJSONObject(it)
        Event(e.getDouble("t"), e.getString("type"), e.optBoolean("locked", false))
    }

    @Test
    fun sharedVectors() {
        val cases = vectors.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val evs = events(c.getJSONArray("events"))
            val samples = c.getJSONArray("samples")
            for (j in 0 until samples.length()) {
                val s = samples.getJSONObject(j)
                val snap = replay(params, evs, s.getDouble("t"))
                val at = "${c.getString("name")} @ t=${s.getDouble("t")}"
                assertEquals("$at state", s.getString("state"), snap.state.name)
                if (s.has("p")) near(snap.p, s.getDouble("p"), "$at p")
                if (s.has("impactT")) near(snap.impactT, s.getDouble("impactT"), "$at impactT")
                if (s.has("meteorT")) near(snap.meteorT, s.getDouble("meteorT"), "$at meteorT")
            }
        }
    }

    @Test
    fun manySmallTicksMatchOneLargeTick() {
        val small = SceneState(params)
        val big = SceneState(params)
        for (s in listOf(small, big)) { s.screenOn(true); s.tick(2.0); s.unlock() }
        repeat(300) { small.tick(1.0 / 60) }
        big.tick(5.0)
        assertEquals(SceneStateName.AFTERMATH, small.state)
        near(small.snapshot().impactT, big.snapshot().impactT, "impactT")
        near(small.snapshot().meteorT, big.snapshot().meteorT, "meteorT")
    }

    @Test
    fun degenerateTuningStaysFinite() {
        val evs = listOf(Event(0.0, "SCREEN_ON", true), Event(3.0, "UNLOCK"))
        for (p in listOf(params.copy(tau = 0.0), params.copy(pHold = 0.0), params.copy(diveBase = 0.0, diveExtra = 0.0))) {
            for (t in listOf(0.0, 0.5, 3.0, 7.0, 10.0)) {
                val s = replay(p, evs, t)
                assertTrue("$p @ $t: $s", s.p.isFinite() && s.impactT.isFinite() && s.meteorT.isFinite())
            }
        }
    }

    @Test
    fun unknownEventTypesAreRejected() {
        try {
            replay(params, listOf(Event(0.0, "NOPE")), 1.0)
            fail("expected an exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown event type: NOPE"))
        }
    }
}
