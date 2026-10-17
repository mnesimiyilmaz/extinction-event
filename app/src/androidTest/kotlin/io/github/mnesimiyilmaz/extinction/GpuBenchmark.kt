package io.github.mnesimiyilmaz.extinction

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mnesimiyilmaz.extinction.render.EglCore
import io.github.mnesimiyilmaz.extinction.render.Frame
import io.github.mnesimiyilmaz.extinction.render.GlRenderer
import io.github.mnesimiyilmaz.extinction.render.RenderPolicy
import io.github.mnesimiyilmaz.extinction.scene.Event
import io.github.mnesimiyilmaz.extinction.scene.SceneParams
import io.github.mnesimiyilmaz.extinction.scene.replay
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// GPU cost per pass at fixed scene moments, at the wallpaper's render size, paced like it so the
// GPU governor behaves as it does on the home screen. Prints to logcat (tag ExtinctionBench). Opt-in:
// tools/device-test.sh GpuBenchmark -e benchmark true
@RunWith(AndroidJUnit4::class)
class GpuBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tuning = JSONObject(context.assets.open("config/tuning.json").bufferedReader().use { it.readText() })

    @Test
    fun scenes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("benchmark") == "true")
        // Default: what the wallpaper renders on this panel, a fixed-size surface at render size.
        val panel = context.resources.displayMetrics
        val size = RenderPolicy.internalSize(panel.widthPixels, panel.heightPixels, tuning.optDouble("renderScale", 0.75))
        val w = InstrumentationRegistry.getArguments().getString("width")?.toInt() ?: size.width
        val h = InstrumentationRegistry.getArguments().getString("height")?.toInt() ?: size.height
        val preScaled = w == size.width && h == size.height
        val params = SceneParams.fromJson(tuning)
        val unlockAt = 6.0
        val events = listOf(Event(0.0, "SCREEN_ON", locked = true), Event(unlockAt, "UNLOCK"))
        // name, scene time, fps cap the wallpaper uses there
        val scenes = listOf(
            Triple("approach", 4.0, 60.0),
            Triple("impact+1", unlockAt + 0.6 + 1.0, 60.0),
            Triple("aftermath+8", unlockAt + 0.6 + 8.0, 30.0),
            Triple("aftermath+40", unlockAt + 0.6 + 40.0, 30.0),
            Triple("aftermath+120", unlockAt + 0.6 + 120.0, 30.0), // the home screen's steady state
        )
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(w, h) }
        val surface = Surface(texture)
        val egl = EglCore()
        egl.init()
        egl.attach(surface)
        try {
            val renderer = GlRenderer(context.assets, tuning, profile = true)
            for ((name, t0, fps) in scenes) {
                renderer.gpuReport() // drop what came before
                val frames = (fps * 3).toInt()
                for (i in 0 until frames) {
                    val t = t0 + i / fps
                    val s = replay(params, events, t)
                    renderer.render(Frame(t.toFloat(), s.p.toFloat(), s.meteorT.toFloat(), s.impactT.toFloat(), 0.5f), w, h, preScaled)
                    GLES20.glFlush() // no swap: nothing consumes the buffers, the GPU work is what counts
                    SystemClock.sleep((1000 / fps).toLong())
                }
                GLES20.glFinish()
                Log.i(TAG, "${w}x$h $name (${s(t0)}s): ${renderer.gpuReport()}")
            }
            renderer.release()
        } finally {
            egl.release()
            surface.release()
            texture.release()
        }
    }

    private fun s(t: Double) = "%.1f".format(t)

    private companion object {
        const val TAG = "ExtinctionBench"
    }
}
