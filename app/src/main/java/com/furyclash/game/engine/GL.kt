package com.furyclash.game.engine

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "FuryGL"

/**
 * Bumped every time the EGL context is (re)created. Cached GPU object names
 * from a previous context are invalid and must be re-uploaded, never reused
 * or deleted (deleting stale names could destroy unrelated new objects).
 */
object GLState {
    var generation = 0
}

fun compileProgram(vsSrc: String, fsSrc: String): Int {
    val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
    val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
    val prog = GLES30.glCreateProgram()
    GLES30.glAttachShader(prog, vs)
    GLES30.glAttachShader(prog, fs)
    GLES30.glLinkProgram(prog)
    val status = IntArray(1)
    GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES30.glGetProgramInfoLog(prog)
        GLES30.glDeleteProgram(prog)
        throw RuntimeException("Program link failed: $log")
    }
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    return prog
}

private fun compileShader(type: Int, src: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, src)
    GLES30.glCompileShader(shader)
    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES30.glGetShaderInfoLog(shader)
        GLES30.glDeleteShader(shader)
        throw RuntimeException("Shader compile failed: $log\n$src")
    }
    return shader
}

fun rgb(hex: Int): FloatArray = floatArrayOf(
    ((hex shr 16) and 0xFF) / 255f,
    ((hex shr 8) and 0xFF) / 255f,
    (hex and 0xFF) / 255f
)

/**
 * Interleaved static mesh: position(3) normal(3) color(3) emissive(1) = 10 floats.
 * GPU buffers are created lazily on first draw inside the GL context.
 */
class Mesh(val vertices: FloatArray, val indices: ShortArray) {
    private var vbo = 0
    private var ibo = 0
    private var vao = 0
    private var gen = -1
    val indexCount = indices.size

    private fun upload() {
        val fb: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(vertices).position(0)
        val sb: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.put(indices).position(0)

        val bufs = IntArray(1)
        GLES30.glGenVertexArrays(1, bufs, 0)
        vao = bufs[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glGenBuffers(1, bufs, 0)
        vbo = bufs[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glGenBuffers(1, bufs, 0)
        ibo = bufs[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, sb, GLES30.GL_STATIC_DRAW)

        val stride = 10 * 4
        // locations fixed by glBindAttribLocation in Renderer: 0 pos, 1 normal, 2 color, 3 emissive
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 6 * 4)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 9 * 4)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        if (vao == 0 || gen != GLState.generation) {
            upload()
            gen = GLState.generation
        }
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun dispose() {
        if (vao != 0 && gen == GLState.generation) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(ibo), 0)
        }
        vao = 0
        gen = -1
    }
}

/**
 * Accumulates transformed primitives into one big mesh (used for arenas so the
 * whole static environment renders in a single draw call).
 */
class MeshBuilder {
    private val verts = ArrayList<Float>(4096)
    private val idx = ArrayList<Short>(4096)
    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val nmat = FloatArray(9)
    private val p = FloatArray(4)

    private fun addVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray, e: Float, m: FloatArray?) {
        var px = x; var py = y; var pz = z
        var nnx = nx; var nny = ny; var nnz = nz
        if (m != null) {
            M4.transformPoint(m, x, y, z, p)
            px = p[0]; py = p[1]; pz = p[2]
            M4.normalMat3(nmat, m)
            nnx = nmat[0] * nx + nmat[3] * ny + nmat[6] * nz
            nny = nmat[1] * nx + nmat[4] * ny + nmat[7] * nz
            nnz = nmat[2] * nx + nmat[5] * ny + nmat[8] * nz
            val l = kotlin.math.sqrt(nnx * nnx + nny * nny + nnz * nnz)
            if (l > 1e-8f) { nnx /= l; nny /= l; nnz /= l }
        }
        verts.add(px); verts.add(py); verts.add(pz)
        verts.add(nnx); verts.add(nny); verts.add(nnz)
        verts.add(c[0]); verts.add(c[1]); verts.add(c[2])
        verts.add(e)
    }

    private fun quad(base: Int) {
        idx.add(base.toShort()); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
        idx.add(base.toShort()); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
    }

    fun box(cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float, color: FloatArray, emissive: Float = 0f, transform: FloatArray? = null) {
        val x0 = cx - w / 2; val x1 = cx + w / 2
        val y0 = cy - h / 2; val y1 = cy + h / 2
        val z0 = cz - d / 2; val z1 = cz + d / 2
        val base = verts.size / 10
        // +X
        addVertex(x1, y0, z0, 1f, 0f, 0f, color, emissive, transform)
        addVertex(x1, y1, z0, 1f, 0f, 0f, color, emissive, transform)
        addVertex(x1, y1, z1, 1f, 0f, 0f, color, emissive, transform)
        addVertex(x1, y0, z1, 1f, 0f, 0f, color, emissive, transform)
        quad(base)
        // -X
        addVertex(x0, y0, z1, -1f, 0f, 0f, color, emissive, transform)
        addVertex(x0, y1, z1, -1f, 0f, 0f, color, emissive, transform)
        addVertex(x0, y1, z0, -1f, 0f, 0f, color, emissive, transform)
        addVertex(x0, y0, z0, -1f, 0f, 0f, color, emissive, transform)
        quad(base + 4)
        // +Y
        addVertex(x0, y1, z0, 0f, 1f, 0f, color, emissive, transform)
        addVertex(x0, y1, z1, 0f, 1f, 0f, color, emissive, transform)
        addVertex(x1, y1, z1, 0f, 1f, 0f, color, emissive, transform)
        addVertex(x1, y1, z0, 0f, 1f, 0f, color, emissive, transform)
        quad(base + 8)
        // -Y
        addVertex(x0, y0, z1, 0f, -1f, 0f, color, emissive, transform)
        addVertex(x0, y0, z0, 0f, -1f, 0f, color, emissive, transform)
        addVertex(x1, y0, z0, 0f, -1f, 0f, color, emissive, transform)
        addVertex(x1, y0, z1, 0f, -1f, 0f, color, emissive, transform)
        quad(base + 12)
        // +Z
        addVertex(x0, y0, z1, 0f, 0f, 1f, color, emissive, transform)
        addVertex(x1, y0, z1, 0f, 0f, 1f, color, emissive, transform)
        addVertex(x1, y1, z1, 0f, 0f, 1f, color, emissive, transform)
        addVertex(x0, y1, z1, 0f, 0f, 1f, color, emissive, transform)
        quad(base + 16)
        // -Z
        addVertex(x1, y0, z0, 0f, 0f, -1f, color, emissive, transform)
        addVertex(x0, y0, z0, 0f, 0f, -1f, color, emissive, transform)
        addVertex(x0, y1, z0, 0f, 0f, -1f, color, emissive, transform)
        addVertex(x1, y1, z0, 0f, 0f, -1f, color, emissive, transform)
        quad(base + 20)
    }

    /** Vertical cylinder centered at (cx,cy,cz), height h along Y. */
    fun cylinder(cx: Float, cy: Float, cz: Float, rBottom: Float, rTop: Float, h: Float, segments: Int, color: FloatArray, emissive: Float = 0f, transform: FloatArray? = null) {
        val y0 = cy - h / 2; val y1 = cy + h / 2
        val slope = (rBottom - rTop) / h
        for (i in 0 until segments) {
            val a0 = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            val a1 = ((i + 1).toFloat() / segments) * 2f * Math.PI.toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val n0x = c0; val n0z = s0; val n1x = c1; val n1z = s1
            val ny = slope
            val base = verts.size / 10
            addVertex(cx + c0 * rBottom, y0, cz + s0 * rBottom, n0x, ny, n0z, color, emissive, transform)
            addVertex(cx + c0 * rTop, y1, cz + s0 * rTop, n0x, ny, n0z, color, emissive, transform)
            addVertex(cx + c1 * rTop, y1, cz + s1 * rTop, n1x, ny, n1z, color, emissive, transform)
            addVertex(cx + c1 * rBottom, y0, cz + s1 * rBottom, n1x, ny, n1z, color, emissive, transform)
            quad(base)
        }
        // caps
        if (rTop > 0.001f) {
            val center = verts.size / 10
            addVertex(cx, y1, cz, 0f, 1f, 0f, color, emissive, transform)
            for (i in 0..segments) {
                val a = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
                addVertex(cx + cos(a) * rTop, y1, cz + sin(a) * rTop, 0f, 1f, 0f, color, emissive, transform)
            }
            for (i in 0 until segments) {
                idx.add(center.toShort()); idx.add((center + 1 + i).toShort()); idx.add((center + 2 + i).toShort())
            }
        }
        if (rBottom > 0.001f) {
            val center = verts.size / 10
            addVertex(cx, y0, cz, 0f, -1f, 0f, color, emissive, transform)
            for (i in 0..segments) {
                val a = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
                addVertex(cx + cos(a) * rBottom, y0, cz + sin(a) * rBottom, 0f, -1f, 0f, color, emissive, transform)
            }
            for (i in 0 until segments) {
                idx.add(center.toShort()); idx.add((center + 2 + i).toShort()); idx.add((center + 1 + i).toShort())
            }
        }
    }

    fun sphere(cx: Float, cy: Float, cz: Float, r: Float, rings: Int, segments: Int, color: FloatArray, emissive: Float = 0f, transform: FloatArray? = null, scaleY: Float = 1f) {
        val base = verts.size / 10
        for (ring in 0..rings) {
            val phi = (ring.toFloat() / rings) * Math.PI.toFloat()
            val y = cos(phi) * r * scaleY
            val rr = sin(phi) * r
            for (seg in 0..segments) {
                val theta = (seg.toFloat() / segments) * 2f * Math.PI.toFloat()
                val x = cos(theta) * rr
                val z = sin(theta) * rr
                val nl = kotlin.math.sqrt(x * x + (y / scaleY) * (y / scaleY) + z * z)
                val nx = if (nl > 1e-6f) x / nl else 0f
                val ny = if (nl > 1e-6f) (y / scaleY) / nl else 1f
                val nz = if (nl > 1e-6f) z / nl else 0f
                addVertex(cx + x, cy + y, cz + z, nx, ny, nz, color, emissive, transform)
            }
        }
        val stride = segments + 1
        for (ring in 0 until rings) {
            for (seg in 0 until segments) {
                val a = base + ring * stride + seg
                val b = a + stride
                idx.add(a.toShort()); idx.add(b.toShort()); idx.add((b + 1).toShort())
                idx.add(a.toShort()); idx.add((b + 1).toShort()); idx.add((a + 1).toShort())
            }
        }
    }

    /** Horizontal plane (XZ) at height y, centered (cx,cz). */
    fun plane(cx: Float, y: Float, cz: Float, w: Float, d: Float, color: FloatArray, emissive: Float = 0f) {
        val base = verts.size / 10
        addVertex(cx - w / 2, y, cz - d / 2, 0f, 1f, 0f, color, emissive, null)
        addVertex(cx - w / 2, y, cz + d / 2, 0f, 1f, 0f, color, emissive, null)
        addVertex(cx + w / 2, y, cz + d / 2, 0f, 1f, 0f, color, emissive, null)
        addVertex(cx + w / 2, y, cz - d / 2, 0f, 1f, 0f, color, emissive, null)
        quad(base)
    }

    /** Arbitrary transform helper: translate * rotateY * scale. */
    fun trs(tx: Float, ty: Float, tz: Float, rotY: Float, sx: Float, sy: Float, sz: Float, out: FloatArray) {
        M4.translation(tmp, tx, ty, tz)
        M4.rotationY(tmp2, rotY)
        M4.multiply(out, tmp, tmp2)
        M4.scaling(tmp2, sx, sy, sz)
        M4.multiply(tmp, out, tmp2)
        System.arraycopy(tmp, 0, out, 0, 16)
    }

    fun build(): Mesh {
        if (verts.size / 10 > 65535) Log.w(TAG, "Mesh vertex count exceeds 16-bit index range!")
        return Mesh(verts.toFloatArray(), idx.toShortArray())
    }
}
