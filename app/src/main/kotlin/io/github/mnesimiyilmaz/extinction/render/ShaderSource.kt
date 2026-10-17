package io.github.mnesimiyilmaz.extinction.render

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

// Shader includes and tuning → uniforms. Pure, so it is tested on the JVM.
object ShaderSource {
    // Everything under assets/shaders/ (ShaderSourceTest keeps the list in step with the directory).
    val FILES = listOf(
        "common.glsl", "bodies.glsl", "fullscreen.vert", "background.frag", "fx.frag",
        "chunks.vert", "chunks.frag", "meteor.vert", "meteor.frag", "rockgeo.frag", "flash.frag", "particles.vert", "particles.frag", "bloom.frag", "final.frag",
    )

    // UNIX_LINES: only \n ends a line, so a CRLF's \r is consumed by [ \t\r]* rather than left behind.
    private val INCLUDE = Regex("""^[ \t]*//#include[ \t]+(\S+)[ \t\r]*$""", setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES))

    // Replaces `//#include <name>` lines with files[name]. Not recursive: included text is not rescanned.
    fun preprocess(source: String, files: Map<String, String>): String =
        INCLUDE.replace(source) { m ->
            val name = m.groupValues[1]
            files[name] ?: throw IllegalArgumentException("#include of unknown file: $name")
        }

    // Appends the offending lines to a GLSL info log; its line numbers refer to the preprocessed source.
    fun annotateLog(log: String, source: String): String {
        val lines = source.split('\n')
        val out = mutableListOf(log.trim())
        val seen = HashSet<Int>()
        for (m in Regex("""ERROR: \d+:(\d+):""").findAll(log)) {
            val n = m.groupValues[1].toInt()
            if (!seen.add(n)) continue
            for (i in maxOf(1, n - 2)..minOf(lines.size, n + 2)) {
                out += "${if (i == n) '>' else ' '} ${i.toString().padStart(4)} | ${lines[i - 1]}"
            }
        }
        return out.joinToString("\n")
    }
}

class Uniform(val name: String, val values: FloatArray)

object TuningUniforms {
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    // tuning.json → uniforms: key `fooBar` → `uFooBar`; number → 1, 2–4 numbers → 2–4 floats,
    // '#rrggbb' → linear-space vec3. Anything else is not a uniform and is skipped.
    fun from(tuning: JSONObject): List<Uniform> {
        val out = ArrayList<Uniform>()
        for (key in tuning.keys()) {
            val name = "u" + key.replaceFirstChar { it.uppercaseChar() }
            when (val v = tuning.get(key)) {
                is Number -> out += Uniform(name, floatArrayOf(v.toFloat()))
                is JSONArray -> {
                    val nums = (0 until v.length()).map { v.get(it) }
                    if (nums.size in 2..4 && nums.all { it is Number }) {
                        out += Uniform(name, FloatArray(nums.size) { (nums[it] as Number).toFloat() })
                    }
                }
                is String -> if (HEX.matches(v)) out += Uniform(name, hexToLinear(v))
            }
        }
        return out
    }

    fun hexToLinear(hex: String): FloatArray = FloatArray(3) { i ->
        val c = hex.substring(1 + 2 * i, 3 + 2 * i).toInt(16) / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }
}
