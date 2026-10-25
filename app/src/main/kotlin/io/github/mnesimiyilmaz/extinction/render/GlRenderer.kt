package io.github.mnesimiyilmaz.extinction.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Per-frame scene parameters (docs/architecture.md, "Shader contract").
data class Frame(val time: Float, val meteorP: Float, val meteorT: Float, val impactT: Float, val offset: Float)

// The GLES3 render pipeline (docs/architecture.md). Create and use on the GL thread.
// The per-frame path allocates nothing: tuning uniforms are uploaded once per program (uniform
// values are program state), and the frame uniforms go through cached locations.
// profile: time every pass on the GPU (EXT_disjoint_timer_query), read with gpuReport().
class GlRenderer(private val assets: AssetManager, tuning: JSONObject, profile: Boolean = false) {
    private enum class Blend { NONE, PREMULTIPLIED, ADD }
    private class Program(val id: Int) {
        val locations = HashMap<String, Int>()
        val frameLocations = IntArray(FRAME_UNIFORMS.size)
    }
    private class Target(val tex: Int, val fbo: Int, val width: Int, val height: Int)
    private class Targets(val w: Int, val h: Int, val background: Target, val scene: Target, val bloom: List<Target>) {
        val all get() = listOf(background, scene) + bloom
    }

    private val shaders = ShaderSource.FILES.associateWith { name ->
        assets.open("shaders/$name").bufferedReader().use { it.readText() }
    }
    private val emptyVao = genVertexArray()
    private val quadVao = genVertexArray()
    private val background: Program
    private val fx: Program
    private val chunksProgram: Program
    private val meteor: Program
    private val rockGeo: Program
    private val flash: Program
    private val particles: Program
    private val bloomProgram: Program
    private val final: Program
    private val programs: List<Program>
    private val earthDay: Int
    private val earthAux: Int
    private val rockCache: Target
    private val rockNormal: Int
    private val gpu: GpuTimer?
    private var targets: Targets? = null
    private var bgDirty = true
    private var bgTime = Float.NEGATIVE_INFINITY
    private var bgOffset = Float.NaN
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // Tuning values the CPU side reads every frame, cached by setTuning.
    private var renderScale = 0.75
    private var bgRefreshHz = 10.0
    private var shockTime = 5.0
    private var chunkCount = 0
    private val particleCounts = IntArray(3) // fragments, ejecta, embers (uKind 0..2)
    private var sway = 0.0
    private var shake = 0.0
    private val cameraShift = FloatArray(2) // per frame, see SceneConstants.cameraShift
    private val bgCameraShift = FloatArray(2) // cameraShift at the last background render

    init {
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        check("GL_EXT_color_buffer_float" in ext || "GL_EXT_color_buffer_half_float" in ext) {
            "RGBA16F render targets are not supported"
        }
        GLES30.glBindVertexArray(quadVao) // particle and chunk quad corners in attribute 0
        val buffer = IntArray(1)
        GLES20.glGenBuffers(1, buffer, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer[0])
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val data = ByteBuffer.allocateDirect(corners.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(corners)
        data.position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, corners.size * 4, data, GLES20.GL_STATIC_DRAW)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)

        fun build(vs: String, fs: String) = compileProgram(
            ShaderSource.preprocess(shaders.getValue(vs), shaders),
            ShaderSource.preprocess(shaders.getValue(fs), shaders),
            "$vs + $fs",
        )
        background = build("fullscreen.vert", "background.frag")
        fx = build("fullscreen.vert", "fx.frag")
        chunksProgram = build("chunks.vert", "chunks.frag")
        meteor = build("meteor.vert", "meteor.frag")
        rockGeo = build("fullscreen.vert", "rockgeo.frag")
        flash = build("fullscreen.vert", "flash.frag")
        particles = build("particles.vert", "particles.frag")
        bloomProgram = build("fullscreen.vert", "bloom.frag")
        final = build("fullscreen.vert", "final.frag")
        programs = listOf(background, fx, chunksProgram, meteor, rockGeo, flash, particles, bloomProgram, final)
        for (p in programs) {
            FRAME_UNIFORMS.forEachIndexed { i, name -> p.frameLocations[i] = location(p, name) }
        }
        earthDay = imageTexture("earth/day.jpg", srgb = true)
        earthAux = imageTexture("earth/aux.jpg", srgb = false)
        // Texture units are fixed per sampler, so they are program state too.
        sampler(background, "uEarthDay", 0)
        sampler(background, "uEarthAux", 1)
        sampler(meteor, "uRockPos", 0)
        sampler(meteor, "uRockNormal", 1)
        sampler(bloomProgram, "uSource", 0)
        sampler(final, "uBackground", 0)
        sampler(final, "uScene", 1)
        sampler(final, "uBloom", 2)
        val cache = createRockCache()
        rockCache = cache.first
        rockNormal = cache.second
        setTuning(tuning)
        gpu = if (profile && GpuTimer.supported(ext)) GpuTimer(PASSES) else null
    }

    fun setTuning(next: JSONObject) {
        val list = TuningUniforms.from(next) + SceneConstants.uniforms(next)
        for (p in programs) {
            GLES20.glUseProgram(p.id)
            setUniforms(p, list)
        }
        renderScale = next.optDouble("renderScale", 0.75).coerceIn(0.1, 1.0)
        bgRefreshHz = next.optDouble("bgRefreshHz", 10.0)
        shockTime = next.optDouble("shockTime", 5.0)
        sway = next.optDouble("sway", 0.0)
        shake = next.optDouble("shake", 0.0)
        chunkCount = next.optDouble("chunkCount", 0.0).roundToInt().coerceIn(0, MAX_CHUNKS)
        PARTICLE_KEYS.forEachIndexed { kind, key -> particleCounts[kind] = next.optDouble(key, 0.0).roundToInt().coerceAtLeast(0) }
        bgDirty = true
    }

    // Mean GPU ms per pass since the last call; null when not profiling or nothing completed.
    fun gpuReport(): String? = gpu?.times?.report()

    // preScaled: the surface is already at render size (see ExtinctionEngine.fixSize), so the targets
    // match it; otherwise they are sized from it.
    fun render(frame: Frame, width: Int, height: Int, preScaled: Boolean = false) {
        surfaceWidth = width
        surfaceHeight = height
        SceneConstants.cameraShift(sway, shake, frame.time.toDouble(), frame.offset.toDouble(), frame.impactT.toDouble(), cameraShift)
        val t = ensureTargets(if (preScaled) RenderPolicy.Size(width, height) else RenderPolicy.internalSize(width, height, renderScale))
        val due = RenderPolicy.backgroundDue(frame.time.toDouble(), bgTime.toDouble(), frame.impactT.toDouble(), bgRefreshHz, shockTime)
        if (bgDirty || due || frame.offset != bgOffset) {
            begin(P_BACKGROUND, background, t.background, frame, Blend.NONE)
            bindTexture(0, earthDay)
            bindTexture(1, earthAux)
            fullscreen()
            // The buried rock is as static as the Earth: its geometry is cached on the same schedule.
            if (frame.impactT >= 0f) {
                begin(-1, rockGeo, rockCache, frame, Blend.NONE)
                GLES20.glUniform2f(rockGeo.frameLocations[0], t.w.toFloat(), t.h.toFloat()) // the scene's
                fullscreen()
            }
            bgTime = frame.time
            bgOffset = frame.offset
            bgCameraShift[0] = cameraShift[0]
            bgCameraShift[1] = cameraShift[1]
            bgDirty = false
        }
        // fx (the impact) writes every pixel, so the scene target needs no clear. Blended over it in
        // order: the chunks behind the meteor, the meteor, the chunks in front of it (after impact),
        // the particles, and the flash over everything.
        begin(P_FX, fx, t.scene, frame, Blend.NONE)
        fullscreen()
        val chunksOn = chunkCount > 0 && frame.meteorT >= 0f
        if (chunksOn) chunks(P_CHUNKS, layer = 0f, frame, t)
        begin(P_METEOR, meteor, t.scene, frame, Blend.PREMULTIPLIED)
        bindTexture(0, rockCache.tex)
        bindTexture(1, rockNormal)
        fullscreen()
        if (chunksOn && frame.impactT >= 0f) chunks(P_CHUNKS_FRONT, layer = 1f, frame, t)
        begin(P_PARTICLES, particles, t.scene, frame, Blend.PREMULTIPLIED)
        for (kind in particleCounts.indices) {
            if (particleCounts[kind] == 0) continue
            GLES20.glUniform1f(location(particles, "uKind"), kind.toFloat())
            quads(particleCounts[kind])
        }
        if (frame.impactT >= 0f && frame.impactT < FLASH_SECONDS) {
            begin(P_FLASH, flash, t.scene, frame, Blend.ADD)
            fullscreen()
        }
        bloom(t, frame)
        begin(P_FINAL, final, null, frame, Blend.NONE)
        GLES20.glUniform2f(location(final, "uBgCameraShift"), bgCameraShift[0], bgCameraShift[1])
        bindTexture(0, t.background.tex)
        bindTexture(1, t.scene.tex)
        bindTexture(2, t.bloom[0].tex)
        fullscreen()
        gpu?.end()
        gpu?.frameDone()
    }

    fun release() {
        gpu?.release()
    }

    private fun chunks(timed: Int, layer: Float, frame: Frame, t: Targets) {
        begin(timed, chunksProgram, t.scene, frame, Blend.PREMULTIPLIED)
        GLES20.glUniform1f(location(chunksProgram, "uChunkLayer"), layer)
        quads(chunkCount * 2) // rock + landing flash
    }

    // Downsample scene → bloom[0..3], then upsample back, adding each level into the larger one.
    private fun bloom(t: Targets, frame: Frame) {
        var src = t.scene
        for (i in t.bloom.indices) {
            val dst = t.bloom[i]
            begin(if (i == 0) P_BLOOM else -1, bloomProgram, dst, frame, Blend.NONE)
            GLES20.glUniform2f(location(bloomProgram, "uTexel"), 1f / src.width, 1f / src.height)
            GLES20.glUniform1f(location(bloomProgram, "uMode"), 0f)
            GLES20.glUniform1f(location(bloomProgram, "uPrefilter"), if (i == 0) 1f else 0f)
            bindTexture(0, src.tex)
            fullscreen()
            src = dst
        }
        for (i in t.bloom.size - 2 downTo 0) {
            val s = t.bloom[i + 1]
            begin(-1, bloomProgram, t.bloom[i], frame, Blend.ADD)
            GLES20.glUniform2f(location(bloomProgram, "uTexel"), 1f / s.width, 1f / s.height)
            GLES20.glUniform1f(location(bloomProgram, "uMode"), 1f)
            bindTexture(0, s.tex)
            fullscreen()
        }
    }

    private fun ensureTargets(size: RenderPolicy.Size): Targets {
        val w = size.width
        val h = size.height
        targets?.let { if (it.w == w && it.h == h) return it; it.all.forEach(::deleteTarget) }
        val bloom = ArrayList<Target>()
        var bw = w
        var bh = h
        repeat(4) {
            bw = max(1, bw / 2)
            bh = max(1, bh / 2)
            bloom += createTarget(bw, bh)
        }
        bgDirty = true
        return Targets(w, h, createTarget(w, h), createTarget(w, h), bloom).also { targets = it }
    }

    // Starts a pass: target, program, blending and the frame uniforms. timed: index into PASSES of
    // the GPU timing slot this pass opens (the previous slot closes), or -1 to stay in the current one.
    private fun begin(timed: Int, program: Program, target: Target?, frame: Frame, blend: Blend) {
        if (gpu != null && timed >= 0) {
            gpu.end()
            gpu.begin(timed)
        }
        val w = target?.width ?: surfaceWidth
        val h = target?.height ?: surfaceHeight
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target?.fbo ?: 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program.id)
        when (blend) {
            Blend.NONE -> GLES20.glDisable(GLES20.GL_BLEND)
            Blend.PREMULTIPLIED -> {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
            Blend.ADD -> {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            }
        }
        val loc = program.frameLocations
        if (loc[0] >= 0) GLES20.glUniform2f(loc[0], w.toFloat(), h.toFloat())
        if (loc[1] >= 0) GLES20.glUniform1f(loc[1], frame.time)
        if (loc[2] >= 0) GLES20.glUniform1f(loc[2], frame.offset)
        if (loc[3] >= 0) GLES20.glUniform1f(loc[3], frame.meteorP)
        if (loc[4] >= 0) GLES20.glUniform1f(loc[4], frame.meteorT)
        if (loc[5] >= 0) GLES20.glUniform1f(loc[5], frame.impactT)
        if (loc[6] >= 0) GLES20.glUniform2f(loc[6], cameraShift[0], cameraShift[1])
    }

    private fun fullscreen() {
        GLES30.glBindVertexArray(emptyVao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    private fun quads(instances: Int) {
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 4, instances)
    }

    private fun bindTexture(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    private fun sampler(p: Program, name: String, unit: Int) {
        val loc = location(p, name)
        if (loc < 0) return
        GLES20.glUseProgram(p.id)
        GLES20.glUniform1i(loc, unit)
    }

    private fun location(p: Program, name: String) = p.locations.getOrPut(name) { GLES20.glGetUniformLocation(p.id, name) }

    // Uniforms a shader does not declare are skipped silently. The program must be in use.
    private fun setUniforms(p: Program, list: List<Uniform>) {
        for (u in list) {
            val loc = location(p, u.name)
            if (loc < 0) continue
            when (u.values.size) {
                1 -> GLES20.glUniform1fv(loc, 1, u.values, 0)
                2 -> GLES20.glUniform2fv(loc, 1, u.values, 0)
                3 -> GLES20.glUniform3fv(loc, 1, u.values, 0)
                4 -> GLES20.glUniform4fv(loc, 1, u.values, 0)
            }
        }
    }

    private fun compileProgram(vs: String, fs: String, label: String): Program {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                val kind = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
                throw IllegalStateException("$label: $kind shader failed to compile:\n${ShaderSource.annotateLog(log, src)}")
            }
            return s
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "$label: program failed to link:\n${GLES20.glGetProgramInfoLog(id)}" }
        return Program(id)
    }

    private fun createTarget(w: Int, h: Int): Target {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        clampLinear(mipmapped = false)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer ${w}x$h incomplete: 0x${Integer.toHexString(status)}" }
        return Target(tex[0], fbo[0], w, h)
    }

    // Buried rock geometry cache (bodies.glsl): rock-space hit point and normal + tone, two RGBA16F
    // textures behind one framebuffer. Fixed size, one texel per render pixel.
    private fun createRockCache(): Pair<Target, Int> {
        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        for (id in tex) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, ROCK_CACHE, ROCK_CACHE, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            clampLinear(mipmapped = false) // sampled between texels (meteor.frag)
        }
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES20.GL_TEXTURE_2D, tex[1], 0)
        GLES30.glDrawBuffers(2, intArrayOf(GLES20.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "rock cache incomplete: 0x${Integer.toHexString(status)}" }
        return Target(tex[0], fbo[0], ROCK_CACHE, ROCK_CACHE) to tex[1]
    }

    private fun deleteTarget(t: Target) {
        GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
    }

    // Raw pixels: no premultiplication (aux.jpg channels are data); sRGB decoded by the GPU for day.jpg.
    private fun imageTexture(path: String, srgb: Boolean): Int {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("cannot decode $path")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        // Not GLUtils.texImage2D: it passes a sized internal format through as the pixel format too,
        // which GL rejects (INVALID_ENUM) and leaves the texture undefined. Allocate, then upload as RGBA.
        val levels = 32 - Integer.numberOfLeadingZeros(max(bitmap.width, bitmap.height))
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, levels, if (srgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8, bitmap.width, bitmap.height)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        bitmap.recycle()
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        checkGl("upload $path")
        clampLinear(mipmapped = true)
        // The surface is seen at grazing angles toward the limb: anisotropic filtering keeps it sharp.
        if ("GL_EXT_texture_filter_anisotropic" in (GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "")) {
            val maxAniso = FloatArray(1)
            GLES20.glGetFloatv(MAX_TEXTURE_MAX_ANISOTROPY, maxAniso, 0)
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY, min(8f, maxAniso[0]))
        }
        return tex[0]
    }

    private fun clampLinear(mipmapped: Boolean) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmapped) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun checkGl(label: String) {
        val err = GLES20.glGetError()
        check(err == GLES20.GL_NO_ERROR) { "$label: GL error 0x${Integer.toHexString(err)}" }
    }

    private fun genVertexArray(): Int {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        return ids[0]
    }

    private companion object {
        const val MAX_CHUNKS = 24 // bodies.glsl
        const val ROCK_CACHE = 256 // bodies.glsl
        val FRAME_UNIFORMS = arrayOf("uResolution", "uTime", "uOffset", "uMeteorP", "uMeteorT", "uImpactT", "uCameraShift")
        val PARTICLE_KEYS = arrayOf("fragmentCount", "ejectaCount", "emberCount")
        val PASSES = listOf("bg", "fx", "chunks", "meteor", "front", "particles", "flash", "bloom", "final")
        const val P_BACKGROUND = 0
        const val P_FX = 1
        const val P_CHUNKS = 2
        const val P_METEOR = 3
        const val P_CHUNKS_FRONT = 4
        const val P_PARTICLES = 5
        const val P_FLASH = 6
        const val P_BLOOM = 7
        const val P_FINAL = 8
        const val FLASH_SECONDS = 5f // flash.frag: below 1e-4 after
        const val TEXTURE_MAX_ANISOTROPY = 0x84FE // EXT_texture_filter_anisotropic
        const val MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF
    }
}
