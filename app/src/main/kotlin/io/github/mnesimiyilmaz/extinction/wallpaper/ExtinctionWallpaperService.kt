package io.github.mnesimiyilmaz.extinction.wallpaper

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import io.github.mnesimiyilmaz.extinction.render.GlRenderer
import io.github.mnesimiyilmaz.extinction.render.RenderPolicy
import io.github.mnesimiyilmaz.extinction.scene.SceneDriver
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val ACTION_DEBUG_FPS = "io.github.mnesimiyilmaz.extinction.DEBUG_FPS"
private const val ACTION_DEBUG_SCRUB = "io.github.mnesimiyilmaz.extinction.DEBUG_SCRUB"

class ExtinctionWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ExtinctionEngine()

    inner class ExtinctionEngine : Engine() {
        private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
        private lateinit var loop: RenderLoop
        private var renderScale = 0.75
        private var fixedSize: RenderPolicy.Size? = null
        private var debugRegistered = false
        private val battery by lazy { getSystemService(BatteryManager::class.java) }
        private val mainHandler = Handler(Looper.getMainLooper())

        // Debug builds: the battery's instantaneous current once a second, for power measurements
        // (the shell cannot read the fuel gauge). Units as reported by the device.
        private val powerSample = object : Runnable {
            override fun run() {
                Log.i(TAG, "power: current ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)}")
                mainHandler.postDelayed(this, 1000)
            }
        }

        // Debuggable builds only:
        //   adb shell am broadcast -p io.github.mnesimiyilmaz.extinction -a io.github.mnesimiyilmaz.extinction.DEBUG_FPS --ef fps 15
        //     (0 pauses, -1 restores the tuned caps)
        //   adb shell am broadcast -p io.github.mnesimiyilmaz.extinction -a io.github.mnesimiyilmaz.extinction.DEBUG_SCRUB --ef t 14.6
        //     (holds the canonical sequence still at t seconds, unlock at scrubUnlockAt; -1 resumes)
        private val debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DEBUG_FPS -> {
                        val fps = intent.getFloatExtra("fps", -1f)
                        loop.setFpsOverride(if (fps < 0f) null else fps.toDouble())
                    }
                    ACTION_DEBUG_SCRUB -> {
                        val t = intent.getFloatExtra("t", -1f)
                        loop.post { scrub(if (t < 0f) null else t.toDouble()) }
                    }
                }
            }
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        val locked = keyguard.isKeyguardLocked
                        loop.post { onScreenOn(locked) }
                    }
                    Intent.ACTION_SCREEN_OFF -> loop.post { onScreenOff() }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val tuning = JSONObject(assets.open("config/tuning.json").bufferedReader().use { it.readText() })
            val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            renderScale = tuning.optDouble("renderScale", 0.75).coerceIn(0.1, 1.0)
            loop = RenderLoop(tuning, isPreview, keyguard, debug) { GlRenderer(assets, tuning, profile = debug) }
            if (!isPreview) {
                registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }, RECEIVER_NOT_EXPORTED) // system broadcasts still arrive
                if (debug) {
                    registerReceiver(debugReceiver, IntentFilter().apply {
                        addAction(ACTION_DEBUG_FPS)
                        addAction(ACTION_DEBUG_SCRUB)
                    }, RECEIVER_EXPORTED)
                    debugRegistered = true
                    mainHandler.post(powerSample)
                }
            }
        }

        override fun onDestroy() {
            if (!isPreview) {
                unregisterReceiver(receiver)
                if (debugRegistered) unregisterReceiver(debugReceiver)
                mainHandler.removeCallbacks(powerSample)
            }
            loop.quit()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loop.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (fixedSize == null) {
                val size = RenderPolicy.internalSize(width, height, renderScale)
                if (size != RenderPolicy.Size(width, height) && fixSize(holder, size)) {
                    fixedSize = size
                    return // the surface comes back at the new size
                }
            }
            loop.surfaceChanged(width, height, preScaled = fixedSize == RenderPolicy.Size(width, height))
        }

        // The display hardware scales the wallpaper up for free, so a surface at render size instead
        // of a QHD+ panel's 1440x3120 cuts the final pass and the compositor's read about 3x. Wallpapers
        // may resize their surface only behind a hidden switch (non-SDK, allowed today); if that ever
        // fails, the surface stays full size and the renderer scales as before.
        @SuppressLint("DiscouragedPrivateApi") // deliberate, with the full-size fallback below
        private fun fixSize(holder: SurfaceHolder, size: RenderPolicy.Size): Boolean = try {
            if (!isPreview) {
                Engine::class.java.getDeclaredMethod("setFixedSizeAllowed", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(this, true)
            }
            holder.setFixedSize(size.width, size.height)
            true
        } catch (e: Exception) {
            Log.w(TAG, "fixed-size surface unavailable; rendering at full size", e)
            false
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            loop.redrawNow()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            loop.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) = loop.setVisible(visible)

        override fun onOffsetsChanged(x: Float, y: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) =
            loop.setOffset(x) // inert on One UI (always 0.5), useful on other launchers

        override fun onCommand(action: String, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == SceneDriver.CMD_WAKING_UP) {
                val locked = keyguard.isKeyguardLocked
                loop.post { onScreenOn(locked) }
            } else {
                loop.post { onCommand(action) }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }
    }
}
