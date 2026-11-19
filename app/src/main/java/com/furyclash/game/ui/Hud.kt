package com.furyclash.game.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.furyclash.game.engine.FightCamera
import com.furyclash.game.engine.clamp
import com.furyclash.game.engine.lerp
import com.furyclash.game.engine.smoothstep
import com.furyclash.game.game.BattleMode
import com.furyclash.game.game.FState
import com.furyclash.game.game.TeamBattle
import kotlin.math.ceil
import kotlin.math.sin

class Hud {
    class Announce(val text: String, val color: Int, val duration: Float) {
        var t = 0f
    }

    private val announces = ArrayList<Announce>(4)
    private val displayHp = floatArrayOf(1f, 1f)
    private val projOut = FloatArray(2)
    var pauseRect = RectF()
    private var vignette: RadialGradient? = null
    private var vigW = 0; private var vigH = 0

    fun addAnnounce(text: String, color: Int, duration: Float) {
        announces.add(Announce(text, color, duration))
        if (announces.size > 3) announces.removeAt(0)
    }

    fun update(dt: Float, battle: TeamBattle?) {
        var i = announces.size - 1
        while (i >= 0) {
            announces[i].t += dt
            if (announces[i].t > announces[i].duration) announces.removeAt(i)
            i--
        }
        if (battle != null) {
            for (p in 0..1) {
                val target = battle.active(p).hpFraction
                displayHp[p] = if (target < displayHp[p]) lerp(displayHp[p], target, clamp(dt * 3f, 0f, 1f)) else target
            }
        }
    }

    fun draw(c: Canvas, w: Int, h: Int, battle: TeamBattle, camera: FightCamera, time: Float) {
        val u = Ui.unit(w, h)
        val p = Ui.paint

        // ---- cinematic flash + vignette ----
        if (battle.world.flash > 0f) {
            p.color = battle.world.flashColor
            p.alpha = (battle.world.flash * 160).toInt()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.alpha = 255
        }
        if (battle.world.cinematicTimer > 0f) {
            if (vignette == null || vigW != w || vigH != h) {
                vignette = RadialGradient(w / 2f, h / 2f, kotlin.math.max(w, h) * 0.62f,
                    intArrayOf(0x00000000, 0xB0000000.toInt()), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
                vigW = w; vigH = h
            }
            p.shader = vignette
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }

        // ---- top HUD strip ----
        val stripH = 13.5f * u
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, stripH,
            intArrayOf(0xD80A0D16.toInt(), 0x000A0D16), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), stripH, p)
        p.shader = null

        val barW = w * 0.5f - 15 * u
        val barH = 3.1f * u
        val barY = 3.2f * u
        for (team in 0..1) {
            val t = battle.teams[team]
            val f = battle.active(team)
            val rtl = team == 1
            val portraitX = if (rtl) w - 11.5f * u else 2.5f * u
            val barX = if (rtl) w - 12.5f * u - barW else 12.5f * u
            val teamColor = if (team == 0) 0xFF3D7BFF.toInt() else 0xFFFF5A3C.toInt()

            Ui.portrait(c, Portraits.bitmaps[f.def.id], portraitX, 1.8f * u, 9f * u, teamColor, !f.alive)
            // name
            val nameX = if (rtl) barX + barW else barX
            val align = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            Ui.text(c, f.def.name.uppercase(), nameX, barY - 0.7f * u, 2.5f * u, 0xFFF2F4FA.toInt(), align, spacing = 0.05f)
            // trailing damage + hp
            Ui.bar(c, barX, barY, barW, barH, displayHp[team], 0xFFF2F4FA.toInt(), 0xE610131A.toInt(), rtl, barH * 0.3f)
            val frac = f.hpFraction
            val hpColor = when {
                frac > 0.5f -> 0xFF39D97A.toInt()
                frac > 0.25f -> 0xFFFFC94D.toInt()
                else -> 0xFFFF4D4D.toInt()
            }
            Ui.bar(c, barX, barY, barW, barH, frac, hpColor, 0x00000000, rtl, barH * 0.3f)
            Ui.rrStroke(c, barX, barY, barW, barH, barH * 0.3f, 0x66FFFFFF, 0.35f * u)

            // team mini portraits + mini hp + active indicator
            val mini = 4.6f * u
            val miniY = barY + barH + 1.1f * u
            for (i in 0 until 3) {
                val mx = if (rtl) barX + barW - (i + 1) * (mini + 1.6f * u) else barX + i * (mini + 1.6f * u)
                val bf = t.fighters[i]
                val isActive = i == t.active
                Ui.portrait(c, Portraits.bitmaps[bf.def.id], mx, miniY, mini,
                    if (isActive) 0xFFFFC94D.toInt() else 0xFF3A4050.toInt(), !bf.alive)
                Ui.bar(c, mx, miniY + mini + 0.4f * u, mini, 0.7f * u, bf.hpFraction,
                    if (bf.alive) teamColor else 0xFF3A3F4A.toInt(), 0xE610131A.toInt(), rtl, 0.3f * u)
                if (isActive && bf.alive) {
                    Ui.text(c, "▼", mx + mini / 2f, miniY - 0.4f * u, 1.8f * u, 0xFFFFC94D.toInt())
                }
            }
            // energy meter
            val exX = if (rtl) barX + barW - 26 * u else barX
            val exY = miniY + mini + 1.7f * u
            val exW = 26 * u
            val exH = 1.5f * u
            val full = f.energy >= 100f
            val exColor = if (full) 0xFFFFC94D.toInt() else 0xFF6BE8FF.toInt()
            Ui.bar(c, exX, exY, exW, exH, f.energy / 100f, exColor, 0xE610131A.toInt(), rtl, exH * 0.4f)
            for (seg in 1..3) {
                val sx = exX + exW * seg / 4f
                p.color = 0x88000000.toInt()
                c.drawRect(sx - 0.15f * u, exY, sx + 0.15f * u, exY + exH, p)
            }
            Ui.text(c, if (full) "ULT READY!" else "EX", if (rtl) exX - 1f * u else exX + exW + 1f * u, exY + exH * 0.9f,
                1.6f * u, if (full) 0xFFFFC94D.toInt() else 0xFF8A93A8.toInt(),
                if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT)
        }

        // ---- center: timer / round / score ----
        val cx = w / 2f
        Ui.rr(c, cx - 6.5f * u, 1.2f * u, 13 * u, 8.5f * u, 1.5f * u, 0xE610131A.toInt())
        Ui.rrStroke(c, cx - 6.5f * u, 1.2f * u, 13 * u, 8.5f * u, 1.5f * u, 0x55FFFFFF, 0.3f * u)
        val tSec = if (battle.roundTime > 0f) ceil(battle.timer).toInt().toString() else "∞"
        val urgent = battle.roundTime > 0f && battle.timer < 10f
        Ui.textShadow(c, tSec, cx, 7.2f * u, 5.4f * u, if (urgent) 0xFFFF4D4D.toInt() else 0xFFF2F4FA.toInt(), font = Ui.black)
        Ui.text(c, "ROUND ${battle.round}", cx, 9.2f * u, 1.6f * u, 0xFF8A93A8.toInt(), spacing = 0.1f)
        // score pips
        for (team in 0..1) {
            for (i in 0 until 3) {
                val px = if (team == 0) cx - 8.5f * u - i * 2.4f * u else cx + 8.5f * u + i * 2.4f * u
                val won = i < battle.teams[team].roundsWon
                val col = if (won) (if (team == 0) 0xFF3D7BFF.toInt() else 0xFFFF5A3C.toInt()) else 0xFF2A3040.toInt()
                p.color = col
                val r = 0.9f * u
                val path = android.graphics.Path()
                path.moveTo(px, 4.2f * u - r); path.lineTo(px + r, 4.2f * u); path.lineTo(px, 4.2f * u + r); path.lineTo(px - r, 4.2f * u)
                path.close()
                c.drawPath(path, p)
            }
        }

        // ---- combo counters ----
        for (team in 0..1) {
            val f = battle.active(team)
            if (f.comboDealt >= 2 && f.comboResetTimer > 0f) {
                val ccx = if (team == 0) w * 0.16f else w * 0.84f
                val pulse = 1f + 0.12f * sin(time * 14f)
                Ui.textShadow(c, "${f.comboDealt}", ccx, h * 0.40f, 7f * u * pulse,
                    if (team == 0) 0xFF6BE8FF.toInt() else 0xFFFF8C3A.toInt(), font = Ui.black)
                Ui.textShadow(c, "HITS!", ccx, h * 0.40f + 3.4f * u, 2.6f * u, 0xFFF2F4FA.toInt(), spacing = 0.12f)
            }
        }

        // ---- floating damage numbers + player markers ----
        for (d in battle.world.damageNumbers) {
            if (camera.project(d.x, d.y, d.z, w, h, projOut)) {
                val a = clamp(1f - d.t / 0.9f, 0f, 1f)
                val size = (if (d.big) 3.4f else 2.5f) * u * (1f + 0.25f * (1f - smoothstep(d.t / 0.15f)))
                Ui.text(c, d.text, projOut[0], projOut[1], size, d.color, alpha = (a * 255).toInt(), font = Ui.black)
            }
        }
        for (team in 0..1) {
            val f = battle.active(team)
            if (f.alive && battle.phase == com.furyclash.game.game.Phase.FIGHT && camera.project(f.x, f.y + 2.15f, 0f, w, h, projOut)) {
                val col = if (team == 0) 0xFF3D7BFF.toInt() else 0xFFFF5A3C.toInt()
                p.color = col
                val path = android.graphics.Path()
                val r = 1.1f * u
                path.moveTo(projOut[0], projOut[1] + r); path.lineTo(projOut[0] - r, projOut[1] - r * 0.4f); path.lineTo(projOut[0] + r, projOut[1] - r * 0.4f)
                path.close()
                c.drawPath(path, p)
                Ui.text(c, "P${team + 1}", projOut[0], projOut[1] - r * 0.8f, 1.5f * u, col)
            }
        }

        // ---- training panel ----
        if (battle.mode == BattleMode.TRAINING) {
            val pw = 30f * u; val ph = 10f * u
            val px = w / 2f - pw / 2f; val py = h - ph - 2f * u
            Ui.rr(c, px, py, pw, ph, 1.2f * u, 0xCC0A0D16.toInt())
            Ui.rrStroke(c, px, py, pw, ph, 1.2f * u, 0x44FFFFFF, 0.25f * u)
            Ui.text(c, "TRAINING", px + pw / 2f, py + 2.2f * u, 1.8f * u, 0xFF39FF9C.toInt(), spacing = 0.15f)
            Ui.text(c, "LAST  ${battle.statsLastDamage.toInt()}      TOTAL  ${battle.statsTotalDamage.toInt()}      MAX COMBO  ${battle.statsMaxCombo}",
                px + pw / 2f, py + 5f * u, 1.9f * u, 0xFFF2F4FA.toInt())
            Ui.text(c, "DUMMY: ${battle.dummyMode.name}   ·   pause to change", px + pw / 2f, py + 7.8f * u, 1.6f * u, 0xFF8A93A8.toInt())
        }

        // ---- announcements ----
        for (a in announces) {
            val inT = smoothstep(a.t / 0.18f)
            val outT = clamp((a.duration - a.t) / 0.3f, 0f, 1f)
            val scale = 1f + 0.35f * (1f - inT)
            val alpha = (inT * outT * 255).toInt()
            Ui.textShadow(c, a.text, cx, h * 0.36f, 6.5f * u * scale, a.color, font = Ui.black, spacing = 0.08f, alpha = alpha)
        }

        // ---- pause button ----
        val pr = 2.6f * u
        pauseRect = RectF(w - pr * 2.6f, 1.4f * u, w - pr * 0.6f, 1.4f * u + pr * 2f)
        Ui.rr(c, pauseRect.left, pauseRect.top, pauseRect.width(), pauseRect.height(), 0.8f * u, 0xAA10131A.toInt())
        Ui.text(c, "❚❚", pauseRect.centerX(), pauseRect.centerY() + 0.9f * u, 2.2f * u, 0xCCFFFFFF.toInt())
    }
}
