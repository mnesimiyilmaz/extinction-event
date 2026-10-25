package io.github.mnesimiyilmaz.extinction

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mnesimiyilmaz.extinction.render.EglCore
import io.github.mnesimiyilmaz.extinction.render.Frame
import io.github.mnesimiyilmaz.extinction.render.GlRenderer
import io.github.mnesimiyilmaz.extinction.render.RenderPolicy
import io.github.mnesimiyilmaz.extinction.render.SceneConstants
import io.github.mnesimiyilmaz.extinction.scene.Event
import io.github.mnesimiyilmaz.extinction.scene.SceneParams
import io.github.mnesimiyilmaz.extinction.scene.replay
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

// Consecutive frames, scheduled as the wallpaper schedules them (one renderer, so the background and
// rock caches refresh on their own cadence), cropped around the buried rock and a patch of Earth,
// written to files/sequence/ as PNGs: for measuring frame-to-frame motion (judder). Opt-in:
// -e sequence true [-e t0 46.6] [-e fps 20] [-e frames 60].
@RunWith(AndroidJUnit4::class)
class SceneSequence {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tuning = JSONObject(context.assets.open("config/tuning.json").bufferedReader().use { it.readText() })

    @Test
    fun frames() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("sequence") == "true")
        val t0 = args.getString("t0")?.toDouble() ?: 46.6
        val fps = args.getString("fps")?.toDouble() ?: 20.0
        val count = args.getString("frames")?.toInt() ?: 60
        val panel = context.resources.displayMetrics
        val (w, h) = RenderPolicy.internalSize(panel.widthPixels, panel.heightPixels, tuning.optDouble("renderScale", 0.75))
        // Crops (top-left x, y from the top, size): the impact point, where the rock lies, and Earth
        // well away from it.
        val impact = SceneConstants.from(tuning).let { c ->
            val q = doubleArrayOf(c.impactWorld[0] * c.focal / -c.impactWorld[2], c.impactWorld[1] * c.focal / -c.impactWorld[2])
            (q[0] * h + w / 2.0).roundToInt() to (h / 2.0 - q[1] * h).roundToInt()
        }
        val crops = mapOf("rock" to intArrayOf(impact.first - 96, impact.second - 96, 192), "earth" to intArrayOf(100, 200, 160))
        val params = SceneParams.fromJson(tuning)
        val events = listOf(Event(0.0, "SCREEN_ON", locked = true), Event(6.0, "UNLOCK"))
        val out = File(context.filesDir, "sequence").apply { deleteRecursively(); mkdirs() }
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(w, h) }
        val surface = Surface(texture)
        val egl = EglCore()
        egl.init()
        egl.attach(surface)
        try {
            val renderer = GlRenderer(context.assets, tuning)
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                val t = t0 + i / fps
                val s = replay(params, events, t)
                renderer.render(Frame(t.toFloat(), s.p.toFloat(), s.meteorT.toFloat(), s.impactT.toFloat(), 0.5f), w, h, preScaled = true)
                pixels.rewind()
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                raw.copyPixelsFromBuffer(pixels)
                val frame = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false) // GL rows run bottom-up
                for ((name, c) in crops) {
                    val crop = Bitmap.createBitmap(frame, c[0], c[1], c[2], c[2])
                    File(out, "%s-%03d.png".format(name, i)).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    crop.recycle()
                }
                raw.recycle()
                frame.recycle()
            }
            renderer.release()
        } finally {
            egl.release()
            surface.release()
            texture.release()
        }
    }
}
