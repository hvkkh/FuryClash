package com.furyclash.game.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.furyclash.game.AppState
import com.furyclash.game.Game
import com.furyclash.game.engine.rgb
import com.furyclash.game.game.Arenas
import com.furyclash.game.game.BattleMode
import com.furyclash.game.game.Characters
import com.furyclash.game.game.Difficulty
import com.furyclash.game.game.DummyMode
import com.furyclash.game.game.Phase
import com.furyclash.game.game.Style
import com.furyclash.game.net.LanDiscovery
import com.furyclash.game.net.NetKind
import com.furyclash.game.net.Proto
import com.furyclash.game.net.Session
import kotlin.math.min
import kotlin.math.sin

/** All non-battle Canvas screens. Buttons register hit rects during draw. */
class Screens(private val game: Game) {
    private class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = ArrayList<Hit>(24)      // live list read by touch
    private val building = ArrayList<Hit>(24)  // built during draw, swapped at end

    fun beginFrame() { building.clear() }

    fun endFrame() {
        hits.clear()
        hits.addAll(building)
    }

    fun touch(x: Float, y: Float): Boolean {
        for (i in hits.indices.reversed()) {
            if (hits[i].rect.contains(x, y)) { hits[i].action(); return true }
        }
        return false
    }

    private fun btn(c: Canvas, label: String, x: Float, y: Float, w: Float, h: Float, accent: Int, enabled: Boolean = true, sub: String? = null, action: () -> Unit) {
        val r = Ui.button(c, label, x, y, w, h, accent, enabled, sub)
        if (enabled) building.add(Hit(r, action))
    }

    private fun dim(c: Canvas, w: Int, h: Int, alpha: Int) {
        Ui.paint.color = (alpha shl 24)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Ui.paint)
    }

    private fun leftFade(c: Canvas, w: Int, h: Int) {
        Ui.paint.shader = LinearGradient(0f, 0f, w * 0.62f, 0f,
            intArrayOf(0xE6070A12.toInt(), 0x00070A12), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Ui.paint)
        Ui.paint.shader = null
    }

    // ---------------- MENU ----------------

    fun drawMenu(c: Canvas, w: Int, h: Int, t: Float) {
        leftFade(c, w, h)
        val u = Ui.unit(w, h)
        val x = w * 0.055f
        Ui.textShadow(c, "FURY CLASH", x, h * 0.24f, 9.5f * u, 0xFFF2F4FA.toInt(), Paint.Align.LEFT, Ui.black, 0.04f)
        Ui.text(c, "3v3 TEAM BATTLE  ·  BEST OF 5  ·  15 ORIGINAL FIGHTERS", x, h * 0.24f + 3.4f * u, 1.9f * u, 0xFFFFC94D.toInt(), Paint.Align.LEFT, spacing = 0.14f)
        val bw = w * 0.24f
        val bh = 6.2f * u
        var y = h * 0.40f
        val step = bh + 1.7f * u
        btn(c, "PLAY", x, y, bw, bh, 0xFFFF5A3C.toInt(), sub = "VS AI  ·  LOCAL 2P") { game.goModeSelect() }
        y += step
        btn(c, "TRAINING", x, y, bw, bh, 0xFF39FF9C.toInt(), sub = "LAB YOUR COMBOS") { game.goSelect(BattleMode.TRAINING) }
        y += step
        btn(c, "SETTINGS", x, y, bw, bh, 0xFF6BE8FF.toInt()) { game.state = AppState.SETTINGS; game.sfx.play("ui") }
        y += step
        btn(c, "HOW TO PLAY", x, y, bw, bh, 0xFFC99CFF.toInt()) { game.openHelp(AppState.MENU) }
        y += step
        btn(c, "EXIT", x, y, bw, bh, 0xFF8A93A8.toInt()) { game.exitGame() }
        Ui.text(c, "AN ORIGINAL 3D FIGHTING GAME  ·  v1.0", x, h - 2.2f * u, 1.5f * u, 0xFF5A6274.toInt(), Paint.Align.LEFT, spacing = 0.1f)
    }

    // ---------------- MODE SELECT ----------------

    fun drawModeSelect(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xB0)
        val u = Ui.unit(w, h)
        Ui.textShadow(c, "CHOOSE YOUR BATTLE", w / 2f, h * 0.16f, 5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.06f)
        val cw = w * 0.30f
        val ch = 17f * u
        val y = h * 0.26f
        btn(c, "VS AI", w / 2f - cw - 2f * u, y, cw, ch, 0xFFFF5A3C.toInt(), sub = "FIGHT A CPU TEAM OF 3") { game.goSelect(BattleMode.VS_AI) }
        btn(c, "LOCAL 2P", w / 2f + 2f * u, y, cw, ch, 0xFF3D7BFF.toInt(), sub = "TWO PLAYERS · ONE SCREEN") { game.goSelect(BattleMode.LOCAL_2P) }
        // difficulty
        Ui.text(c, "AI DIFFICULTY", w / 2f, y + ch + 4.5f * u, 2f * u, 0xFF8A93A8.toInt(), spacing = 0.15f)
        val dw = 12f * u
        val dy = y + ch + 6f * u
        Difficulty.entries.forEachIndexed { i, d ->
            val dx = w / 2f + (i - 1) * (dw + 2f * u) - dw / 2f
            val sel = game.difficulty == d
            val col = when (d) { Difficulty.EASY -> 0xFF39FF9C.toInt(); Difficulty.NORMAL -> 0xFFFFC94D.toInt(); Difficulty.HARD -> 0xFFFF5A3C.toInt() }
            btn(c, d.name, dx, dy, dw, 4.5f * u, col, sub = if (sel) "SELECTED" else null) {
                game.difficulty = d; game.saveSettings(); game.sfx.play("ui")
            }
        }
        btn(c, "MULTIPLAYER", w / 2f - 16f * u, dy + 6.5f * u, 32f * u, 5f * u, 0xFF39FF9C.toInt(), sub = "LAN + ONLINE ROOMS") { game.state = AppState.MP_MENU; game.sfx.play("ui") }
        btn(c, "BACK", 3f * u, h - 8f * u, 14f * u, 5f * u, 0xFF8A93A8.toInt()) { game.goMenu() }
    }

    // ---------------- CHARACTER SELECT ----------------

    fun drawSelect(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xC8)
        val u = Ui.unit(w, h)
        val player = game.selectingPlayer
        val teamColor = if (player == 0) 0xFF3D7BFF.toInt() else 0xFFFF5A3C.toInt()
        val header = when {
            game.mode == BattleMode.LOCAL_2P -> "PLAYER ${player + 1} — PICK 3 FIGHTERS"
            game.mode == BattleMode.TRAINING -> "TRAINING — PICK YOUR TEAM OF 3"
            else -> "PLAYER 1 — PICK 3 FIGHTERS"
        }
        Ui.textShadow(c, header, w * 0.30f, 4.5f * u, 3.2f * u, teamColor, font = Ui.black, spacing = 0.05f)
        // team slots
        for (i in 0 until 3) {
            val sx = w * 0.30f - 8f * u + i * 6.5f * u
            val picked = game.picks[player].getOrNull(i)
            Ui.portrait(c, if (picked != null) Portraits.bitmaps[picked] else null, sx, 6f * u, 5.5f * u, teamColor)
            if (picked == null) Ui.text(c, "${i + 1}", sx + 2.75f * u, 9.4f * u, 2.4f * u, 0xFF5A6274.toInt())
        }
        Ui.text(c, "${game.picks[player].size}/3", w * 0.30f + 12f * u, 9.6f * u, 2.4f * u, 0xFFF2F4FA.toInt(), Paint.Align.LEFT)

        // grid 5x3
        val gx = 2f * u
        val gy = 13f * u
        val gw = w * 0.56f
        val gh = h - gy - 10f * u
        val cellW = gw / 5f
        val cellH = gh / 3f
        for (id in 0 until 15) {
            val col = id % 5
            val row = id / 5
            val cx = gx + col * cellW
            val cy = gy + row * cellH
            val def = Characters.byId(id)
            val pickIdx = game.picks[player].indexOf(id)
            val hovered = game.hoverChar == id
            val ps = min(cellW, cellH) * 0.56f
            val px = cx + cellW / 2f - ps / 2f
            val py = cy + cellH * 0.10f
            if (hovered) Ui.rr(c, cx + 0.5f * u, cy + 0.5f * u, cellW - 1f * u, cellH - 1f * u, 1f * u, 0x33FFFFFF)
            Ui.portrait(c, Portraits.bitmaps[id], px, py, ps, if (pickIdx >= 0) teamColor else 0xFF3A4050.toInt())
            Ui.text(c, def.name.uppercase(), cx + cellW / 2f, py + ps + 2.1f * u, 1.35f * u,
                if (pickIdx >= 0) 0xFFF2F4FA.toInt() else 0xFF9AA3B8.toInt(), spacing = 0.02f)
            if (pickIdx >= 0) {
                Ui.circle(c, px + ps * 0.88f, py + ps * 0.12f, ps * 0.16f, teamColor)
                Ui.text(c, "${pickIdx + 1}", px + ps * 0.88f, py + ps * 0.12f + ps * 0.06f, ps * 0.2f, 0xFFFFFFFF.toInt())
            }
            val rect = RectF(cx, cy, cx + cellW, cy + cellH)
            building.add(Hit(rect) {
                game.hoverChar = id
                game.sfx.play("ui")
                val picks = game.picks[player]
                if (picks.contains(id)) picks.remove(id)
                else if (picks.size < 3) picks.add(id)
            })
        }

        // info panel
        val ix = w * 0.60f
        val iw = w * 0.38f
        val def = Characters.byId(game.hoverChar)
        Ui.rr(c, ix, 4f * u, iw, h - 14f * u, 1.5f * u, 0xCC10141F.toInt())
        Ui.rrStroke(c, ix, 4f * u, iw, h - 14f * u, 1.5f * u, 0x33FFFFFF, 0.25f * u)
        Ui.portrait(c, Portraits.bitmaps[def.id], ix + 2f * u, 6f * u, 10f * u, def.accent)
        Ui.text(c, def.name.uppercase(), ix + 13.5f * u, 9.5f * u, 3f * u, 0xFFF2F4FA.toInt(), Paint.Align.LEFT, Ui.black)
        Ui.text(c, def.title, ix + 13.5f * u, 12.3f * u, 1.7f * u, def.accent, Paint.Align.LEFT)
        Ui.text(c, styleLabel(def.style), ix + 13.5f * u, 14.8f * u, 1.5f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT, spacing = 0.1f)
        // stats
        val stats = listOf(
            "HP" to def.hp / 1300f,
            "POWER" to def.power / 1.35f,
            "SPEED" to def.runSpeed / 6.6f,
            "WEIGHT" to def.weight / 1.5f
        )
        stats.forEachIndexed { i, (label, v) ->
            val sy = 17.5f * u + i * 2.6f * u
            Ui.text(c, label, ix + 2f * u, sy + 1.2f * u, 1.5f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT)
            Ui.bar(c, ix + 9f * u, sy, iw - 12f * u, 1.4f * u, v, def.accent, 0xFF1A2030.toInt())
        }
        // skills
        Ui.text(c, "SPECIAL MOVES", ix + 2f * u, 30f * u, 1.7f * u, 0xFFFFC94D.toInt(), Paint.Align.LEFT, spacing = 0.12f)
        def.skills.forEachIndexed { i, sk ->
            val sy = 32.5f * u + i * 6.4f * u
            val tag = if (i == 2) "ULT" else "S${i + 1}"
            Ui.rr(c, ix + 2f * u, sy, 4.5f * u, 2.2f * u, 0.6f * u, if (i == 2) 0x55FFC94D else 0x556BE8FF)
            Ui.text(c, tag, ix + 4.25f * u, sy + 1.6f * u, 1.4f * u, if (i == 2) 0xFFFFC94D.toInt() else 0xFF6BE8FF.toInt())
            Ui.text(c, sk.name, ix + 7.5f * u, sy + 1.7f * u, 1.9f * u, 0xFFF2F4FA.toInt(), Paint.Align.LEFT)
            Ui.text(c, "${sk.cost.toInt()} EX", ix + iw - 2f * u, sy + 1.7f * u, 1.4f * u, 0xFF8A93A8.toInt(), Paint.Align.RIGHT)
            val desc = if (sk.desc.length > 52) sk.desc.take(51) + "…" else sk.desc
            Ui.text(c, desc, ix + 2f * u, sy + 4.2f * u, 1.45f * u, 0xFF9AA3B8.toInt(), Paint.Align.LEFT)
        }
        Ui.text(c, def.desc, ix + 2f * u, h - 12.5f * u, 1.4f * u, 0xFF6A7284.toInt(), Paint.Align.LEFT)

        // bottom buttons
        val by = h - 8f * u
        btn(c, "BACK", 2f * u, by, 11f * u, 5f * u, 0xFF8A93A8.toInt()) {
            if (game.mode == BattleMode.LOCAL_2P && game.selectingPlayer == 1) { game.selectingPlayer = 0 }
            else game.goModeSelect()
        }
        btn(c, "CLEAR", 14.5f * u, by, 11f * u, 5f * u, 0xFFFF5A3C.toInt()) { game.picks[player].clear() }
        btn(c, "RANDOM", 27f * u, by, 12f * u, 5f * u, 0xFFC99CFF.toInt()) { game.randomTeam(player) }
        val confirmLabel = if (game.mode == BattleMode.LOCAL_2P && player == 0) "NEXT: PLAYER 2" else "CONFIRM TEAM"
        btn(c, confirmLabel, w - 26f * u, by, 24f * u, 5f * u, 0xFF39FF9C.toInt(), game.picks[player].size == 3) { game.confirmPicks() }
    }

    private fun styleLabel(s: Style) = when (s) {
        Style.RUSHDOWN -> "RUSHDOWN · CLOSE-RANGE PRESSURE"
        Style.ZONER -> "ZONER · LONG-RANGE CONTROL"
        Style.BALANCED -> "BALANCED · ALL-ROUNDER"
        Style.GRAPPLER -> "GRAPPLER · HEAVY DAMAGE"
        Style.TRICKY -> "TRICKY · MOBILITY & MIXUPS"
    }

    // ---------------- ARENA SELECT ----------------

    fun drawArenaSelect(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0x99)
        val u = Ui.unit(w, h)
        Ui.textShadow(c, "CHOOSE ARENA", w / 2f, h * 0.14f, 4.5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.06f)
        val cw = w * 0.27f
        val ch = 22f * u
        val y = h * 0.24f
        val themes = listOf(
            intArrayOf(0xFF1A2036.toInt(), 0xFF3D7BFF.toInt()),
            intArrayOf(0xFF3A2430.toInt(), 0xFFFFC98A.toInt()),
            intArrayOf(0xFF241E1E.toInt(), 0xFFFF5A3C.toInt())
        )
        for (i in 0 until 3) {
            val x = w / 2f + (i - 1) * (cw + 2.5f * u) - cw / 2f
            val sel = game.arenaId == i
            Ui.paint.shader = LinearGradient(x, y, x, y + ch, themes[i], null, Shader.TileMode.CLAMP)
            Ui.paint.style = Paint.Style.FILL
            val rect = RectF(x, y, x + cw, y + ch)
            c.drawRoundRect(rect, 1.5f * u, 1.5f * u, Ui.paint)
            Ui.paint.shader = null
            Ui.rrStroke(c, x, y, cw, ch, 1.5f * u, if (sel) 0xFFF2F4FA.toInt() else 0x44FFFFFF, if (sel) 0.5f * u else 0.2f * u)
            Ui.textShadow(c, Arenas.names[i], x + cw / 2f, y + ch * 0.5f, 2.4f * u, 0xFFF2F4FA.toInt(), spacing = 0.04f)
            Ui.text(c, Arenas.subtitles[i], x + cw / 2f, y + ch * 0.5f + 3f * u, 1.7f * u, 0xCCFFFFFF.toInt())
            if (sel) Ui.text(c, "✓ SELECTED", x + cw / 2f, y + ch - 2.5f * u, 1.8f * u, 0xFF39FF9C.toInt(), spacing = 0.1f)
            building.add(Hit(rect) { game.requestArena(i); game.sfx.play("ui") })
        }
        btn(c, "BACK", 3f * u, h - 8f * u, 14f * u, 5f * u, 0xFF8A93A8.toInt()) { game.state = AppState.SELECT }
        btn(c, "START BATTLE", w - 26f * u, h - 8f * u, 23f * u, 5f * u, 0xFFFF5A3C.toInt()) { game.startBattle() }
    }

    // ---------------- SETTINGS ----------------

    fun drawSettings(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xC0)
        val u = Ui.unit(w, h)
        val pw = w * 0.5f
        val px = w / 2f - pw / 2f
        Ui.rr(c, px, h * 0.08f, pw, h * 0.84f, 2f * u, 0xEE10141F.toInt())
        Ui.textShadow(c, "SETTINGS", w / 2f, h * 0.17f, 4f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.08f)
        var y = h * 0.24f
        val rowH = 6f * u
        fun row(label: String, value: String, accent: Int, action: () -> Unit) {
            Ui.text(c, label, px + 3f * u, y + rowH * 0.6f, 2.1f * u, 0xFF9AA3B8.toInt(), Paint.Align.LEFT, spacing = 0.06f)
            btn(c, value, px + pw - 20f * u, y, 17f * u, rowH * 0.85f, accent, action = action)
            y += rowH
        }
        row("AI DIFFICULTY", game.difficulty.name, 0xFFFFC94D.toInt()) {
            game.difficulty = Difficulty.entries[(game.difficulty.ordinal + 1) % 3]; game.saveSettings(); game.sfx.play("ui")
        }
        row("ROUND TIME", if (game.roundTime > 0) "${game.roundTime.toInt()}s" else "∞", 0xFF6BE8FF.toInt()) {
            game.roundTime = when (game.roundTime) { 60f -> 99f; 99f -> 0f; else -> 60f }; game.saveSettings(); game.sfx.play("ui")
        }
        row("SCREEN SHAKE", if (game.shakeEnabled) "ON" else "OFF", 0xFF39FF9C.toInt()) {
            game.shakeEnabled = !game.shakeEnabled; game.saveSettings(); game.sfx.play("ui")
        }
        row("PARTICLES", if (game.particleQuality >= 1f) "HIGH" else "LOW", 0xFFC99CFF.toInt()) {
            game.particleQuality = if (game.particleQuality >= 1f) 0.4f else 1f; game.saveSettings(); game.sfx.play("ui")
        }
        row("SOUND FX", if (game.sfx.enabled) "ON" else "OFF", 0xFFFF8C3A.toInt()) {
            game.sfx.enabled = !game.sfx.enabled; game.saveSettings(); game.sfx.play("ui")
        }
        row("MUSIC", if (game.sfx.musicEnabled) "ON" else "OFF", 0xFFFF3D6E.toInt()) {
            game.sfx.musicEnabled = !game.sfx.musicEnabled; game.saveSettings(); game.sfx.play("ui")
        }
        btn(c, "BACK", w / 2f - 8f * u, h * 0.84f, 16f * u, 5f * u, 0xFF8A93A8.toInt()) { game.goMenu() }
    }

    // ---------------- RESULTS ----------------

    fun drawResults(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xB8)
        val u = Ui.unit(w, h)
        val b = game.battle ?: return
        val winner = b.matchWinner
        val headline = when {
            winner < 0 -> "DRAW"
            game.mode == BattleMode.VS_AI -> if (winner == 0) "YOU WIN!" else "YOU LOSE"
            else -> "TEAM ${winner + 1} WINS!"
        }
        val col = if (winner == 0) 0xFF3D7BFF.toInt() else 0xFFFF5A3C.toInt()
        Ui.textShadow(c, headline, w / 2f, h * 0.24f, 8f * u, if (winner < 0) 0xFFF2F4FA.toInt() else col, font = Ui.black, spacing = 0.05f)
        Ui.textShadow(c, "${b.teams[0].roundsWon}  —  ${b.teams[1].roundsWon}", w / 2f, h * 0.38f, 5f * u, 0xFFF2F4FA.toInt(), font = Ui.black)
        Ui.text(c, "ROUNDS", w / 2f, h * 0.38f + 3f * u, 1.8f * u, 0xFF8A93A8.toInt(), spacing = 0.2f)
        if (winner >= 0) {
            val team = b.teams[winner]
            for (i in 0 until 3) {
                val px = w / 2f + (i - 1) * 9f * u - 4f * u
                Ui.portrait(c, Portraits.bitmaps[team.fighters[i].def.id], px, h * 0.46f, 8f * u, col)
                Ui.text(c, team.fighters[i].def.name.uppercase(), px + 4f * u, h * 0.46f + 10f * u, 1.5f * u, 0xFF9AA3B8.toInt())
            }
        }
        val by = h * 0.78f
        if (game.session != null) {
            btn(c, "RETURN TO LOBBY", w / 2f - 19f * u, by, 20f * u, 5.5f * u, 0xFF39FF9C.toInt()) { game.session?.returnToLobby() }
            btn(c, "MAIN MENU", w / 2f + 3f * u, by, 16f * u, 5.5f * u, 0xFF8A93A8.toInt()) { game.session?.leave(); game.goMenu() }
        } else {
            btn(c, "REMATCH", w / 2f - 25f * u, by, 15f * u, 5.5f * u, 0xFF39FF9C.toInt()) { game.rematch() }
            btn(c, "CHANGE FIGHTERS", w / 2f - 8f * u, by, 17f * u, 5.5f * u, 0xFF6BE8FF.toInt()) { game.goSelect(game.mode) }
            btn(c, "MAIN MENU", w / 2f + 11f * u, by, 14f * u, 5.5f * u, 0xFF8A93A8.toInt()) { game.goMenu() }
        }
    }

    // ---------------- PAUSE ----------------

    fun drawPause(c: Canvas, w: Int, h: Int) {
        dim(c, w, h, 0xA0)
        val u = Ui.unit(w, h)
        val b = game.battle ?: return
        val pw = w * 0.42f
        val px = w / 2f - pw / 2f
        Ui.rr(c, px, h * 0.08f, pw, h * 0.84f, 2f * u, 0xEE10141F.toInt())
        Ui.textShadow(c, "PAUSED", w / 2f, h * 0.18f, 4.5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.1f)
        val bw = pw - 8f * u
        var y = h * 0.24f
        btn(c, "RESUME", px + 4f * u, y, bw, 5.5f * u, 0xFF39FF9C.toInt()) { game.togglePause() }
        y += 6.8f * u
        btn(c, "RESTART MATCH", px + 4f * u, y, bw, 5.5f * u, 0xFFFFC94D.toInt()) { game.rematch() }
        y += 6.8f * u
        if (game.mode == BattleMode.TRAINING) {
            Ui.text(c, "DUMMY BEHAVIOR", w / 2f, y + 1.5f * u, 1.8f * u, 0xFF8A93A8.toInt(), spacing = 0.12f)
            y += 2.8f * u
            val dw = (bw - 3f * u) / 4f
            DummyMode.entries.forEachIndexed { i, d ->
                val sel = b.dummyMode == d
                btn(c, d.name, px + 4f * u + i * (dw + 1f * u), y, dw, 4.2f * u,
                    if (sel) 0xFF39FF9C.toInt() else 0xFF5A6274.toInt()) { b.dummyMode = d; game.sfx.play("ui") }
            }
            y += 5.4f * u
            btn(c, "INFINITE EX: ${if (b.infiniteEnergy) "ON" else "OFF"}", px + 4f * u, y, bw / 2f - 1f * u, 4.2f * u, 0xFF6BE8FF.toInt()) { b.infiniteEnergy = !b.infiniteEnergy }
            btn(c, "RESET STATS", px + 4f * u + bw / 2f + 1f * u, y, bw / 2f - 1f * u, 4.2f * u, 0xFFC99CFF.toInt()) { b.resetTrainingStats() }
            y += 5.4f * u
        } else {
            Ui.text(c, "P / K / H — attacks · B — block (tap just before impact to PARRY)", w / 2f, y + 1.5f * u, 1.6f * u, 0xFF8A93A8.toInt())
            Ui.text(c, "S1 / S2 — specials · ULT — ultimate at 100 EX · TAG — switch fighter", w / 2f, y + 3.8f * u, 1.6f * u, 0xFF8A93A8.toInt())
            Ui.text(c, "Joystick: move · up = jump · down = crouch · full tilt = run", w / 2f, y + 6.1f * u, 1.6f * u, 0xFF8A93A8.toInt())
            y += 8f * u
        }
        btn(c, "QUIT TO MENU", px + 4f * u, h * 0.82f, bw, 5.5f * u, 0xFFFF5A3C.toInt()) { game.goMenu() }
    }


    // ---------------- MULTIPLAYER ----------------

    private fun newSession(kind: NetKind, host: Boolean): Session {
        val name = (android.os.Build.MODEL ?: "Player").take(18)
        val s = Session(game, kind, host, name)
        game.session = s
        return s
    }

    fun drawMpMenu(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xC0)
        val u = Ui.unit(w, h)
        Ui.textShadow(c, "MULTIPLAYER", w / 2f, h * 0.16f, 5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.08f)
        val cw = w * 0.28f
        val ch = 16f * u
        val y = h * 0.28f
        btn(c, "SAME DEVICE", w / 2f - cw * 1.5f - 2f * u, y, cw, ch, 0xFF3D7BFF.toInt(), sub = "2 PLAYERS - 1 SCREEN") { game.goSelect(BattleMode.LOCAL_2P) }
        btn(c, "LOCAL NETWORK", w / 2f - cw / 2f, y, cw, ch, 0xFF39FF9C.toInt(), sub = "LAN - HOST OR JOIN") { game.state = AppState.MP_LAN; game.sfx.play("ui") }
        btn(c, "ONLINE", w / 2f + cw / 2f + 2f * u, y, cw, ch, 0xFFFF5A3C.toInt(), sub = "INTERNET - ROOM CODE") { game.state = AppState.MP_ONLINE; game.sfx.play("ui") }
        Ui.text(c, "Real players only - no bots. Online play uses a relay server (see server/README.md).", w / 2f, y + ch + 4f * u, 1.8f * u, 0xFF8A93A8.toInt())
        btn(c, "BACK", 3f * u, h - 8f * u, 14f * u, 5f * u, 0xFF8A93A8.toInt()) { game.goMenu() }
    }

    fun drawMpLan(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xC0)
        val u = Ui.unit(w, h)
        Ui.textShadow(c, "LOCAL NETWORK", w / 2f, h * 0.14f, 4.5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.06f)
        val lw = w * 0.42f
        btn(c, "HOST ROOM", w * 0.06f, h * 0.24f, lw, 7f * u, 0xFF39FF9C.toInt(), sub = "OTHERS JOIN THIS DEVICE") {
            newSession(NetKind.LAN, true).beginHostLan()
            game.state = AppState.LOBBY
        }
        Ui.text(c, "ROOMS ON THIS NETWORK", w * 0.55f, h * 0.24f, 2f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT, spacing = 0.1f)
        val rooms = LanDiscovery.found.toList()
        if (rooms.isEmpty()) {
            Ui.text(c, "No rooms found yet - scan, or join by IP.", w * 0.55f, h * 0.32f, 1.8f * u, 0xFF5A6274.toInt(), Paint.Align.LEFT)
        }
        rooms.take(3).forEachIndexed { i, r ->
            btn(c, "${r.name}  (${r.address})", w * 0.55f, h * 0.28f + i * 6f * u, w * 0.38f, 5f * u, 0xFF6BE8FF.toInt()) {
                newSession(NetKind.LAN, false).beginJoinLan(r.address, r.port)
                game.state = AppState.LOBBY
            }
        }
        btn(c, "SCAN NETWORK", w * 0.55f, h * 0.52f, 16f * u, 5f * u, 0xFFC99CFF.toInt()) { LanDiscovery.scan() }
        btn(c, "JOIN BY IP", w * 0.55f + 18f * u, h * 0.52f, 16f * u, 5f * u, 0xFFFFC94D.toInt()) {
            game.showTextInput("Join LAN room", "192.168.1.50:7777", "") { text ->
                val parts = text.trim().split(":")
                val ip = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 7777
                if (ip.isNotEmpty()) {
                    newSession(NetKind.LAN, false).beginJoinLan(ip, port)
                    game.state = AppState.LOBBY
                }
            }
        }
        Ui.text(c, "Both devices must be on the same Wi-Fi / LAN. Host shows its IP in the lobby.", w / 2f, h * 0.72f, 1.8f * u, 0xFF8A93A8.toInt())
        btn(c, "BACK", 3f * u, h - 8f * u, 14f * u, 5f * u, 0xFF8A93A8.toInt()) { game.state = AppState.MP_MENU }
    }

    fun drawMpOnline(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xC0)
        val u = Ui.unit(w, h)
        Ui.textShadow(c, "ONLINE MULTIPLAYER", w / 2f, h * 0.14f, 4.5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.06f)
        val pw = w * 0.6f
        val px = w / 2f - pw / 2f
        Ui.text(c, "RELAY SERVER", px, h * 0.24f, 1.9f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT, spacing = 0.1f)
        btn(c, if (game.relayUrl.isEmpty()) "SET RELAY SERVER URL" else game.relayUrl, px, h * 0.27f, pw, 5.5f * u, 0xFF6BE8FF.toInt()) {
            game.showTextInput("Relay server", "wss://your-relay.example.com or ws://host:8080", game.relayUrl) { text ->
                game.relayUrl = text.trim()
                game.saveSettings()
            }
        }
        val enabled = game.relayUrl.isNotEmpty()
        btn(c, "CREATE ROOM", px, h * 0.40f, pw / 2f - 1.5f * u, 8f * u, 0xFF39FF9C.toInt(), enabled, sub = "BECOME HOST") {
            newSession(NetKind.ONLINE, true).beginOnline(true, game.relayUrl, "")
            game.state = AppState.LOBBY
        }
        btn(c, "JOIN ROOM", px + pw / 2f + 1.5f * u, h * 0.40f, pw / 2f - 1.5f * u, 8f * u, 0xFFFF5A3C.toInt(), enabled, sub = "ENTER ROOM CODE") {
            game.showTextInput("Join room", "5-character code", "") { text ->
                val code = text.trim().uppercase()
                if (code.isNotEmpty()) {
                    newSession(NetKind.ONLINE, false).beginOnline(false, game.relayUrl, code)
                    game.state = AppState.LOBBY
                }
            }
        }
        Ui.text(c, "Works across different networks / mobile data. Run server/relay.js (free hosts supported).", w / 2f, h * 0.60f, 1.8f * u, 0xFF8A93A8.toInt())
        game.session?.errorMsg?.let {
            Ui.text(c, it, w / 2f, h * 0.68f, 2f * u, 0xFFFF4D4D.toInt())
        }
        btn(c, "BACK", 3f * u, h - 8f * u, 14f * u, 5f * u, 0xFF8A93A8.toInt()) { game.state = AppState.MP_MENU }
    }

    fun drawLobby(c: Canvas, w: Int, h: Int, t: Float) {
        dim(c, w, h, 0xC8)
        val u = Ui.unit(w, h)
        val s = game.session
        if (s == null) { game.state = AppState.MP_MENU; return }
        val room = s.room
        val kindLabel = if (s.kind == NetKind.LAN) "LAN" else "ONLINE"
        Ui.textShadow(c, "ROOM ${s.roomCode.ifEmpty { "----" }}", w * 0.30f, 5f * u, 4f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.06f)
        Ui.text(c, kindLabel, w * 0.30f + 16f * u, 5f * u, 2f * u, 0xFF39FF9C.toInt(), Paint.Align.LEFT, spacing = 0.15f)
        // connection status
        val conn = when {
            s.connecting -> "CONNECTING..."
            s.link == null || s.link?.alive != true -> "DISCONNECTED"
            else -> "CONNECTED" + if (s.rttMs >= 0) "  -  ${s.rttMs} ms" else ""
        }
        val connCol = if (conn.startsWith("CONNECTED")) 0xFF39FF9C.toInt() else if (conn.startsWith("CONNECTING")) 0xFFFFC94D.toInt() else 0xFFFF4D4D.toInt()
        Ui.circle(c, w * 0.62f, 4.2f * u, 0.8f * u, connCol)
        Ui.text(c, conn, w * 0.64f, 4.9f * u, 1.9f * u, connCol, Paint.Align.LEFT)
        if (s.kind == NetKind.LAN && s.isHost) {
            Ui.text(c, "Host address: ${localIp()}:${7777}", w * 0.62f, 7.4f * u, 1.7f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT)
        }
        // player slots
        val slotW = w * 0.40f
        val slotH = 20f * u
        for (i in 0..1) {
            val x = w * 0.06f + i * (slotW + 3f * u)
            val y = 9f * u
            val p = room?.players?.getOrNull(i)
            Ui.rr(c, x, y, slotW, slotH, 1.5f * u, 0xCC10141F.toInt())
            Ui.rrStroke(c, x, y, slotW, slotH, 1.5f * u, if (p != null && p.connected) 0x66FFFFFF else 0x33FFFFFF, 0.3f * u)
            if (p == null) {
                val pulse = 0.5f + 0.5f * sin(t * 3f)
                Ui.circle(c, x + slotW / 2f, y + slotH * 0.4f, 1.2f * u, ((0x40 + (pulse * 120).toInt()) shl 24) or 0x6BE8FF)
                Ui.text(c, "WAITING FOR PLAYER...", x + slotW / 2f, y + slotH * 0.68f, 2f * u, 0xFF8A93A8.toInt(), spacing = 0.1f)
                Ui.text(c, "SLOT ${i + 1} / 2", x + slotW / 2f, y + slotH * 0.85f, 1.6f * u, 0xFF5A6274.toInt())
            } else {
                Ui.text(c, p.name.uppercase(), x + 2f * u, y + 3.4f * u, 2.4f * u, 0xFFF2F4FA.toInt(), Paint.Align.LEFT)
                if (p.host) Ui.text(c, "HOST", x + slotW - 2f * u, y + 3.4f * u, 1.7f * u, 0xFFFFC94D.toInt(), Paint.Align.RIGHT, spacing = 0.1f)
                Ui.circle(c, x + 2.6f * u, y + 5.6f * u, 0.7f * u, if (p.connected) 0xFF39FF9C.toInt() else 0xFFFF4D4D.toInt())
                Ui.text(c, if (p.connected) "online" else "offline", x + 4f * u, y + 6.2f * u, 1.6f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT)
                Ui.rr(c, x + slotW - 10f * u, y + 4.6f * u, 8f * u, 2.6f * u, 0.8f * u, if (p.ready) 0x5539FF9C else 0x33FFFFFF)
                Ui.text(c, if (p.ready) "READY" else "NOT READY", x + slotW - 6f * u, y + 6.4f * u, 1.6f * u, if (p.ready) 0xFF39FF9C.toInt() else 0xFF8A93A8.toInt())
                if (p.team != null) {
                    for (k in 0..2) Ui.portrait(c, Portraits.bitmaps[p.team[k]], x + 2f * u + k * 6f * u, y + 8.5f * u, 5.5f * u, 0xFF3A4050.toInt())
                } else {
                    Ui.text(c, "No team picked yet", x + 2f * u, y + 12f * u, 1.8f * u, 0xFF5A6274.toInt(), Paint.Align.LEFT)
                }
                Ui.text(c, if (p.token == s.myToken) "YOU" else "OPPONENT", x + 2f * u, y + slotH - 2f * u, 1.6f * u, 0xFF6BE8FF.toInt(), Paint.Align.LEFT, spacing = 0.1f)
            }
        }
        // status / error
        Ui.text(c, s.statusMsg, w / 2f, 32f * u, 2f * u, 0xFFC9D2E4.toInt())
        s.errorMsg?.let { Ui.text(c, it, w / 2f, 35f * u, 2f * u, 0xFFFF4D4D.toInt()) }
        Ui.text(c, "2 players max - each fields a team of 3 - Best of 5", w / 2f, 38f * u, 1.7f * u, 0xFF5A6274.toInt())
        // actions
        val by = h - 16f * u
        val me = room?.players?.firstOrNull { it.token == s.myToken }
        btn(c, "PICK TEAM", w * 0.06f, by, 15f * u, 6f * u, 0xFF6BE8FF.toInt()) {
            game.goSelect(if (s.isHost) BattleMode.ONLINE_HOST else BattleMode.ONLINE_CLIENT)
        }
        btn(c, if (me?.ready == true) "UNREADY" else "READY", w * 0.06f + 17f * u, by, 13f * u, 6f * u, 0xFF39FF9C.toInt()) {
            s.setReady(me?.ready != true)
        }
        val arenaName = com.furyclash.game.game.Arenas.names[s.arena]
        if (s.isHost) {
            btn(c, "ARENA: $arenaName", w * 0.06f + 32f * u, by, 24f * u, 6f * u, 0xFFC99CFF.toInt()) { s.pickArena((s.arena + 1) % 3) }
        } else {
            Ui.text(c, "ARENA: $arenaName", w * 0.06f + 32f * u, by + 3.6f * u, 1.9f * u, 0xFF8A93A8.toInt(), Paint.Align.LEFT)
        }
        if (s.isHost) {
            btn(c, "START MATCH", w - 32f * u, by, 26f * u, 6f * u, 0xFFFF5A3C.toInt(), s.canStart()) { s.startMatch() }
        } else {
            Ui.text(c, if (s.canStartGuestWaiting()) "Waiting for host to start..." else "Ready up and pick a team", w - 19f * u, by + 3.6f * u, 1.9f * u, 0xFF8A93A8.toInt())
        }
        btn(c, "LEAVE ROOM", w - 16f * u, h - 8f * u, 14f * u, 5f * u, 0xFF8A93A8.toInt()) { s.leave(); game.state = AppState.MP_MENU }
    }

    private fun Session.canStartGuestWaiting(): Boolean = room?.players?.size == 2

    private fun localIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    /** In-match net status chip, disconnect grace overlay and net pause menu. */
    fun drawNetOverlay(c: Canvas, w: Int, h: Int, t: Float) {
        val s = game.session ?: return
        val u = Ui.unit(w, h)
        val role = if (s.isHost) "HOST - P1" else "GUEST - P2"
        val kind = if (s.kind == NetKind.LAN) "LAN" else "ONLINE"
        val ping = if (s.rttMs >= 0) "${s.rttMs}ms" else "--"
        Ui.rr(c, 2f * u, h - 5f * u, 30f * u, 3.4f * u, 1f * u, 0xAA10141F.toInt())
        Ui.circle(c, 3.6f * u, h - 3.3f * u, 0.6f * u, if (s.peerConnected) 0xFF39FF9C.toInt() else 0xFFFF4D4D.toInt())
        Ui.text(c, "$kind - $role - $ping", 5f * u, h - 2.7f * u, 1.7f * u, 0xFFC9D2E4.toInt(), Paint.Align.LEFT)
        if (s.lostTimer >= 0f) {
            dim(c, w, h, 0x99)
            val pw = w * 0.5f
            val px = w / 2f - pw / 2f
            Ui.rr(c, px, h * 0.3f, pw, h * 0.34f, 2f * u, 0xEE10141F.toInt())
            Ui.textShadow(c, "OPPONENT DISCONNECTED", w / 2f, h * 0.40f, 3.4f * u, 0xFFFF4D4D.toInt(), font = Ui.black)
            val left = kotlin.math.max(0f, s.graceSec - s.lostTimer).toInt()
            Ui.text(c, "Reconnecting... forfeit in ${left}s", w / 2f, h * 0.47f, 2.2f * u, 0xFFFFC94D.toInt())
            Ui.text(c, s.statusMsg, w / 2f, h * 0.52f, 1.8f * u, 0xFF8A93A8.toInt())
            btn(c, "LEAVE MATCH", w / 2f - 8f * u, h * 0.56f, 16f * u, 5f * u, 0xFF8A93A8.toInt()) { s.leave(); game.goMenu() }
        } else if (game.netMenuOpen) {
            dim(c, w, h, 0x99)
            val pw = w * 0.4f
            val px = w / 2f - pw / 2f
            Ui.rr(c, px, h * 0.3f, pw, h * 0.34f, 2f * u, 0xEE10141F.toInt())
            Ui.textShadow(c, "NET MATCH", w / 2f, h * 0.40f, 3.4f * u, 0xFFF2F4FA.toInt(), font = Ui.black)
            Ui.text(c, "The match keeps running for both players.", w / 2f, h * 0.46f, 1.8f * u, 0xFF8A93A8.toInt())
            btn(c, "RESUME", w / 2f - 17f * u, h * 0.52f, 16f * u, 5f * u, 0xFF39FF9C.toInt()) { game.netMenuOpen = false }
            btn(c, "LEAVE MATCH", w / 2f + 1f * u, h * 0.52f, 16f * u, 5f * u, 0xFFFF5A3C.toInt()) { s.leave(); game.goMenu() }
        }
    }

    // ---------------- HELP ----------------

    fun drawHelp(c: Canvas, w: Int, h: Int) {
        dim(c, w, h, 0xD8)
        val u = Ui.unit(w, h)
        Ui.textShadow(c, "HOW TO PLAY", w / 2f, h * 0.12f, 4.5f * u, 0xFFF2F4FA.toInt(), font = Ui.black, spacing = 0.08f)
        val colW = w * 0.44f
        val lx = w * 0.05f
        val rx = w * 0.53f
        var y = h * 0.20f
        val line = 3.1f * u
        fun left(s: String, col: Int = 0xFFC9D2E4.toInt()) { Ui.text(c, s, lx, y, 1.85f * u, col, Paint.Align.LEFT); y += line }
        fun header(s: String) { Ui.text(c, s, lx, y, 2.1f * u, 0xFFFFC94D.toInt(), Paint.Align.LEFT, spacing = 0.1f); y += line }
        header("TOUCH CONTROLS")
        left("Joystick (left): move · up = jump · down = crouch")
        left("Full tilt = run. Air control is limited — commit to your jumps.")
        left("P = punch · K = kick · H = heavy · B = block (hold)")
        left("Crouch + B blocks low attacks. Stand-block beats overheads.")
        left("Tap B right before impact = PARRY → foe staggers, punish free!")
        left("S1 / S2 = specials (cost EX) · ULT = ultimate (needs 100 EX)")
        left("TAG = switch to next fighter (short cooldown, brief invulnerability)")
        y += line * 0.5f
        header("COMBOS")
        left("P → P → K → H chains on hit. Jump attacks beat crouch block.")
        left("Crouch + K = sweep (knockdown). Damage scales down in long combos.")
        y += line * 0.5f
        header("TEAM RULES")
        left("3v3 · one fighter active per team · first to 3 rounds wins (Bo5).")
        left("KO'd fighters sit out the round; next teammate enters automatically.")
        left("Round timer runs out → team with more total HP takes the round.")
        // right column: keyboard
        var y2 = h * 0.20f
        fun right(s: String, col: Int = 0xFFC9D2E4.toInt()) { Ui.text(c, s, rx, y2, 1.85f * u, col, Paint.Align.LEFT); y2 += line }
        fun header2(s: String) { Ui.text(c, s, rx, y2, 2.1f * u, 0xFF6BE8FF.toInt(), Paint.Align.LEFT, spacing = 0.1f); y2 += line }
        header2("KEYBOARD (EMULATOR / DESKTOP)")
        right("P1: A/D move · W jump · S crouch")
        right("J punch · K kick · L heavy · U block")
        right("I S1 · O S2 · P ultimate · Q tag")
        y2 += line * 0.5f
        right("P2 (local): arrow keys move/jump/crouch")
        right("1 punch · 2 kick · 3 heavy · 4 block")
        right("5 S1 · 6 S2 · 7 ultimate · 8 tag")
        y2 += line * 0.5f
        header2("EX METER")
        right("Build EX by landing hits, taking hits and blocking.")
        right("Specials cost 25–40 EX. Ultimates cost 100 and are unblockable.")
        right("Frozen foes take +20% damage. Burn deals damage over time.")
        btn(c, "GOT IT", w / 2f - 8f * u, h * 0.88f, 16f * u, 5.5f * u, 0xFF39FF9C.toInt()) { game.closeHelp() }
    }
}
