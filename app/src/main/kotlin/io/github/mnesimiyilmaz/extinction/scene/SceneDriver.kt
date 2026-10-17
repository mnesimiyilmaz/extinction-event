package io.github.mnesimiyilmaz.extinction.scene

// Turns wallpaper engine signals into scene state (docs/architecture.md). Pure Kotlin, so the
// mapping is unit-tested on the JVM; at runtime it lives on the render thread.
class SceneDriver(private val params: SceneParams, private val preview: Boolean, demoUnlockAt: Double) {
    val scene = SceneState(params)

    // uTime: seconds since the scene last left OFF (keeps noise inputs small for float precision).
    var time = 0.0
        private set

    private val demoEvents = listOf(Event(0.0, "SCREEN_ON", locked = true), Event(demoUnlockAt, "UNLOCK"))
    private val demoLength = demoUnlockAt + DEMO_AFTERMATH
    private var demoT = 0.0
    private var scrubT: Double? = null
    private var liveTime = 0.0

    // Debug builds: hold the canonical sequence (screen on locked, unlock at demoUnlockAt) still at
    // scene time t, for screenshots of a given moment; null resumes the live scene where it was.
    fun scrub(t: Double?) {
        if (t != null && scrubT == null) liveTime = time
        if (t == null && scrubT != null) time = liveTime
        scrubT = t
        if (t != null) time = t
    }

    fun onScreenOn(locked: Boolean) {
        if (scene.state == SceneStateName.OFF) time = 0.0
        scene.screenOn(locked)
    }

    fun onScreenOff() = scene.screenOff()

    // Becoming visible while OFF means no screen-on was seen (first apply, process restart).
    fun onVisible(locked: Boolean) {
        if (scene.state == SceneStateName.OFF) onScreenOn(locked)
    }

    fun onCommand(action: String) {
        when (action) {
            CMD_DRAG_START -> scene.dragStart()
            CMD_DRAG_END -> scene.dragEnd()
        }
    }

    fun onFrame(dt: Double, keyguardLocked: () -> Boolean): Snapshot {
        scrubT?.let { return replay(params, demoEvents, it) }
        if (preview) { // the picker loops the canonical sequence
            demoT = (demoT + dt) % demoLength
            time = demoT
            return replay(params, demoEvents, demoT)
        }
        if (scene.state == SceneStateName.APPROACH && !keyguardLocked()) scene.unlock()
        time += dt
        scene.tick(dt)
        return scene.snapshot()
    }

    companion object {
        const val CMD_WAKING_UP = "android.wallpaper.wakingup"
        // Undocumented One UI commands: a lock-screen drag starts / ends.
        const val CMD_DRAG_START = "samsung.android.wallpaper.pause"
        const val CMD_DRAG_END = "samsung.android.wallpaper.resume"
        const val DEMO_AFTERMATH = 12.0
    }
}
