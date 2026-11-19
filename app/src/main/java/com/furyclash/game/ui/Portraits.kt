package com.furyclash.game.ui

import android.graphics.Bitmap
import android.opengl.GLES30
import com.furyclash.game.engine.FightCamera
import com.furyclash.game.engine.Renderer
import com.furyclash.game.engine.Vec3
import com.furyclash.game.engine.rgb
import com.furyclash.game.game.Anims
import com.furyclash.game.game.FighterModel
import com.furyclash.game.game.Joints
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders each fighter's 3D bust into a bitmap (real 3D portraits).
 * Incremental: one portrait per GL frame so startup never blocks on all 15.
 */
object Portraits {
    private const val SIZE = 128
    val bitmaps = arrayOfNulls<Bitmap>(15)
    var ready = false
        private set

    private var next = 0
    private var fbo = 0
    private var tex = 0
    private var depth = 0
    private var buf: ByteBuffer? = null
    private val pixels = IntArray(SIZE * SIZE)
    private val pose = FloatArray(Joints.POSE_SIZE)
    private var cam: FightCamera? = null

    private fun setup() {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, SIZE, SIZE, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val d = IntArray(1)
        GLES30.glGenRenderbuffers(1, d, 0)
        depth = d[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, SIZE, SIZE)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
        buf = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        cam = FightCamera().apply {
            followSpeed = 1000f
            goalEye = Vec3(0.85f, 1.55f, 1.05f)
            goalTarget = Vec3(0f, 1.40f, 0f)
            goalFov = 33f
            update(1f, 1f)
        }
    }

    private fun teardown() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteRenderbuffers(1, intArrayOf(depth), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        fbo = 0; tex = 0; depth = 0
        buf = null
        cam = null
    }

    /** Captures one portrait; returns true when all are done. Restores default FB + viewport. */
    fun captureNext(renderer: Renderer, models: List<FighterModel>, screenW: Int, screenH: Int): Boolean {
        if (ready || next >= models.size) { ready = true; return true }
        if (fbo == 0) {
            setup()
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                teardown()
                ready = true
                return true
            }
        }
        val c = cam ?: return true
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, SIZE, SIZE)
        renderer.fogColor = rgb(0x141826)
        renderer.fogDensity = 0f
        renderer.lightDir = Vec3(-0.4f, 0.7f, 0.6f).normalized()
        renderer.lightColor = rgb(0xFFF2E0)
        renderer.ambient = rgb(0x6A7590)
        renderer.ground = rgb(0x3A3440)

        val i = next
        Anims.compute("idle", 0.4f, pose)
        models[i].compute(0f, 0f, 1, pose)
        renderer.beginFrame(c)
        models[i].draw(renderer, null)
        val b = buf ?: return true
        b.position(0)
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
        for (y in 0 until SIZE) {
            val srcRow = SIZE - 1 - y
            for (x in 0 until SIZE) {
                val o = (srcRow * SIZE + x) * 4
                val r = b.get(o).toInt() and 0xFF
                val g = b.get(o + 1).toInt() and 0xFF
                val bl = b.get(o + 2).toInt() and 0xFF
                pixels[y * SIZE + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        bitmaps[i] = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        next++
        if (next >= models.size) {
            teardown()
            ready = true
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        GLES30.glViewport(0, 0, screenW, screenH)
        return ready
    }

    /** Reset on GL context loss so portraits re-capture into the new context. */
    fun onContextLost() {
        // GPU names die with the context; just restart the sequence.
        fbo = 0; tex = 0; depth = 0
        buf = null
        cam = null
        next = 0
        ready = false
    }
}
