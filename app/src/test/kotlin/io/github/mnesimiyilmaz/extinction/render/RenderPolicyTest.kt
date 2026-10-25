package io.github.mnesimiyilmaz.extinction.render

import io.github.mnesimiyilmaz.extinction.scene.SceneStateName
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderPolicyTest {
    private val hz = 10.0
    private val shockTime = 5.0

    @Test
    fun calmFramesRefreshAtTheRateToleratingJitter() {
        assertEquals(false, RenderPolicy.backgroundDue(10.05, 10.0, -1.0, hz, shockTime))
        assertEquals(true, RenderPolicy.backgroundDue(10.0995, 10.0, -1.0, hz, shockTime))
        assertEquals(true, RenderPolicy.backgroundDue(10.2, 10.0, -1.0, hz, shockTime))
    }

    @Test
    fun everyFrameWhileTheShockSpreads() {
        assertEquals(true, RenderPolicy.backgroundDue(20.008, 20.0, 0.5, hz, shockTime))
        assertEquals(true, RenderPolicy.backgroundDue(20.008, 20.0, 14.9, hz, shockTime))
        assertEquals(false, RenderPolicy.backgroundDue(20.008, 20.0, 15.1, hz, shockTime))
    }

    @Test
    fun timeRestartRefreshes() {
        assertEquals(true, RenderPolicy.backgroundDue(1.0, 10.0, -1.0, hz, shockTime))
    }

    @Test
    fun frameIsDueOnTheVsyncThatReachesTheCapDespiteJitter() {
        val ms = 1_000_000L
        assertEquals(true, RenderPolicy.frameDue(nowNs = 33 * ms, lastRenderNs = 0, cap = 30.0)) // 33.3 ms nominal
        assertEquals(false, RenderPolicy.frameDue(nowNs = 25 * ms, lastRenderNs = 0, cap = 30.0))
        assertEquals(true, RenderPolicy.frameDue(nowNs = 16 * ms, lastRenderNs = 0, cap = 60.0))
    }

    @Test
    fun nextFrameIsRequestedAFewMsBeforeItIsDue() {
        // No wake-ups on the 120 Hz vsyncs in between; 4 ms early so the target vsync is not missed.
        assertEquals(29L, RenderPolicy.frameDelayMs(30.0))
        assertEquals(12L, RenderPolicy.frameDelayMs(60.0))
        assertEquals(0L, RenderPolicy.frameDelayMs(240.0))
    }

    @Test
    fun internalSizeCapsTheHeightAt2340ThenScales() {
        assertEquals(RenderPolicy.Size(810, 1755), RenderPolicy.internalSize(1440, 3120, 0.75)) // QHD+
        assertEquals(RenderPolicy.Size(810, 1755), RenderPolicy.internalSize(1080, 2340, 0.75)) // FHD+
        assertEquals(RenderPolicy.Size(540, 1170), RenderPolicy.internalSize(1080, 2340, 0.5))
        assertEquals(RenderPolicy.Size(1, 1), RenderPolicy.internalSize(1, 1, 0.75))
    }

    private val caps = RenderPolicy.FrameCaps(active = 60.0, idle = 30.0, settled = 20.0, settleAfter = 30.0)

    @Test
    fun frameCapFollowsTheState() {
        assertEquals(60.0, RenderPolicy.frameCap(SceneStateName.APPROACH, impactT = -1.0, caps, override = null), 0.0)
        assertEquals(60.0, RenderPolicy.frameCap(SceneStateName.IMPACT, impactT = 1.0, caps, override = null), 0.0)
        assertEquals(30.0, RenderPolicy.frameCap(SceneStateName.AFTERMATH, impactT = 10.0, caps, override = null), 0.0)
    }

    @Test
    fun aftermathSettlesToALowerCapAfterAWhile() {
        assertEquals(30.0, RenderPolicy.frameCap(SceneStateName.AFTERMATH, impactT = 29.9, caps, override = null), 0.0)
        assertEquals(20.0, RenderPolicy.frameCap(SceneStateName.AFTERMATH, impactT = 30.0, caps, override = null), 0.0)
        assertEquals(20.0, RenderPolicy.frameCap(SceneStateName.AFTERMATH, impactT = 600.0, caps, override = null), 0.0)
    }

    @Test
    fun debugOverrideReplacesEveryCap() {
        assertEquals(15.0, RenderPolicy.frameCap(SceneStateName.APPROACH, impactT = -1.0, caps, override = 15.0), 0.0)
        assertEquals(0.0, RenderPolicy.frameCap(SceneStateName.AFTERMATH, impactT = 600.0, caps, override = 0.0), 0.0)
    }

    @Test
    fun capsComeFromTuning() {
        val t = org.json.JSONObject("""{"fpsActive": 60, "fpsIdle": 30, "fpsSettled": 20, "settleAfter": 30}""")
        assertEquals(caps, RenderPolicy.FrameCaps.from(t))
    }

    @Test
    fun theSceneOffRendersAtTheIdleCap() {
        assertEquals(30.0, RenderPolicy.frameCap(SceneStateName.OFF, impactT = -1.0, caps, override = null), 0.0)
    }

    @Test
    fun internalSizeToleratesAnEmptySurface() {
        assertEquals(RenderPolicy.Size(1, 1), RenderPolicy.internalSize(1080, 0, 0.75))
        assertEquals(RenderPolicy.Size(1, 1), RenderPolicy.internalSize(0, 0, 0.75))
    }
}
