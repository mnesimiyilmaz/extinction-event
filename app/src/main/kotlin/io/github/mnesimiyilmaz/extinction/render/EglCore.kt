package io.github.mnesimiyilmaz.extinction.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

// One GLES 3 context for the engine's lifetime; window surfaces come and go with the SurfaceHolder.
class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    val initialized get() = context != EGL14.EGL_NO_CONTEXT

    fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed: ${error()}" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no GLES3 EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${error()}" }
    }

    // `surface` is the SurfaceHolder's Surface.
    fun attach(window: Any) {
        surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${error()}" }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            val err = error()
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
            throw IllegalStateException("eglMakeCurrent failed: $err")
        }
    }

    fun detach() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

    // Call right after a failed swap: the error is that swap's.
    fun contextLost(): Boolean = EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST

    // No eglTerminate: the default display is shared with every other engine in this process (the
    // picker preview runs beside the active wallpaper), and terminating it is never required.
    fun release() {
        detach()
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun error() = "0x" + Integer.toHexString(EGL14.eglGetError())
}
