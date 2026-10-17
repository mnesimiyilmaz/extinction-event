package io.github.mnesimiyilmaz.extinction.render

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// Static checks on the shared GLSL that no driver error would catch.
class ShaderLintTest {
    private val dir = File("src/main/assets/shaders")
    private val sources = dir.listFiles()!!.associate { it.name to it.readText() }

    // GLSL ES 3.00 §8.2: pow(x, y) is undefined for x < 0. Desktop drivers turn pow(x, 2.0) into x*x,
    // Android drivers may compute exp2(y*log2(x)) and return NaN. Squares must use sq() instead.
    @Test
    fun shadersNeverSquareWithPow() {
        val offenders = sources.flatMap { (name, src) ->
            Regex("""\bpow\(""").findAll(src).mapNotNull { m ->
                val args = callArgs(src, m.range.last)
                if (args.getOrNull(1) == "2.0") "$name: pow(${args.joinToString(", ")})" else null
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    // The same hazard in another shape: 1.0 - x dips below zero when x overshoots 1 by a rounding
    // error (a dot product of unit vectors, for one). Cube or square such terms explicitly.
    @Test
    fun shadersNeverPowAOneMinusBase() {
        val offenders = sources.flatMap { (name, src) ->
            Regex("""\bpow\(""").findAll(src).mapNotNull { m ->
                val base = callArgs(src, m.range.last).first()
                if (base.startsWith("1.0 -")) "$name: pow($base, …)" else null
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    // GLSL ES 3.00 §8.9: implicit derivatives are undefined in non-uniform control flow, and the Earth
    // is shaded inside `if (cover > 0.0)`. Its mipmapped textures must take explicit gradients.
    @Test
    fun backgroundSamplesTheEarthWithExplicitGradientsOnly() {
        assertEquals(0, Regex("""\btexture\(""").findAll(sources.getValue("background.frag")).count())
    }

    @Test
    fun everyShaderUniformIsSuppliedByTheRendererOrTuning() {
        val tuning = JSONObject(File("src/main/assets/config/tuning.json").readText())
        val supplied = RENDERER + TuningUniforms.from(tuning).map { it.name } + SceneConstants.uniforms(tuning).map { it.name }
        val missing = sources.flatMap { (name, src) ->
            Regex("""(?m)^\s*uniform\s+\w+\s+(\w+)\s*;""").findAll(src)
                .map { it.groupValues[1] }.filter { it !in supplied }.map { "$name: $it" }.toList()
        }
        assertEquals(emptyList<String>(), missing)
    }

    // Arguments of the call whose '(' is at index `open`, split at top-level commas.
    private fun callArgs(src: String, open: Int): List<String> {
        val args = ArrayList<String>()
        var depth = 0
        var start = open + 1
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) {
                    args += src.substring(start, i).trim()
                    return args
                }
                ',' -> if (depth == 1) {
                    args += src.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        error("unbalanced parentheses")
    }

    private companion object {
        // Set by GlRenderer itself (frame values, samplers, per-pass parameters).
        val RENDERER = setOf(
            "uResolution", "uTime", "uOffset", "uMeteorP", "uMeteorT", "uImpactT", "uCameraShift",
            "uBackground", "uScene", "uBloom", "uSource", "uTexel", "uMode", "uPrefilter", "uKind",
            "uEarthDay", "uEarthAux", "uRockPos", "uRockNormal", "uChunkLayer", "uBgCameraShift",
        )
    }
}
