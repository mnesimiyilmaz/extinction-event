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

// Renders fixed scene moments off screen, exactly as the wallpaper does on this device, and writes
// them as PNGs to the app's files/dump/ — for before/after comparisons of a change
// (tools/scene-dump.sh runs it and pulls them; tools/compare-shots.py compares two dumps). Opt-in:
// -e dump true.
@RunWith(AndroidJUnit4::class)
class SceneDump {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tuning = JSONObject(context.assets.open("config/tuning.json").bufferedReader().use { it.readText() })

    @Test
    fun scenes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dump") == "true")
        val panel = context.resources.displayMetrics
        val size = RenderPolicy.internalSize(panel.widthPixels, panel.heightPixels, tuning.optDouble("renderScale", 0.75))
        val (w, h) = size
        val params = SceneParams.fromJson(tuning)
        val out = File(context.filesDir, "dump").apply { deleteRecursively(); mkdirs() }
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(w, h) }
        val surface = Surface(texture)
        val egl = EglCore()
        egl.init()
        egl.attach(surface)
        try {
            val renderer = GlRenderer(context.assets, tuning)
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for ((t, unlockAt) in SCENES) {
                val events = listOf(Event(0.0, "SCREEN_ON", locked = true), Event(unlockAt, "UNLOCK"))
                val s = replay(params, events, t)
                renderer.render(Frame(t.toFloat(), s.p.toFloat(), s.meteorT.toFloat(), s.impactT.toFloat(), 0.5f), w, h, preScaled = true)
                pixels.rewind()
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                raw.copyPixelsFromBuffer(pixels)
                val bitmap = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false) // GL rows run bottom-up
                val name = if (unlockAt == 6.0) "t$t" else "t$t-unlock$unlockAt"
                File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                raw.recycle()
                bitmap.recycle()
            }
            renderer.release()
        } finally {
            egl.release()
            surface.release()
            texture.release()
        }
    }

    private companion object {
        // (scene time, unlock time): approach, the lock-screen hold, dive, impact, and the aftermath
        // up to its settled steady state.
        val SCENES = listOf(
            2.08 to 6.0, 20.0 to 60.0, 6.28 to 6.0, 6.85 to 6.0, 8.05 to 6.0,
            14.05 to 6.0, 25.0 to 6.0, 40.0 to 6.0, 61.3 to 6.0, 130.0 to 6.0,
        )
    }
}
