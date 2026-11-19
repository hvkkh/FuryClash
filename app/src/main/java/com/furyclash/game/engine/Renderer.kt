package com.furyclash.game.engine

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Smooth-follow fight camera with shake and cinematic override support. */
class FightCamera {
    private var eyeX = 0f; private var eyeY = 2.2f; private var eyeZ = 7f
    private var tgtX = 0f; private var tgtY = 1.1f; private var tgtZ = 0f
    val eye: Vec3 get() = Vec3(eyeX, eyeY, eyeZ)
    val target: Vec3 get() = Vec3(tgtX, tgtY, tgtZ)
    var fovDeg = 45f

    // goals set by game code each frame
    var goalEye = Vec3(0f, 2.2f, 7f)
    var goalTarget = Vec3(0f, 1.1f, 0f)
    var goalFov = 45f
    var followSpeed = 6f

    var shakeAmount = 0f
    private var shakeTime = 0f

    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)

    fun shake(amount: Float) {
        shakeAmount = max(shakeAmount, amount)
    }

    fun update(dt: Float, aspect: Float) {
        val t = clamp(followSpeed * dt, 0f, 1f)
        eyeX = lerp(eyeX, goalEye.x, t); eyeY = lerp(eyeY, goalEye.y, t); eyeZ = lerp(eyeZ, goalEye.z, t)
        tgtX = lerp(tgtX, goalTarget.x, t); tgtY = lerp(tgtY, goalTarget.y, t); tgtZ = lerp(tgtZ, goalTarget.z, t)
        fovDeg = lerp(fovDeg, goalFov, t)
        shakeTime += dt * 40f
        shakeAmount = max(0f, shakeAmount - dt * 2.2f)
        val s = shakeAmount * shakeAmount * 0.35f
        val sx = sin(shakeTime * 1.3f) * s
        val sy = cos(shakeTime * 1.7f) * s
        val sz = sin(shakeTime * 0.9f) * s * 0.4f

        M4.lookAt(view, Vec3(eyeX + sx, eyeY + sy, eyeZ + sz), Vec3(tgtX, tgtY, tgtZ), Vec3.UP)
        M4.perspective(proj, fovDeg * Math.PI.toFloat() / 180f, aspect, 0.1f, 120f)
        M4.multiply(viewProj, proj, view)
    }

    /** Project world point to screen pixels; returns false if behind camera. */
    private val projOut = FloatArray(4)
    fun project(wx: Float, wy: Float, wz: Float, screenW: Int, screenH: Int, out: FloatArray): Boolean {
        M4.transformPoint(viewProj, wx, wy, wz, projOut)
        if (projOut[3] <= 0.01f) return false
        val nx = projOut[0] / projOut[3]
        val ny = projOut[1] / projOut[3]
        out[0] = (nx * 0.5f + 0.5f) * screenW
        out[1] = (1f - (ny * 0.5f + 0.5f)) * screenH
        return true
    }
}

/**
 * Forward renderer: one lit program for all world geometry, one additive
 * point-sprite program for particles. Attribute locations are fixed:
 * 0=pos 1=normal 2=color 3=emissive (world), 0=pos 1=size 2=color (particles).
 */
class Renderer {
    private var worldProg = 0
    private var particleProg = 0

    // world uniform locations
    private var uMVP = 0; private var uModel = 0; private var uNormal = 0
    private var uLightDir = 0; private var uLightColor = 0; private var uAmbient = 0
    private var uGround = 0; private var uFogColor = 0; private var uFogDensity = 0
    private var uCamPos = 0; private var uTint = 0; private var uEmissiveBoost = 0; private var uSpecular = 0
    // particle uniform locations
    private var puVP = 0; private var puScale = 0

    private val mvp = FloatArray(16)
    private val normal = FloatArray(9)
    private val identity = FloatArray(16).also { M4.identity(it) }

    var lightDir = Vec3(-0.35f, 0.85f, 0.45f).normalized()
    var lightColor = rgb(0xFFF2E0)
    var ambient = rgb(0x4A5570)
    var ground = rgb(0x2A2430)
    var fogColor = rgb(0x0B0E1A)
    var fogDensity = 0.022f
    var specular = 0.25f

    private val camPos = FloatArray(3)

    fun init() {
        GLState.generation++
        worldProg = compileProgram(WORLD_VS, WORLD_FS)
        GLES30.glBindAttribLocation(worldProg, 0, "aPos")
        GLES30.glBindAttribLocation(worldProg, 1, "aNormal")
        GLES30.glBindAttribLocation(worldProg, 2, "aColor")
        GLES30.glBindAttribLocation(worldProg, 3, "aEmissive")
        // relink after binding attribute locations
        GLES30.glLinkProgram(worldProg)
        uMVP = GLES30.glGetUniformLocation(worldProg, "uMVP")
        uModel = GLES30.glGetUniformLocation(worldProg, "uModel")
        uNormal = GLES30.glGetUniformLocation(worldProg, "uNormal")
        uLightDir = GLES30.glGetUniformLocation(worldProg, "uLightDir")
        uLightColor = GLES30.glGetUniformLocation(worldProg, "uLightColor")
        uAmbient = GLES30.glGetUniformLocation(worldProg, "uAmbient")
        uGround = GLES30.glGetUniformLocation(worldProg, "uGround")
        uFogColor = GLES30.glGetUniformLocation(worldProg, "uFogColor")
        uFogDensity = GLES30.glGetUniformLocation(worldProg, "uFogDensity")
        uCamPos = GLES30.glGetUniformLocation(worldProg, "uCamPos")
        uTint = GLES30.glGetUniformLocation(worldProg, "uTint")
        uEmissiveBoost = GLES30.glGetUniformLocation(worldProg, "uEmissiveBoost")
        uSpecular = GLES30.glGetUniformLocation(worldProg, "uSpecular")

        particleProg = compileProgram(PARTICLE_VS, PARTICLE_FS)
        GLES30.glBindAttribLocation(particleProg, 0, "aPos")
        GLES30.glBindAttribLocation(particleProg, 1, "aSize")
        GLES30.glBindAttribLocation(particleProg, 2, "aColor")
        GLES30.glLinkProgram(particleProg)
        puVP = GLES30.glGetUniformLocation(particleProg, "uVP")
        puScale = GLES30.glGetUniformLocation(particleProg, "uScale")

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f)
    }

    fun beginFrame(camera: FightCamera) {
        currentViewProj = camera.viewProj
        GLES30.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(worldProg)
        GLES30.glUniform3f(uLightDir, lightDir.x, lightDir.y, lightDir.z)
        GLES30.glUniform3fv(uLightColor, 1, lightColor, 0)
        GLES30.glUniform3fv(uAmbient, 1, ambient, 0)
        GLES30.glUniform3fv(uGround, 1, ground, 0)
        GLES30.glUniform3fv(uFogColor, 1, fogColor, 0)
        GLES30.glUniform1f(uFogDensity, fogDensity)
        GLES30.glUniform1f(uSpecular, specular)
        camPos[0] = camera.eye.x; camPos[1] = camera.eye.y; camPos[2] = camera.eye.z
        GLES30.glUniform3fv(uCamPos, 1, camPos, 0)
        GLES30.glUniform3f(uTint, 1f, 1f, 1f)
        GLES30.glUniform1f(uEmissiveBoost, 1f)
    }

    fun draw(mesh: Mesh, model: FloatArray, tint: FloatArray? = null, emissiveBoost: Float = 1f) {
        M4.multiply(mvp, currentViewProj, model)
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        M4.normalMat3(normal, model)
        GLES30.glUniformMatrix3fv(uNormal, 1, false, normal, 0)
        if (tint != null) GLES30.glUniform3fv(uTint, 1, tint, 0)
        else GLES30.glUniform3f(uTint, 1f, 1f, 1f)
        GLES30.glUniform1f(uEmissiveBoost, emissiveBoost)
        mesh.draw()
    }

    private var currentViewProj = FloatArray(16)
    fun setViewProj(vp: FloatArray) { currentViewProj = vp }

    fun drawParticles(ps: ParticleSystem, viewProj: FloatArray, screenHeight: Int) {
        if (ps.count == 0) return
        ps.fillBuffer()
        GLES30.glUseProgram(particleProg)
        GLES30.glUniformMatrix4fv(puVP, 1, false, viewProj, 0)
        GLES30.glUniform1f(puScale, screenHeight * 0.9f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        ps.draw()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(worldProg)
    }

    companion object {
        private const val WORLD_VS = """#version 300 es
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat3 uNormal;
in vec3 aPos;
in vec3 aNormal;
in vec3 aColor;
in float aEmissive;
out vec3 vNormal;
out vec3 vColor;
out float vEmissive;
out vec3 vWorld;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = uNormal * aNormal;
    vColor = aColor;
    vEmissive = aEmissive;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val WORLD_FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vColor;
in float vEmissive;
in vec3 vWorld;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform vec3 uGround;
uniform vec3 uFogColor;
uniform float uFogDensity;
uniform vec3 uCamPos;
uniform vec3 uTint;
uniform float uEmissiveBoost;
uniform float uSpecular;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    float ndl = max(dot(n, uLightDir), 0.0);
    vec3 hemi = mix(uGround, uAmbient, n.y * 0.5 + 0.5);
    vec3 viewDir = normalize(uCamPos - vWorld);
    vec3 h = normalize(uLightDir + viewDir);
    float spec = pow(max(dot(n, h), 0.0), 36.0) * uSpecular * ndl;
    vec3 base = vColor * uTint;
    vec3 col = base * (hemi + uLightColor * ndl) + uLightColor * spec;
    col += base * vEmissive * uEmissiveBoost;
    float dist = length(uCamPos - vWorld);
    float fog = 1.0 - exp(-uFogDensity * uFogDensity * dist * dist);
    col = mix(col, uFogColor, clamp(fog, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
"""
        private const val PARTICLE_VS = """#version 300 es
uniform mat4 uVP;
uniform float uScale;
in vec3 aPos;
in float aSize;
in vec4 aColor;
out vec4 vColor;
void main() {
    gl_Position = uVP * vec4(aPos, 1.0);
    gl_PointSize = clamp(aSize * uScale / max(gl_Position.w, 0.1), 1.0, 160.0);
    vColor = aColor;
}
"""
        private const val PARTICLE_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float r = length(d) * 2.0;
    float a = smoothstep(1.0, 0.15, r);
    fragColor = vec4(vColor.rgb, vColor.a * a);
}
"""
    }
}

/** CPU-simulated additive point-sprite particles, one dynamic draw call. */
class ParticleSystem(private val max: Int = 2048) {
    private val px = FloatArray(max); private val py = FloatArray(max); private val pz = FloatArray(max)
    private val vx = FloatArray(max); private val vy = FloatArray(max); private val vz = FloatArray(max)
    private val life = FloatArray(max); private val maxLife = FloatArray(max)
    private val size = FloatArray(max); private val grav = FloatArray(max); private val drag = FloatArray(max)
    private val cr = FloatArray(max); private val cg = FloatArray(max); private val cb = FloatArray(max); private val ca = FloatArray(max)
    var count = 0
        private set

    private val buffer: FloatBuffer = ByteBuffer.allocateDirect(max * 8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vbo = 0
    private var vao = 0
    private var gen = -1

    var density = 1f // graphics quality scaler

    fun clear() { count = 0 }

    fun spawn(x: Float, y: Float, z: Float, velX: Float, velY: Float, velZ: Float, lifeSec: Float, sizeWorld: Float, r: Float, g: Float, b: Float, a: Float, gravity: Float = 0f, dragK: Float = 0f) {
        if (count >= max) return
        val i = count++
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = velX; vy[i] = velY; vz[i] = velZ
        life[i] = lifeSec; maxLife[i] = lifeSec
        size[i] = sizeWorld; grav[i] = gravity; drag[i] = dragK
        cr[i] = r; cg[i] = g; cb[i] = b; ca[i] = a
    }

    fun burst(x: Float, y: Float, z: Float, n: Int, speed: Float, lifeSec: Float, sizeWorld: Float, color: FloatArray, alpha: Float = 1f, gravity: Float = 0f, upBias: Float = 0f) {
        val nn = (n * density).toInt().coerceAtLeast(1)
        for (i in 0 until nn) {
            val theta = Random.nextFloat() * 2f * Math.PI.toFloat()
            val phi = (Random.nextFloat() - 0.5f) * Math.PI.toFloat()
            val sp = speed * (0.35f + Random.nextFloat() * 0.65f)
            spawn(
                x, y, z,
                cos(theta) * cos(phi) * sp,
                sin(phi) * sp + upBias,
                sin(theta) * cos(phi) * sp * 0.6f,
                lifeSec * (0.6f + Random.nextFloat() * 0.6f),
                sizeWorld * (0.6f + Random.nextFloat() * 0.8f),
                color[0], color[1], color[2], alpha, gravity, 1.5f
            )
        }
    }

    fun ring(x: Float, y: Float, z: Float, n: Int, radius: Float, speed: Float, lifeSec: Float, sizeWorld: Float, color: FloatArray, alpha: Float = 1f) {
        val nn = (n * density).toInt().coerceAtLeast(3)
        for (i in 0 until nn) {
            val a = (i.toFloat() / nn) * 2f * Math.PI.toFloat()
            spawn(
                x + cos(a) * radius * 0.3f, y, z + sin(a) * radius * 0.3f,
                cos(a) * speed, 0.4f, sin(a) * speed * 0.5f,
                lifeSec, sizeWorld, color[0], color[1], color[2], alpha, 0f, 1.2f
            )
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                val last = --count
                px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last]
                vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
                life[i] = life[last]; maxLife[i] = maxLife[last]
                size[i] = size[last]; grav[i] = grav[last]; drag[i] = drag[last]
                cr[i] = cr[last]; cg[i] = cg[last]; cb[i] = cb[last]; ca[i] = ca[last]
                continue
            }
            vy[i] -= grav[i] * dt
            val d = max(0f, 1f - drag[i] * dt)
            vx[i] *= d; vy[i] *= d; vz[i] *= d
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            if (py[i] < 0.02f && grav[i] > 0f) { py[i] = 0.02f; vy[i] = -vy[i] * 0.3f }
            i++
        }
    }

    fun fillBuffer() {
        buffer.position(0)
        for (i in 0 until count) {
            val t = life[i] / maxLife[i]
            buffer.put(px[i]); buffer.put(py[i]); buffer.put(pz[i])
            buffer.put(size[i] * (0.5f + 0.5f * t))
            buffer.put(cr[i]); buffer.put(cg[i]); buffer.put(cb[i]); buffer.put(ca[i] * t)
        }
        buffer.position(0)
    }

    fun draw() {
        if (vao == 0 || gen != GLState.generation) {
            gen = GLState.generation
            val bufs = IntArray(1)
            GLES30.glGenVertexArrays(1, bufs, 0)
            vao = bufs[0]
            GLES30.glBindVertexArray(vao)
            GLES30.glGenBuffers(1, bufs, 0)
            vbo = bufs[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, max * 8 * 4, null, GLES30.GL_DYNAMIC_DRAW)
            val stride = 8 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 3 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 4 * 4)
            GLES30.glBindVertexArray(0)
        }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 8 * 4, buffer)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glBindVertexArray(0)
    }
}
