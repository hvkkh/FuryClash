package com.furyclash.game.input

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.furyclash.game.game.Fighter
import com.furyclash.game.game.TeamBattle
import com.furyclash.game.ui.Ui
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Virtual joystick + attack buttons with full multitouch.
 * SINGLE: P1 joystick left, buttons right (classic layout).
 * DUAL: P1 joystick far-left + buttons left-center, P2 mirrored on the right.
 */
class TouchControls {
    enum class LayoutMode { SINGLE, DUAL }

    class Btn(val id: Int, val label: String, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f,
              var pressed: Boolean = false, val skillIndex: Int = -1, val accent: Int = 0xFF3D7BFF.toInt())

    var mode = LayoutMode.SINGLE
    val buttons = arrayOf(ArrayList<Btn>(9), ArrayList<Btn>(9))
    private val joyBase = arrayOf(FloatArray(2), FloatArray(2))
    private val joyOrigin = arrayOf(FloatArray(2), FloatArray(2))
    private val joyPointer = intArrayOf(-1, -1)
    val axis = arrayOf(FloatArray(2), FloatArray(2))
    var joyRadius = 100f
    private var btnR = 50f
    private var screenW = 0
    private var screenH = 0

    private class Assign(val player: Int, val btn: Btn?, val joy: Boolean)
    private val assigns = HashMap<Int, Assign>()

    companion object {
        const val BLOCK_ID = 8
    }

    fun layout(w: Int, h: Int, m: LayoutMode) {
        mode = m
        screenW = w; screenH = h
        val u = Ui.unit(w, h)
        joyRadius = u * 12.5f
        btnR = u * 6.0f
        assigns.clear()
        joyPointer.fill(-1)
        axis[0].fill(0f); axis[1].fill(0f)
        for (p in 0..1) buttons[p].clear()

        fun add(p: Int, id: Int, label: String, cx: Float, cy: Float, r: Float, skill: Int = -1, accent: Int = 0xFF3D7BFF.toInt()) {
            buttons[p].add(Btn(id, label, cx, cy, r, false, skill, accent))
        }

        val gold = 0xFFFFC94D.toInt()
        val cyan = 0xFF6BE8FF.toInt()
        val green = 0xFF39FF9C.toInt()
        val red = 0xFFFF5A3C.toInt()

        if (m == LayoutMode.SINGLE) {
            joyBase[0][0] = w * 0.13f; joyBase[0][1] = h * 0.72f
            // attack diamond (right)
            add(0, InputState.PUNCH, "P", w - 25 * u, h - 13 * u, btnR, accent = red)
            add(0, InputState.KICK, "K", w - 14.5f * u, h - 20 * u, btnR, accent = 0xFFFF8C3A.toInt())
            add(0, InputState.HEAVY, "H", w - 8.5f * u, h - 9.5f * u, btnR * 1.12f, accent = gold)
            add(0, BLOCK_ID, "B", w - 21 * u, h - 28 * u, btnR * 0.95f, accent = cyan)
            // skills arc above
            add(0, InputState.S1, "S1", w - 33 * u, h - 33 * u, btnR * 0.85f, 0, cyan)
            add(0, InputState.S2, "S2", w - 22.5f * u, h - 39 * u, btnR * 0.85f, 1, cyan)
            add(0, InputState.ULT, "ULT", w - 11.5f * u, h - 33.5f * u, btnR * 0.95f, 2, gold)
            add(0, InputState.SWITCH, "TAG", w - 36 * u, h - 12 * u, btnR * 0.8f, accent = green)
        } else {
            // P1 left half
            joyBase[0][0] = w * 0.075f; joyBase[0][1] = h * 0.74f
            add(0, InputState.PUNCH, "P", w * 0.235f, h - 13 * u, btnR * 0.9f, accent = red)
            add(0, InputState.KICK, "K", w * 0.30f, h - 20 * u, btnR * 0.9f, accent = 0xFFFF8C3A.toInt())
            add(0, InputState.HEAVY, "H", w * 0.345f, h - 9.5f * u, btnR, accent = gold)
            add(0, BLOCK_ID, "B", w * 0.215f, h - 27 * u, btnR * 0.85f, accent = cyan)
            add(0, InputState.S1, "S1", w * 0.395f, h - 26 * u, btnR * 0.78f, 0, cyan)
            add(0, InputState.S2, "S2", w * 0.395f, h - 14.5f * u, btnR * 0.78f, 1, cyan)
            add(0, InputState.ULT, "ULT", w * 0.395f, h - 37.5f * u, btnR * 0.82f, 2, gold)
            add(0, InputState.SWITCH, "TAG", w * 0.155f, h - 9.5f * u, btnR * 0.72f, accent = green)
            // P2 mirrored right half
            joyBase[1][0] = w * 0.925f; joyBase[1][1] = h * 0.74f
            add(1, InputState.PUNCH, "P", w * 0.765f, h - 13 * u, btnR * 0.9f, accent = red)
            add(1, InputState.KICK, "K", w * 0.70f, h - 20 * u, btnR * 0.9f, accent = 0xFFFF8C3A.toInt())
            add(1, InputState.HEAVY, "H", w * 0.655f, h - 9.5f * u, btnR, accent = gold)
            add(1, BLOCK_ID, "B", w * 0.785f, h - 27 * u, btnR * 0.85f, accent = cyan)
            add(1, InputState.S1, "S1", w * 0.605f, h - 26 * u, btnR * 0.78f, 0, cyan)
            add(1, InputState.S2, "S2", w * 0.605f, h - 14.5f * u, btnR * 0.78f, 1, cyan)
            add(1, InputState.ULT, "ULT", w * 0.605f, h - 37.5f * u, btnR * 0.82f, 2, gold)
            add(1, InputState.SWITCH, "TAG", w * 0.845f, h - 9.5f * u, btnR * 0.72f, accent = green)
        }
    }

    private fun joyZone(p: Int, x: Float): Boolean {
        return if (mode == LayoutMode.SINGLE) p == 0 && x < screenW * 0.45f
        else if (p == 0) x < screenW * 0.19f else x > screenW * 0.81f
    }

    fun onTouch(ev: MotionEvent, inputs: Array<InputState>): Boolean {
        val action = ev.actionMasked
        val idx = ev.actionIndex
        val id = ev.getPointerId(idx)
        val x = ev.getX(idx)
        val y = ev.getY(idx)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // buttons first
                for (p in 0..1) {
                    if (mode == LayoutMode.SINGLE && p == 1) continue
                    for (b in buttons[p]) {
                        if (hypot(x - b.cx, y - b.cy) < b.r * 1.35f) {
                            assigns[id] = Assign(p, b, false)
                            b.pressed = true
                            if (b.id == BLOCK_ID) inputs[p].blockHeld = true
                            else inputs[p].press(b.id)
                            return true
                        }
                    }
                }
                // joystick
                for (p in 0..1) {
                    if (mode == LayoutMode.SINGLE && p == 1) continue
                    if (joyPointer[p] == -1 && joyZone(p, x) && y > screenH * 0.3f) {
                        joyPointer[p] = id
                        joyOrigin[p][0] = x; joyOrigin[p][1] = y
                        assigns[id] = Assign(p, null, true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val pid = ev.getPointerId(i)
                    val a = assigns[pid] ?: continue
                    if (a.joy) {
                        var dx = ev.getX(i) - joyOrigin[a.player][0]
                        var dy = ev.getY(i) - joyOrigin[a.player][1]
                        val len = hypot(dx, dy)
                        if (len > joyRadius) { dx *= joyRadius / len; dy *= joyRadius / len }
                        axis[a.player][0] = dx / joyRadius
                        axis[a.player][1] = -dy / joyRadius
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val a = assigns.remove(id)
                if (a != null) {
                    if (a.joy) {
                        joyPointer[a.player] = -1
                        axis[a.player].fill(0f)
                    } else if (a.btn != null) {
                        a.btn.pressed = false
                        if (a.btn.id == BLOCK_ID) inputs[a.player].blockHeld = false
                    }
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    assigns.clear()
                    joyPointer.fill(-1)
                    axis[0].fill(0f); axis[1].fill(0f)
                    for (p in 0..1) {
                        for (b in buttons[p]) b.pressed = false
                        inputs[p].blockHeld = false
                    }
                }
            }
        }
        return true
    }

    /** Feed joystick axes into input states (called each tick, before keyboard overlay). */
    fun applyAxes(inputs: Array<InputState>) {
        for (p in 0..1) {
            if (mode == LayoutMode.SINGLE && p == 1) continue
            if (joyPointer[p] != -1) {
                inputs[p].moveX = axis[p][0]
                inputs[p].moveY = axis[p][1]
            } else {
                inputs[p].moveX = 0f
                inputs[p].moveY = 0f
            }
        }
    }

    fun draw(c: Canvas, battle: TeamBattle?, time: Float) {
        val p = Ui.paint
        for (pl in 0..1) {
            if (mode == LayoutMode.SINGLE && pl == 1) continue
            // joystick
            val bx = joyBase[pl][0]; val by = joyBase[pl][1]
            Ui.circle(c, bx, by, joyRadius, 0x2AFFFFFF)
            Ui.circleStroke(c, bx, by, joyRadius, 0x55FFFFFF, joyRadius * 0.03f)
            // direction hints
            Ui.text(c, "▲", bx, by - joyRadius * 0.62f, joyRadius * 0.3f, 0x66FFFFFF)
            Ui.text(c, "▼", bx, by + joyRadius * 0.82f, joyRadius * 0.3f, 0x66FFFFFF)
            Ui.text(c, "◀", bx - joyRadius * 0.75f, by + joyRadius * 0.1f, joyRadius * 0.3f, 0x66FFFFFF)
            Ui.text(c, "▶", bx + joyRadius * 0.75f, by + joyRadius * 0.1f, joyRadius * 0.3f, 0x66FFFFFF)
            val tx = bx + axis[pl][0] * joyRadius * 0.6f
            val ty = by - axis[pl][1] * joyRadius * 0.6f
            Ui.circle(c, tx, ty, joyRadius * 0.42f, if (joyPointer[pl] != -1) 0xBBFFFFFF.toInt() else 0x77FFFFFF)
            Ui.circleStroke(c, tx, ty, joyRadius * 0.42f, 0xAAFFFFFF.toInt(), joyRadius * 0.025f)

            // buttons
            val fighter = battle?.active(pl)
            for (b in buttons[pl]) {
                val ready = when {
                    b.skillIndex >= 0 && fighter != null -> {
                        val skill = fighter.def.skills[b.skillIndex]
                        fighter.cooldowns[b.skillIndex] <= 0f && fighter.energy >= skill.cost
                    }
                    b.id == InputState.SWITCH && battle != null -> battle.teams[pl].switchCooldown <= 0f
                    else -> true
                }
                val baseAlpha = if (ready) 0xD8 else 0x70
                val bg = (baseAlpha shl 24) or 0x141A28
                Ui.circle(c, b.cx, b.cy, b.r, bg)
                Ui.circleStroke(c, b.cx, b.cy, b.r, if (b.pressed) 0xFFFFFFFF.toInt() else (0xAA000000.toInt() or (b.accent and 0xFFFFFF)), b.r * 0.06f)
                if (b.pressed) Ui.circle(c, b.cx, b.cy, b.r * 0.9f, 0x33FFFFFF)
                val labelColor = if (ready) 0xFFF2F4FA.toInt() else 0xFF7A8090.toInt()
                Ui.text(c, b.label, b.cx, b.cy + b.r * 0.16f, b.r * (if (b.label.length > 2) 0.42f else 0.55f), labelColor)
                // cooldown sweep
                if (b.skillIndex >= 0 && fighter != null) {
                    val skill = fighter.def.skills[b.skillIndex]
                    val cd = fighter.cooldowns[b.skillIndex]
                    if (cd > 0f) {
                        val frac = cd / skill.cooldown
                        p.style = Paint.Style.FILL
                        p.color = 0xAA000000.toInt()
                        val rect = android.graphics.RectF(b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r)
                        c.drawArc(rect, -90f, 360f * frac, true, p)
                        Ui.text(c, String.format("%.0f", cd), b.cx, b.cy + b.r * 0.16f, b.r * 0.5f, 0xFFFFFFFF.toInt())
                    } else if (fighter.energy < skill.cost) {
                        Ui.text(c, "${skill.cost.toInt()}", b.cx, b.cy + b.r * 0.62f, b.r * 0.3f, 0xFFFF8C3A.toInt())
                    } else if (b.skillIndex == 2) {
                        // ultimate ready pulse
                        val pulse = 0.5f + 0.5f * kotlin.math.sin(time * 6f)
                        Ui.circleStroke(c, b.cx, b.cy, b.r * (1.08f + pulse * 0.06f), ((0x66 + (pulse * 120).toInt()) shl 24) or 0xFFC94D, b.r * 0.05f)
                    }
                }
                if (b.id == InputState.SWITCH && battle != null) {
                    val t = battle.teams[pl]
                    val next = t.nextAlive(t.active)
                    if (next >= 0) {
                        Ui.text(c, t.fighters[next].def.name.take(1), b.cx, b.cy + b.r * 0.66f, b.r * 0.34f, 0xFF39FF9C.toInt())
                    }
                    if (t.switchCooldown > 0f) {
                        p.color = 0xAA000000.toInt()
                        val rect = android.graphics.RectF(b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r)
                        c.drawArc(rect, -90f, 360f * (t.switchCooldown / 1.6f), true, p)
                    }
                }
            }
        }
    }
}
