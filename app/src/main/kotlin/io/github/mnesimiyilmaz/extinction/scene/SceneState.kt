package io.github.mnesimiyilmaz.extinction.scene

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max

// The scene state machine (docs/architecture.md). Pure: no clock, no rendering.
// src/test/resources/vectors.json pins its behaviour.

enum class SceneStateName { OFF, APPROACH, DIVE, IMPACT, AFTERMATH }

data class SceneParams(
    val pHold: Double,
    val tau: Double,
    val diveBase: Double,
    val diveExtra: Double,
    val impactDuration: Double,
    val dragBoost: Double,
) {
    companion object {
        fun fromJson(o: JSONObject) = SceneParams(
            pHold = o.getDouble("pHold"),
            tau = o.getDouble("tau"),
            diveBase = o.getDouble("diveBase"),
            diveExtra = o.getDouble("diveExtra"),
            impactDuration = o.getDouble("impactDuration"),
            dragBoost = o.optDouble("dragBoost", 1.0),
        )
    }
}

data class Snapshot(val state: SceneStateName, val p: Double, val meteorT: Double, val impactT: Double)

class SceneState(var params: SceneParams) {
    var state = SceneStateName.OFF
        private set
    var meteorT = -1.0
        private set
    var impactT = -1.0
        private set
    private var approachT = 0.0
    private var diveP0 = 0.0
    private var diveT = 0.0
    private var diveDuration = 0.0
    private var dragging = false

    fun reset() {
        state = SceneStateName.OFF
        approachT = 0.0
        meteorT = -1.0
        diveP0 = 0.0
        diveT = 0.0
        diveDuration = 0.0
        impactT = -1.0
        dragging = false
    }

    fun screenOn(locked: Boolean) {
        if (state != SceneStateName.OFF) return
        meteorT = 0.0
        if (locked) {
            state = SceneStateName.APPROACH
            approachT = 0.0
        } else {
            enterDive(0.0)
        }
    }

    fun unlock() {
        when (state) {
            SceneStateName.OFF -> { // the unlock signal can arrive before screen-on
                meteorT = 0.0
                enterDive(0.0)
            }
            SceneStateName.APPROACH -> enterDive(p)
            else -> Unit
        }
    }

    fun screenOff() = reset()

    // Lock-screen drag: speeds up the approach only; never causes an impact.
    fun dragStart() {
        if (state == SceneStateName.APPROACH) dragging = true
    }

    fun dragEnd() {
        dragging = false
    }

    // Advances time, carrying any remainder across DIVE → IMPACT → AFTERMATH in one call.
    fun tick(dtIn: Double) {
        var dt = dtIn
        while (dt > 0) {
            when (state) {
                SceneStateName.OFF -> return
                SceneStateName.APPROACH -> {
                    approachT += dt * (if (dragging) params.dragBoost else 1.0)
                    meteorT += dt
                    return
                }
                SceneStateName.DIVE -> {
                    val left = diveDuration - diveT
                    if (dt < left) {
                        diveT += dt
                        meteorT += dt
                        return
                    }
                    diveT = diveDuration
                    meteorT += left
                    dt -= left
                    state = SceneStateName.IMPACT
                    impactT = 0.0
                }
                SceneStateName.IMPACT -> {
                    val left = params.impactDuration - impactT
                    if (dt < left) {
                        impactT += dt
                        return
                    }
                    impactT = params.impactDuration
                    dt -= left
                    state = SceneStateName.AFTERMATH
                }
                SceneStateName.AFTERMATH -> {
                    impactT += dt // keeps counting through the aftermath
                    return
                }
            }
        }
    }

    val p: Double
        get() = when (state) {
            SceneStateName.APPROACH ->
                if (params.tau > 0) params.pHold * (1 - exp(-approachT / params.tau)) else params.pHold
            SceneStateName.DIVE -> {
                val u = if (diveDuration > 0) diveT / diveDuration else 1.0
                diveP0 + (1 - diveP0) * u * u
            }
            SceneStateName.IMPACT, SceneStateName.AFTERMATH -> 1.0
            SceneStateName.OFF -> 0.0
        }

    fun snapshot() = Snapshot(state, p, meteorT, impactT)

    private fun enterDive(p0: Double) {
        state = SceneStateName.DIVE
        dragging = false
        diveP0 = p0
        diveT = 0.0
        val remaining = if (params.pHold > 0) max(0.0, (params.pHold - p0) / params.pHold) else 0.0
        diveDuration = params.diveBase + params.diveExtra * remaining
    }
}

data class Event(val t: Double, val type: String, val locked: Boolean = false)

fun SceneState.apply(e: Event) {
    when (e.type) {
        "SCREEN_ON" -> screenOn(e.locked)
        "UNLOCK" -> unlock()
        "SCREEN_OFF" -> screenOff()
        "DRAG_START" -> dragStart()
        "DRAG_END" -> dragEnd()
        else -> throw IllegalArgumentException("unknown event type: ${e.type}")
    }
}

// Replays a timed event list on a fresh machine and returns the snapshot at untilT.
// Events at exactly untilT are applied.
fun replay(params: SceneParams, events: List<Event>, untilT: Double): Snapshot {
    val s = SceneState(params)
    var now = 0.0
    for (e in events.sortedBy { it.t }) {
        if (e.t > untilT) break
        s.tick(e.t - now)
        now = e.t
        s.apply(e)
    }
    s.tick(untilT - now)
    return s.snapshot()
}
