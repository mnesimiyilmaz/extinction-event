package io.github.mnesimiyilmaz.extinction.render

import android.opengl.GLES20
import android.opengl.GLES30

// Mean GPU time per pass over a reporting window.
class PassTimes(private val names: List<String>) {
    private val sums = LongArray(names.size)
    private var frames = 0

    fun add(pass: Int, ns: Long) {
        sums[pass] += ns
    }

    fun frameDone() {
        frames++
    }

    // null when no frame has completed since the last report.
    fun report(): String? {
        if (frames == 0) return null
        val parts = names.indices.joinToString(", ") { "%s %.2f".format(names[it], sums[it] / 1e6 / frames) }
        val line = "gpu ms/frame: $parts, total %.2f ($frames frames)".format(sums.sum() / 1e6 / frames)
        sums.fill(0)
        frames = 0
        return line
    }
}

// Per-pass GPU timing with EXT_disjoint_timer_query. Results are read a few frames late so the CPU
// never waits on the GPU; windows the driver flags as disjoint (clock change, preemption) are dropped.
// GL thread only.
class GpuTimer(private val names: List<String>) {
    val times = PassTimes(names)
    private val queries = IntArray(DEPTH * names.size)
    private val used = BooleanArray(queries.size)
    private val value = IntArray(1)
    private var frame = 0
    private var active = -1

    init {
        GLES30.glGenQueries(queries.size, queries, 0)
    }

    fun begin(pass: Int) {
        active = slot(frame, pass)
        GLES30.glBeginQuery(TIME_ELAPSED, queries[active])
    }

    fun end() {
        if (active < 0) return
        GLES30.glEndQuery(TIME_ELAPSED)
        used[active] = true
        active = -1
    }

    // Call once per frame after its last pass: collects the frame issued DEPTH - 1 frames ago.
    fun frameDone() {
        frame = (frame + 1) % DEPTH
        GLES20.glGetIntegerv(GPU_DISJOINT, value, 0)
        val disjoint = value[0] != 0
        var any = false
        for (pass in names.indices) {
            val q = slot(frame, pass) // the oldest frame: the next to be overwritten
            if (!used[q]) continue
            used[q] = false
            GLES30.glGetQueryObjectuiv(queries[q], GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
            if (value[0] == 0 || disjoint) continue
            GLES30.glGetQueryObjectuiv(queries[q], GLES30.GL_QUERY_RESULT, value, 0)
            times.add(pass, value[0].toLong() and 0xFFFFFFFFL)
            any = true
        }
        if (any) times.frameDone()
    }

    fun release() {
        GLES30.glDeleteQueries(queries.size, queries, 0)
    }

    private fun slot(frame: Int, pass: Int) = frame * names.size + pass

    companion object {
        private const val DEPTH = 4
        private const val TIME_ELAPSED = 0x88BF // GL_TIME_ELAPSED_EXT
        private const val GPU_DISJOINT = 0x8FBB // GL_GPU_DISJOINT_EXT

        fun supported(extensions: String) = "GL_EXT_disjoint_timer_query" in extensions
    }
}
