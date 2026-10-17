package io.github.mnesimiyilmaz.extinction.render

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

// Checks against src/test/resources/scene-constants.json: reference values for the shipped tuning,
// computed independently from the same formulas in double precision.
class SceneConstantsTest {
    private val fixture = JSONObject(javaClass.getResource("/scene-constants.json")!!.readText())
    private val tuning = fixture.getJSONObject("tuning")

    private fun doubles(v: Any): DoubleArray =
        if (v is JSONArray) DoubleArray(v.length()) { v.getDouble(it) } else doubleArrayOf((v as Number).toDouble())

    @Test
    fun constantsMatchTheReferenceValues() {
        val c = SceneConstants.from(tuning)
        val expected = fixture.getJSONObject("constants")
        val actual = mapOf(
            "focal" to doubleArrayOf(c.focal), "earthCenter" to c.earthCenter, "impactWorld" to c.impactWorld,
            "impactNormal" to c.impactNormal, "meteorVel" to c.meteorVel,
        )
        for (key in expected.keys()) {
            val e = doubles(expected.get(key))
            val a = actual.getValue(key)
            assertEquals(key, e.size, a.size)
            e.indices.forEach { assertEquals(key, e[it], a[it], 1e-12) }
        }
    }

    @Test
    fun cameraShiftMatchesTheReferenceValues() {
        val shifts = fixture.getJSONArray("shifts")
        val out = FloatArray(2)
        for (i in 0 until shifts.length()) {
            val f = shifts.getJSONObject(i)
            SceneConstants.cameraShift(tuning.getDouble("sway"), tuning.getDouble("shake"),
                f.getDouble("time"), f.getDouble("offset"), f.getDouble("impactT"), out)
            val e = doubles(f.get("shift"))
            assertEquals("shift $i x", e[0], out[0].toDouble(), 1e-6)
            assertEquals("shift $i y", e[1], out[1].toDouble(), 1e-6)
        }
    }

    @Test
    fun uniformsCarryTheShaderNames() {
        val names = SceneConstants.uniforms(tuning).map { it.name }
        assertEquals(listOf("uFocal", "uEarthCenter", "uImpactWorld", "uImpactNormal", "uMeteorVel"), names)
    }
}
