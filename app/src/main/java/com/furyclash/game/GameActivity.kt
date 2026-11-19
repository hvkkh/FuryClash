package com.furyclash.game

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.content.Context
import android.graphics.Canvas
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.furyclash.game.audio.Sfx
import com.furyclash.game.game.BattleMode
import com.furyclash.game.game.Difficulty
import com.furyclash.game.input.InputState
import com.furyclash.game.input.TouchControls

class GameActivity : Activity() {
    lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var overlayView: OverlayView
    private val keysDown = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        game = Game(this)
        loadSettings()
        game.sfx = Sfx(this)
        game.sfx.enabled = prefs().getBoolean("sfx", true)
        game.sfx.musicEnabled = prefs().getBoolean("music", true)

        overlayView = OverlayView(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(GameRenderer(game, overlayView))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
        offerNetResume()
    }

    /** Persist / restore an online session so a killed app can rejoin its match. */
    fun saveNetResume(s: com.furyclash.game.net.Session) {
        prefs().edit().putString("netResume",
            "${s.kind.ordinal}|${if (s.isHost) 1 else 0}|${s.serverUrl}|${s.roomCode}|${s.myToken}").apply()
    }

    fun clearNetResume() {
        prefs().edit().remove("netResume").apply()
    }

    private fun offerNetResume() {
        val raw = prefs().getString("netResume", null) ?: return
        val parts = raw.split("|")
        if (parts.size != 5) { clearNetResume(); return }
        val kind = if (parts[0].toIntOrNull() == 0) com.furyclash.game.net.NetKind.LAN else com.furyclash.game.net.NetKind.ONLINE
        if (kind != com.furyclash.game.net.NetKind.ONLINE) { clearNetResume(); return }
        val isHost = parts[1] == "1"
        val url = parts[2]; val code = parts[3]; val token = parts[4]
        AlertDialog.Builder(this)
            .setTitle("Rejoin last match?")
            .setMessage("Room $code is still waiting for you.")
            .setPositiveButton("Rejoin") { _, _ ->
                val s = com.furyclash.game.net.Session(game, kind, isHost, (android.os.Build.MODEL ?: "Player").take(18), token)
                game.session = s
                s.resumeOnline(url, code)
                game.state = AppState.LOBBY
            }
            .setNegativeButton("Discard") { _, _ -> clearNetResume() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        game.sfx.startMusic()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        game.sfx.stopMusic()
        synchronized(game) {
            if (game.state == AppState.BATTLE) game.paused = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        game.sfx.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        synchronized(game) { game.backPressed() }
    }

    // ---------------- settings ----------------

    private fun prefs() = getSharedPreferences("fury_settings", Context.MODE_PRIVATE)

    private fun loadSettings() {
        val p = prefs()
        game.difficulty = Difficulty.entries.getOrElse(p.getInt("difficulty", 1)) { Difficulty.NORMAL }
        game.roundTime = p.getFloat("roundTime", 99f)
        game.shakeEnabled = p.getBoolean("shake", true)
        game.particleQuality = p.getFloat("particles", 1f)
        game.relayUrl = p.getString("relayUrl", "") ?: ""
    }

    /** Native text-entry dialog (room codes, IPs, relay URL) with soft keyboard. */
    fun showTextInput(title: String, hint: String, initial: String, cb: (String) -> Unit) {
        runOnUiThread {
            val input = EditText(this).apply {
                setText(initial)
                setHint(hint)
                setSingleLine()
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK") { _, _ -> cb(input.text.toString()) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun saveSettings() {
        prefs().edit()
            .putString("relayUrl", game.relayUrl)
            .putInt("difficulty", game.difficulty.ordinal)
            .putFloat("roundTime", game.roundTime)
            .putBoolean("shake", game.shakeEnabled)
            .putFloat("particles", game.particleQuality)
            .putBoolean("sfx", game.sfx.enabled)
            .putBoolean("music", game.sfx.musicEnabled)
            .apply()
    }

    // ---------------- keyboard (emulator / desktop testing) ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        synchronized(game) {
            keysDown.add(keyCode)
            if (game.handleKey(keyCode, true)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        synchronized(game) {
            keysDown.remove(keyCode)
            if (game.handleKey(keyCode, false)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Called from Game.tick: apply held movement keys after joystick axes. */
    fun applyKeyboard(inputs: Array<InputState>, dual: Boolean) {
        // P1 movement
        var mx = 0f
        if (keysDown.contains(KeyEvent.KEYCODE_A)) mx -= 1f
        if (keysDown.contains(KeyEvent.KEYCODE_D)) mx += 1f
        if (mx != 0f) inputs[0].moveX = mx
        if (keysDown.contains(KeyEvent.KEYCODE_S)) inputs[0].moveY = -1f
        if (keysDown.contains(KeyEvent.KEYCODE_W)) inputs[0].moveY = 1f
        if (keysDown.contains(KeyEvent.KEYCODE_U)) inputs[0].blockHeld = true
        if (dual) {
            var mx2 = 0f
            if (keysDown.contains(KeyEvent.KEYCODE_DPAD_LEFT)) mx2 -= 1f
            if (keysDown.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) mx2 += 1f
            if (mx2 != 0f) inputs[1].moveX = mx2
            if (keysDown.contains(KeyEvent.KEYCODE_DPAD_DOWN)) inputs[1].moveY = -1f
            if (keysDown.contains(KeyEvent.KEYCODE_DPAD_UP)) inputs[1].moveY = 1f
            if (keysDown.contains(KeyEvent.KEYCODE_4)) inputs[1].blockHeld = true
        }
    }

    /** Edge-triggered keys; returns true if consumed. */
    fun routeKey(keyCode: Int, down: Boolean, inputs: Array<InputState>, dual: Boolean): Boolean {
        if (game.state != AppState.BATTLE || game.paused) {
            if (down && keyCode == KeyEvent.KEYCODE_ENTER) { /* no-op */ }
            return false
        }
        fun press(p: Int, btn: Int) { if (down) inputs[p].press(btn) }
        when (keyCode) {
            KeyEvent.KEYCODE_W -> press(0, InputState.JUMP)
            KeyEvent.KEYCODE_J -> press(0, InputState.PUNCH)
            KeyEvent.KEYCODE_K -> press(0, InputState.KICK)
            KeyEvent.KEYCODE_L -> press(0, InputState.HEAVY)
            KeyEvent.KEYCODE_I -> press(0, InputState.S1)
            KeyEvent.KEYCODE_O -> press(0, InputState.S2)
            KeyEvent.KEYCODE_P -> press(0, InputState.ULT)
            KeyEvent.KEYCODE_Q -> press(0, InputState.SWITCH)
            KeyEvent.KEYCODE_U -> inputs[0].blockHeld = down
            KeyEvent.KEYCODE_ESCAPE -> { if (down) game.togglePause() }
            else -> {
                if (!dual) return false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> press(1, InputState.JUMP)
                    KeyEvent.KEYCODE_1 -> press(1, InputState.PUNCH)
                    KeyEvent.KEYCODE_2 -> press(1, InputState.KICK)
                    KeyEvent.KEYCODE_3 -> press(1, InputState.HEAVY)
                    KeyEvent.KEYCODE_5 -> press(1, InputState.S1)
                    KeyEvent.KEYCODE_6 -> press(1, InputState.S2)
                    KeyEvent.KEYCODE_7 -> press(1, InputState.ULT)
                    KeyEvent.KEYCODE_8 -> press(1, InputState.SWITCH)
                    KeyEvent.KEYCODE_4 -> inputs[1].blockHeld = down
                    else -> return false
                }
            }
        }
        return true
    }

    /** Canvas overlay: all 2D UI + touch routing. */
    private inner class OverlayView(context: Context) : View(context) {
        init {
            isClickable = true
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            synchronized(game) {
                game.drawUi(canvas, width, height)
            }
            // Self-sustaining UI loop: keeps menus/touch hit-rects live even
            // while the GL thread is busy (shader compile, portrait capture).
            postInvalidateOnAnimation()
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            synchronized(game) {
                return game.onTouch(event)
            }
        }
    }
}
