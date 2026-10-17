package io.github.mnesimiyilmaz.extinction

import android.app.KeyguardManager
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mnesimiyilmaz.extinction.render.EglCore
import io.github.mnesimiyilmaz.extinction.render.Frame
import io.github.mnesimiyilmaz.extinction.render.GlRenderer
import io.github.mnesimiyilmaz.extinction.wallpaper.RenderLoop
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Runs on the device: real driver, real shader compiler. tools/device-test.sh GlDeviceTest
@RunWith(AndroidJUnit4::class)
class GlDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tuning = JSONObject(context.assets.open("config/tuning.json").bufferedReader().use { it.readText() })
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface

    @Before
    fun setUp() {
        texture = SurfaceTexture(false).apply { setDefaultBufferSize(W, H) }
        surface = Surface(texture)
    }

    @After
    fun tearDown() {
        surface.release()
        texture.release()
    }

    @Test
    fun earthTexturesUploadAndShowOnTheDaySide() {
        val egl = EglCore()
        egl.init()
        egl.attach(surface)
        try {
            val renderer = GlRenderer(context.assets, tuning)
            // Screen just on, nothing launched yet: only the Earth and the sky are drawn.
            renderer.render(Frame(time = 1f, meteorP = 0f, meteorT = -1f, impactT = -1f, offset = 0.5f), W, H)
            assertEquals("GL error", GLES20.GL_NO_ERROR, GLES20.glGetError())

            // The day side shows clouds and sea at (0.2, 0.18) from the top-left (mean RGB ≈ 112, 99, 139).
            val x = (0.2f * W).toInt() - 10
            val y = ((1f - 0.18f) * H).toInt() - 10
            val pixels = ByteBuffer.allocateDirect(20 * 20 * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(x, y, 20, 20, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            var sum = 0
            for (i in 0 until 20 * 20) {
                for (c in 0 until 3) sum += pixels.get(i * 4 + c).toInt() and 0xFF
            }
            val mean = sum / (20 * 20 * 3)
            assertTrue("day side too dark (mean $mean): Earth textures missing?", mean > 60)
        } finally {
            egl.release()
        }
    }

    @Test
    fun gpuTimerReportsEveryPass() {
        val egl = EglCore()
        egl.init()
        egl.attach(surface)
        try {
            val renderer = GlRenderer(context.assets, tuning, profile = true)
            repeat(8) {
                renderer.render(Frame(1f + it / 60f, 0.3f, 1f, -1f, 0.5f), W, H)
                GLES20.glFinish() // results are collected a few frames late; make sure they are ready
            }
            val report = renderer.gpuReport()
            assertTrue("no GPU timings: $report", report != null && report.startsWith("gpu ms/frame: bg "))
            renderer.release()
        } finally {
            egl.release()
        }
    }

    // A GL setup failure must leave the process alive; the system would otherwise restart and crash-loop it.
    @Test
    fun renderLoopSurvivesRendererSetupFailure() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val loop = RenderLoop(tuning, preview = true, keyguard, debug = true) {
            throw IllegalStateException("shader failed to compile (simulated)")
        }
        try {
            loop.surfaceCreated(surface)
            assertTrue("GL thread died", loop.awaitIdle())
            assertTrue("failure not recorded", loop.failed)
        } finally {
            loop.quit()
        }
    }

    private fun RenderLoop.awaitIdle(): Boolean {
        val done = CountDownLatch(1)
        post { done.countDown() }
        return done.await(5, TimeUnit.SECONDS)
    }

    private companion object {
        const val W = 540
        const val H = 1170
    }
}
