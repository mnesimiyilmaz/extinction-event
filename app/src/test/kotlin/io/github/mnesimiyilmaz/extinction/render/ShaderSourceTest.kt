package io.github.mnesimiyilmaz.extinction.render

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ShaderSourceTest {
    @Test
    fun preprocessReplacesIncludeLines() {
        val out = ShaderSource.preprocess("#version 300 es\n//#include common.glsl\nvoid main() {}\n", mapOf("common.glsl" to "float k = 1.0;"))
        assertEquals("#version 300 es\nfloat k = 1.0;\nvoid main() {}\n", out)
    }

    @Test
    fun preprocessToleratesIndentationTrailingSpacesAndCrlf() {
        assertEquals("A\n", ShaderSource.preprocess("  //#include a.glsl  \n", mapOf("a.glsl" to "A")))
        assertEquals("A\nx", ShaderSource.preprocess("//#include a.glsl\r\nx", mapOf("a.glsl" to "A")))
    }

    @Test
    fun preprocessIsNotRecursive() {
        assertEquals("//#include b.glsl", ShaderSource.preprocess("//#include a.glsl", mapOf("a.glsl" to "//#include b.glsl")))
    }

    @Test
    fun preprocessIgnoresIncludesNotAloneOnTheirLine() {
        val src = "x = 1; //#include a.glsl"
        assertEquals(src, ShaderSource.preprocess(src, mapOf("a.glsl" to "A")))
    }

    @Test
    fun preprocessRejectsUnknownFiles() {
        try {
            ShaderSource.preprocess("//#include nope.glsl", emptyMap())
            fail("expected an exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown file: nope.glsl"))
        }
    }

    @Test
    fun annotateLogShowsTheOffendingLineWithContext() {
        val out = ShaderSource.annotateLog("ERROR: 0:4: 'd' : undeclared identifier\n", "a\nb\nc\nd\ne\nf")
        assertTrue(out.startsWith("ERROR: 0:4:"))
        assertTrue(out, out.contains(">    4 | d"))
        assertTrue(out, out.contains("     2 | b"))
        assertTrue(out, out.contains("     6 | f"))
        assertTrue(out, !out.contains("| a"))
    }

    @Test
    fun tuningUniformsMapNumbersVectorsAndColours() {
        val list = TuningUniforms.from(JSONObject("""{"pHold":0.85,"sunDir":[0.3,0.5,-0.81],"impactQ":[-0.0185,0.0298],"fireColor":"#808080"}"""))
        val byName = list.associateBy { it.name }
        assertEquals(setOf("uPHold", "uSunDir", "uImpactQ", "uFireColor"), byName.keys)
        assertEquals(listOf(0.85f), byName.getValue("uPHold").values.toList())
        assertEquals(listOf(0.3f, 0.5f, -0.81f), byName.getValue("uSunDir").values.toList())
        assertEquals(2, byName.getValue("uImpactQ").values.size)
        for (c in byName.getValue("uFireColor").values) assertTrue("got $c", abs(c - 0.2158605f) < 1e-6f)
    }

    @Test
    fun tuningUniformsSkipNonUniforms() {
        val list = TuningUniforms.from(JSONObject("""{"note":"hello","flag":true,"five":[1,2,3,4,5],"one":[1],"obj":{"a":1},"short":"#fff"}"""))
        assertEquals(emptyList<Uniform>(), list)
    }

    @Test
    fun hexToLinearMapsSrgbEndpointsExactly() {
        assertEquals(listOf(0f, 0f, 0f), TuningUniforms.hexToLinear("#000000").toList())
        assertEquals(listOf(1f, 1f, 1f), TuningUniforms.hexToLinear("#ffffff").toList())
    }

    @Test
    fun everyShippedTuningEntryMapsToAUniform() {
        val tuning = JSONObject(File("src/main/assets/config/tuning.json").readText())
        assertEquals(tuning.length(), TuningUniforms.from(tuning).size)
    }

    @Test
    fun fileListMatchesTheShadersDirectory() {
        val onDisk = File("src/main/assets/shaders").list()!!.toSet()
        assertEquals(onDisk, ShaderSource.FILES.toSet())
    }
}
