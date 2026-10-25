package io.github.mnesimiyilmaz.extinction.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class SceneDriverTest {
    private val params = SceneParams(pHold = 0.85, tau = 3.0, diveBase = 0.5, diveExtra = 0.4, impactDuration = 2.5, dragBoost = 4.0)
    private fun driver(preview: Boolean = false) = SceneDriver(params, preview, demoUnlockAt = 6.0)
    private val locked = { true }
    private val unlocked = { false }

    @Test
    fun keyguardPollingUnlocksOnlyWhenTheKeyguardIsGone() {
        val d = driver()
        d.onScreenOn(locked = true)
        d.onFrame(1.0, locked) // face recognised, still on the lock screen
        assertEquals(SceneStateName.APPROACH, d.scene.state)
        d.onFrame(1.0 / 60, unlocked)
        assertEquals(SceneStateName.DIVE, d.scene.state)
    }

    @Test
    fun keyguardIsNotPolledOutsideTheApproach() {
        val d = driver()
        d.onScreenOn(locked = false)
        var polls = 0
        repeat(10) { d.onFrame(1.0, { polls++; false }) }
        assertEquals(SceneStateName.AFTERMATH, d.scene.state)
        assertEquals(0, polls)
    }

    @Test
    fun samsungPauseAndResumeDriveTheDragBoost() {
        val d = driver()
        d.onScreenOn(locked = true)
        d.onCommand(SceneDriver.CMD_DRAG_START)
        val boosted = d.onFrame(1.0, locked).p
        assertTrue("boosted $boosted", abs(boosted - 0.85 * (1 - exp(-4.0 / 3))) < 1e-9)
        d.onCommand(SceneDriver.CMD_DRAG_END)
        val after = d.onFrame(1.0, locked).p
        assertTrue("after $after", abs(after - 0.85 * (1 - exp(-5.0 / 3))) < 1e-9)
    }

    @Test
    fun unrelatedCommandsAreIgnored() {
        val d = driver()
        d.onScreenOn(locked = true)
        repeat(10) { d.onCommand("samsung.android.wallpaper.blocktoucharea") }
        d.onCommand("samsung.android.wallpaper.bouncervisibilitychanged")
        d.onCommand("android.wallpaper.goingtosleep")
        val p = d.onFrame(3.0, locked).p
        assertTrue("p $p", abs(p - 0.85 * (1 - exp(-1.0))) < 1e-9)
    }

    @Test
    fun lateScreenOffAfterWakingUpStillEndsInAFreshApproach() {
        val d = driver()
        d.onScreenOn(locked = false)
        d.onFrame(10.0, unlocked) // in the aftermath
        d.onScreenOn(locked = true) // wakingup arrives before the late SCREEN_OFF broadcast: ignored
        d.onScreenOff()
        d.onScreenOn(locked = true) // SCREEN_ON broadcast
        assertEquals(SceneStateName.APPROACH, d.scene.state)
        assertEquals(0.0, d.time, 0.0)
    }

    @Test
    fun timeRestartsOnlyWhenTheScreenComesOnFromOff() {
        val d = driver()
        d.onScreenOn(locked = true)
        d.onFrame(5.0, locked)
        d.onScreenOn(locked = true) // duplicate signal
        assertEquals(5.0, d.time, 1e-9)
        d.onScreenOff()
        d.onScreenOn(locked = true)
        assertEquals(0.0, d.time, 0.0)
    }

    @Test
    fun firstVisibilityWithoutAScreenOnStartsTheScene() {
        val d = driver()
        d.onVisible(locked = false) // just applied from the picker, phone unlocked
        assertEquals(SceneStateName.DIVE, d.scene.state)
        d.onFrame(10.0, unlocked)
        d.onVisible(locked = false) // back from an app: no replay
        assertEquals(SceneStateName.AFTERMATH, d.scene.state)
    }

    @Test
    fun previewLoopsTheCanonicalSequence() {
        val d = driver(preview = true)
        assertEquals(SceneStateName.IMPACT, d.onFrame(7.0, locked).state) // dive ends at 6.554
        val wrapped = d.onFrame(SceneDriver.DEMO_AFTERMATH, locked) // 7 + 12 = 19 wraps (length 18) to 1
        assertEquals(SceneStateName.APPROACH, wrapped.state)
        assertEquals(1.0, d.time, 1e-9)
    }

    // Debug scrub (screenshots of fixed moments): the canonical sequence at t, frozen.
    @Test
    fun scrubHoldsTheCanonicalSequenceAtAFixedTime() {
        val d = driver()
        d.onScreenOn(locked = true)
        d.scrub(14.6)
        val expected = replay(params, listOf(Event(0.0, "SCREEN_ON", locked = true), Event(6.0, "UNLOCK")), 14.6)
        repeat(3) { assertEquals(expected, d.onFrame(1.0, unlocked)) }
        assertEquals(14.6, d.time, 0.0)
        assertEquals(SceneStateName.APPROACH, d.scene.state) // the live scene was left alone
    }

    @Test
    fun clearingTheScrubResumesTheLiveScene() {
        val d = driver()
        d.onScreenOn(locked = true)
        d.onFrame(1.0, locked)
        d.scrub(30.0)
        d.onFrame(1.0, locked)
        d.scrub(null)
        val snap = d.onFrame(1.0, locked)
        assertEquals(SceneStateName.APPROACH, snap.state)
        assertEquals(2.0, d.time, 1e-9)
    }
}
