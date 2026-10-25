package io.github.mnesimiyilmaz.extinction.render

import io.github.mnesimiyilmaz.extinction.scene.SceneStateName
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure scheduling and sizing decisions of the renderer.
object RenderPolicy {
    // Whether the cached background must be re-rendered. The Earth moves with the camera
    // shake and the blackout front spreads after impact, so it refreshes every frame for 3 shock time
    // constants; otherwise at bgRefreshHz, 2 ms early so vsync jitter cannot skip every other refresh.
    // abs(): time restarts at screen-on.
    fun backgroundDue(time: Double, bgTime: Double, impactT: Double, bgRefreshHz: Double, shockTime: Double): Boolean {
        if (impactT >= 0.0 && impactT < 3.0 * shockTime) return true
        return abs(time - bgTime) >= 1.0 / bgRefreshHz - 0.002
    }

    // Frame-rate caps (tuning): the approach, dive and impact; the aftermath; and the aftermath once
    // it has settled, settleAfter seconds after impact (the home screen's steady state).
    data class FrameCaps(val active: Double, val idle: Double, val settled: Double, val settleAfter: Double) {
        companion object {
            fun from(t: JSONObject) = FrameCaps(
                t.optDouble("fpsActive", 60.0), t.optDouble("fpsIdle", 30.0),
                t.optDouble("fpsSettled", 20.0), t.optDouble("settleAfter", 30.0),
            )
        }
    }

    // A debug override replaces every cap (0 = paused).
    fun frameCap(state: SceneStateName, impactT: Double, caps: FrameCaps, override: Double?): Double = override ?: when {
        state == SceneStateName.OFF -> caps.idle // only the calm Earth, and only in a passing moment
        state != SceneStateName.AFTERMATH -> caps.active
        impactT >= caps.settleAfter -> caps.settled
        else -> caps.idle
    }

    // A frame is due once a cap interval has passed, with 2 ms slack for vsync jitter.
    fun frameDue(nowNs: Long, lastRenderNs: Long, cap: Double): Boolean =
        nowNs - lastRenderNs >= (1e9 / cap - 2e6).toLong()

    // How long to wait before asking for the next vsync: a few ms short of the cap interval, so the
    // thread does not wake on the vsyncs in between (120 Hz panel) yet cannot miss the target one.
    fun frameDelayMs(cap: Double): Long = max(0L, (1000.0 / cap).toLong() - 4)

    data class Size(val width: Int, val height: Int)

    // Render size: as if the surface were at most 2340 px tall (taller panels cost no more), times
    // renderScale, keeping the surface's aspect ratio.
    fun internalSize(width: Int, height: Int, scale: Double): Size {
        if (width <= 0 || height <= 0) return Size(1, 1)
        val h = max(1, (min(height, 2340) * scale).roundToInt())
        val w = max(1, (h.toDouble() * width / height).roundToInt())
        return Size(w, h)
    }
}
