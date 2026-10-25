package io.github.mnesimiyilmaz.extinction.render

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Values every shader needs that depend on tuning alone (common.glsl), worked out once instead of
// per pixel. Checked against reference values (SceneConstantsTest).
object SceneConstants {
    class Values(
        val focal: Double,
        val earthCenter: DoubleArray,
        val impactWorld: DoubleArray,
        val impactNormal: DoubleArray,
        val meteorVel: DoubleArray,
    )

    fun from(t: JSONObject): Values {
        val focal = 0.5 / tan(t.getDouble("camFovY") * PI / 360.0)
        val earthDir = t.getJSONArray("earthDir")
        val earthCenter = scale(normalize(doubleArrayOf(earthDir.getDouble(0), earthDir.getDouble(1), -focal)), t.getDouble("earthDist"))
        val impactQ = t.getJSONArray("impactQ")
        val rd = normalize(doubleArrayOf(impactQ.getDouble(0), impactQ.getDouble(1), -focal))
        val b = dot(rd, earthCenter)
        val h = b * b - dot(earthCenter, earthCenter) + 1.0
        val impactWorld = scale(rd, if (h < 0) -1.0 else b - sqrt(h)) // the ray meets the Earth
        val start = t.getJSONArray("meteorStart")
        val meteorStart = DoubleArray(3) { start.getDouble(it) }
        return Values(
            focal, earthCenter, impactWorld,
            normalize(DoubleArray(3) { impactWorld[it] - earthCenter[it] }),
            normalize(DoubleArray(3) { impactWorld[it] - meteorStart[it] }),
        )
    }

    fun uniforms(t: JSONObject): List<Uniform> {
        val c = from(t)
        fun f(v: DoubleArray) = FloatArray(v.size) { v[it].toFloat() }
        return listOf(
            Uniform("uFocal", floatArrayOf(c.focal.toFloat())),
            Uniform("uEarthCenter", f(c.earthCenter)),
            Uniform("uImpactWorld", f(c.impactWorld)),
            Uniform("uImpactNormal", f(c.impactNormal)),
            Uniform("uMeteorVel", f(c.meteorVel)),
        )
    }

    // The camera's shift in q for parallax amount 1 (common.glsl parallaxShift): page offset, a slow
    // ~30 s sway and the impact shake. Written into out[0..1]; no allocation per frame.
    fun cameraShift(sway: Double, shake: Double, time: Double, offset: Double, impactT: Double, out: FloatArray) {
        val s = if (impactT >= 0) shake * exp(-impactT * 2.5) else 0.0
        out[0] = (offset - 0.5 + sway * sin(time * 0.21) + s * 0.5 * (sin(impactT * 41.0) + sin(impactT * 23.7))).toFloat()
        out[1] = (sway * 0.6 * sin(time * 0.13 + 1.0) + s * 0.5 * (sin(impactT * 37.3) + sin(impactT * 29.1))).toFloat()
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun scale(v: DoubleArray, k: Double) = DoubleArray(3) { v[it] * k }
    private fun normalize(v: DoubleArray) = scale(v, 1.0 / sqrt(dot(v, v)))
}
