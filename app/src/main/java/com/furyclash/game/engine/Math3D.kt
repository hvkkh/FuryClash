package com.furyclash.game.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Immutable 3D vector used for setup/logic math (hot loops use float arrays). */
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-8f) Vec3(0f, 0f, 0f) else Vec3(x / l, y / l, z / l)
    }
    fun lerp(o: Vec3, t: Float) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val ZERO = Vec3()
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** Column-major 4x4 matrix helpers (OpenGL layout: m[col*4+row]). */
object M4 {
    private val scratch = FloatArray(16)

    fun identity(out: FloatArray) {
        for (i in 0 until 16) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
    }

    /** out = a * b (out must not alias a or b). */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
                scratch[c * 4 + r] = s
            }
        }
        System.arraycopy(scratch, 0, out, 0, 16)
    }

    fun translation(out: FloatArray, x: Float, y: Float, z: Float) {
        identity(out)
        out[12] = x; out[13] = y; out[14] = z
    }

    fun scaling(out: FloatArray, x: Float, y: Float, z: Float) {
        identity(out)
        out[0] = x; out[5] = y; out[10] = z
    }

    fun rotationX(out: FloatArray, rad: Float) {
        identity(out)
        val c = cos(rad); val s = sin(rad)
        out[5] = c; out[6] = s; out[9] = -s; out[10] = c
    }

    fun rotationY(out: FloatArray, rad: Float) {
        identity(out)
        val c = cos(rad); val s = sin(rad)
        out[0] = c; out[2] = -s; out[8] = s; out[10] = c
    }

    fun rotationZ(out: FloatArray, rad: Float) {
        identity(out)
        val c = cos(rad); val s = sin(rad)
        out[0] = c; out[1] = s; out[4] = -s; out[5] = c
    }

    fun perspective(out: FloatArray, fovYRad: Float, aspect: Float, near: Float, far: Float) {
        for (i in 0 until 16) out[i] = 0f
        val f = 1f / tan(fovYRad / 2f)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
    }

    fun lookAt(out: FloatArray, eye: Vec3, center: Vec3, up: Vec3) {
        val z = (eye - center).normalized()
        val x = up.cross(z).normalized()
        val y = z.cross(x)
        out[0] = x.x; out[1] = y.x; out[2] = z.x; out[3] = 0f
        out[4] = x.y; out[5] = y.y; out[6] = z.y; out[7] = 0f
        out[8] = x.z; out[9] = y.z; out[10] = z.z; out[11] = 0f
        out[12] = -x.dot(eye); out[13] = -y.dot(eye); out[14] = -z.dot(eye); out[15] = 1f
    }

    /**
     * Normal matrix (mat3) from a model matrix built from translations, rotations
     * and a final axis-aligned scale: each column of the upper 3x3 divided by its
     * squared length equals R * S^-1 (inverse transpose), valid without shear.
     */
    fun normalMat3(out9: FloatArray, m: FloatArray) {
        for (c in 0 until 3) {
            val c0 = m[c * 4]; val c1 = m[c * 4 + 1]; val c2 = m[c * 4 + 2]
            val sq = c0 * c0 + c1 * c1 + c2 * c2
            val inv = if (sq > 1e-10f) 1f / sq else 1f
            out9[c * 3] = c0 * inv; out9[c * 3 + 1] = c1 * inv; out9[c * 3 + 2] = c2 * inv
        }
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15]
    }
}

fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun smoothstep(t: Float): Float {
    val x = clamp(t, 0f, 1f)
    return x * x * (3f - 2f * x)
}
