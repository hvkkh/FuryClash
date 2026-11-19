package com.furyclash.game

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.View
import com.furyclash.game.engine.Renderer
import com.furyclash.game.engine.clamp
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GL thread: fixed-clamp timestep simulation + 3D scene, then nudges the Canvas overlay. */
class GameRenderer(private val game: Game, private val overlay: View) : GLSurfaceView.Renderer {
    private val renderer = Renderer()
    private var w = 1
    private var h = 1
    private var lastNs = 0L

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        renderer.init()
        game.initGl(renderer)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        w = width; h = height
        GLES30.glViewport(0, 0, width, height)
        game.onScreenSize(width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 60f else clamp((now - lastNs) / 1_000_000_000f, 0.0001f, 0.05f)
        lastNs = now
        synchronized(game) {
            game.tick(dt)
            game.drawScene(renderer, w, h, dt)
        }
        overlay.postInvalidateOnAnimation()
    }
}
