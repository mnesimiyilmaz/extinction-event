package io.github.mnesimiyilmaz.extinction.wallpaper

import android.app.KeyguardManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import io.github.mnesimiyilmaz.extinction.render.EglCore
import io.github.mnesimiyilmaz.extinction.render.Frame
import io.github.mnesimiyilmaz.extinction.render.GlRenderer
import io.github.mnesimiyilmaz.extinction.render.RenderPolicy
import io.github.mnesimiyilmaz.extinction.scene.SceneDriver
import io.github.mnesimiyilmaz.extinction.scene.SceneParams
import io.github.mnesimiyilmaz.extinction.scene.SceneStateName
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val TAG = "Extinction"

// Owns the GL thread: the EGL context, renderer and scene driver all live on it. Engine
// callbacks arrive on the main thread and are posted here, so no state is shared across threads.
internal class RenderLoop(
    private val tuning: JSONObject,
    preview: Boolean,
    private val keyguard: KeyguardManager,
    debug: Boolean,
    private val createRenderer: () -> GlRenderer,
) : Choreographer.FrameCallback {
    private val thread = HandlerThread("ExtinctionRender").apply { start() }
    private val handler = Handler(thread.looper)
    private val driver = SceneDriver(SceneParams.fromJson(tuning), preview, tuning.optDouble("scrubUnlockAt", 6.0))
    private val egl = EglCore()
    private val stats = FrameStats(debug)
    private val keyguardLocked = { keyguard.isKeyguardLocked }
    private val caps = RenderPolicy.FrameCaps.from(tuning)
    private var renderer: GlRenderer? = null
    private var surface: Surface? = null
    @Volatile var failed = false
        private set
    private var width = 0
    private var height = 0
    private var preScaled = false
    private var offset = 0.5f
    private var visible = false
    private var hasSurface = false
    private var lastFrameNs = 0L
    private var lastRenderNs = 0L
    private var state = SceneStateName.OFF
    private var impactT = -1.0
    private var frameRate = 0.0
    private var fpsOverride: Double? = null

    fun post(block: SceneDriver.() -> Unit) {
        handler.post { driver.block() }
    }

    fun surfaceCreated(s: Surface) {
        handler.post {
            surface = s
            frameRate = 0.0
            hasSurface = true
            setUp()
            schedule()
        }
    }

    // A setup failure (no GLES3, a shader the driver rejects, a missing extension) must not escape:
    // an uncaught exception kills the process, the system restarts it, and it crash-loops until it
    // falls back to the default wallpaper. Retried on the next surface.
    private fun setUp() {
        val s = surface ?: return
        try {
            if (!egl.initialized) egl.init()
            egl.attach(s)
            if (renderer == null) renderer = createRenderer()
            failed = false
        } catch (e: RuntimeException) {
            Log.e(TAG, "GL setup failed", e)
            failed = true
            // Whatever was built before the failure goes with the context.
            renderer = null
            egl.release()
        }
    }

    fun surfaceChanged(w: Int, h: Int, preScaled: Boolean) {
        handler.post {
            width = w
            height = h
            this.preScaled = preScaled
        }
    }

    // Must not return before the GL thread has let go of the surface.
    fun surfaceDestroyed() {
        val done = CountDownLatch(1)
        handler.post {
            hasSurface = false
            surface = null
            Choreographer.getInstance().removeFrameCallback(this)
            egl.detach()
            done.countDown()
        }
        if (!done.await(2, TimeUnit.SECONDS)) Log.w(TAG, "GL thread still busy; surface released anyway")
    }

    fun setVisible(v: Boolean) {
        handler.post {
            visible = v
            if (v) {
                lastFrameNs = 0L
                stats.reset()
                driver.onVisible(keyguard.isKeyguardLocked)
                schedule()
            } else {
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }
    }

    fun setOffset(x: Float) {
        handler.post { offset = x }
    }

    // onSurfaceRedrawNeeded: the system shows the surface once this returns, so draw a frame first
    // instead of letting it show black (up to 1 s; the very first one waits for setup).
    fun redrawNow() {
        val done = CountDownLatch(1)
        handler.post {
            if (hasSurface && !failed) drawFrame(System.nanoTime())
            done.countDown()
        }
        if (!done.await(1, TimeUnit.SECONDS)) Log.w(TAG, "first frame not ready in time")
    }

    fun quit() {
        handler.post {
            Choreographer.getInstance().removeFrameCallback(this)
            renderer?.release()
            egl.release()
            renderer = null
        }
        thread.quitSafely()
    }

    private fun schedule() {
        if (!visible || !hasSurface || failed) return
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    // Wakes only for the vsyncs it renders: the next callback is requested a few ms before the cap
    // interval ends, rather than on every vsync of a 120 Hz panel.
    override fun doFrame(frameTimeNanos: Long) {
        if (!visible || !hasSurface || failed) return
        val cap = cap()
        if (cap <= 0.0) return // paused: the last frame stays on screen
        if (!RenderPolicy.frameDue(frameTimeNanos, lastRenderNs, cap)) {
            Choreographer.getInstance().postFrameCallback(this) // woke a vsync early
            return
        }
        lastRenderNs = frameTimeNanos
        Choreographer.getInstance().postFrameCallbackDelayed(this, RenderPolicy.frameDelayMs(cap))
        drawFrame(frameTimeNanos)
    }

    private fun cap() = RenderPolicy.frameCap(state, impactT, caps, fpsOverride)

    // Debug builds only (power measurements): null restores the tuned caps, 0 pauses rendering.
    fun setFpsOverride(fps: Double?) {
        handler.post {
            fpsOverride = fps
            Log.i(TAG, "fps override: ${fps ?: "off"}")
            lastRenderNs = 0L
            schedule()
        }
    }

    private fun drawFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNs == 0L) 0.0 else ((frameTimeNanos - lastFrameNs) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNs = frameTimeNanos
        val snap = driver.onFrame(dt, keyguardLocked) // polls the keyguard (a binder call) only here
        state = snap.state
        impactT = snap.impactT
        voteFrameRate(cap())
        val r = renderer ?: return
        if (width == 0 || height == 0) return
        val t0 = System.nanoTime()
        try {
            // OFF is never on screen for long (visibility follows the screen); show the calm Earth.
            val off = snap.state == SceneStateName.OFF
            r.render(
                Frame(driver.time.toFloat(), if (off) 0f else snap.p.toFloat(), if (off) -1f else snap.meteorT.toFloat(),
                    if (off) -1f else snap.impactT.toFloat(), offset),
                width, height, preScaled,
            )
            if (!egl.swap()) {
                if (egl.contextLost()) {
                    // Every GL object went with the context: start over on the same surface.
                    Log.w(TAG, "EGL context lost; recreating")
                    renderer = null
                    egl.release()
                    setUp()
                } else {
                    Log.w(TAG, "eglSwapBuffers failed")
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "frame failed", e)
            visible = false // stop the loop instead of crashing the wallpaper process every frame
            return
        }
        if (stats.add(frameTimeNanos, System.nanoTime() - t0, snap.state)) renderer?.gpuReport()?.let { Log.i(TAG, it) }
    }

    // Tells the display what this surface needs, so an LTPO panel can drop its refresh rate when
    // nothing else on screen animates faster.
    private fun voteFrameRate(cap: Double) {
        if (cap == frameRate) return
        frameRate = cap
        surface?.setFrameRate(cap.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }
}

// Debug builds: fps and CPU submit time every 2 s.
private class FrameStats(private val enabled: Boolean) {
    private var windowStart = 0L
    private var frames = 0
    private var cpuNs = 0L

    // true when it has just logged a window.
    fun add(frameNs: Long, cpu: Long, state: SceneStateName): Boolean {
        if (!enabled) return false
        if (windowStart == 0L) windowStart = frameNs
        frames++
        cpuNs += cpu
        val span = frameNs - windowStart
        if (span >= 2_000_000_000L) {
            Log.i(TAG, "%s fps %.1f, cpu %.2f ms/frame".format(state, frames * 1e9 / span, cpuNs / 1e6 / frames))
            windowStart = frameNs
            frames = 0
            cpuNs = 0
            return true
        }
        return false
    }

    // A window must not span time the wallpaper was hidden.
    fun reset() {
        windowStart = 0L
        frames = 0
        cpuNs = 0
    }
}
