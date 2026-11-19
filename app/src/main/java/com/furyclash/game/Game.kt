package com.furyclash.game

import android.graphics.Canvas
import android.view.MotionEvent
import com.furyclash.game.audio.Sfx
import com.furyclash.game.engine.FightCamera
import com.furyclash.game.engine.M4
import com.furyclash.game.engine.Mesh
import com.furyclash.game.engine.MeshBuilder
import com.furyclash.game.engine.ParticleSystem
import com.furyclash.game.engine.Renderer
import com.furyclash.game.engine.Vec3
import com.furyclash.game.engine.clamp
import com.furyclash.game.engine.rgb
import com.furyclash.game.game.Anims
import com.furyclash.game.game.Arena
import com.furyclash.game.game.Arenas
import com.furyclash.game.game.BattleMode
import com.furyclash.game.game.Characters
import com.furyclash.game.game.CombatWorld
import com.furyclash.game.game.Difficulty
import com.furyclash.game.game.DummyMode
import com.furyclash.game.game.FState
import com.furyclash.game.game.Fighter
import com.furyclash.game.game.GameEvent
import com.furyclash.game.game.Joints
import com.furyclash.game.game.ModelRegistry
import com.furyclash.game.game.Phase
import com.furyclash.game.game.TeamBattle
import com.furyclash.game.input.InputState
import com.furyclash.game.input.TouchControls
import com.furyclash.game.net.EvtCodec
import com.furyclash.game.net.Proto
import com.furyclash.game.net.Session
import com.furyclash.game.ui.Hud
import com.furyclash.game.ui.Portraits
import com.furyclash.game.ui.Screens
import kotlin.math.abs
import kotlin.math.sin

enum class AppState { MENU, MODE_SELECT, SELECT, ARENA_SELECT, BATTLE, RESULTS, SETTINGS, HELP, MP_MENU, MP_LAN, MP_ONLINE, LOBBY }

/**
 * Central game state: owns the simulation, camera, UI state machine and the
 * bridge between the GL thread (tick + 3D) and the UI thread (Canvas overlay).
 * All public entry points are called under synchronized(game).
 */
class Game(private val activity: GameActivity) {
    var state = AppState.MENU
    var paused = false
    var time = 0f

    // settings
    var difficulty = Difficulty.NORMAL
    var roundTime = 99f
    var shakeEnabled = true
    var particleQuality = 1f

    // selection flow
    var mode = BattleMode.VS_AI
    val picks = arrayOf(ArrayList<Int>(3), ArrayList<Int>(3))
    var selectingPlayer = 0
    var hoverChar = 0
    var arenaId = 0

    // runtime
    val particles = ParticleSystem(2048)
    val camera = FightCamera()
    val world = CombatWorld(particles, camera)
    var battle: TeamBattle? = null
    var arena: Arena? = null
    val inputs = arrayOf(InputState(), InputState())
    val controls = TouchControls()
    val hud = Hud()
    val screens = Screens(this)
    lateinit var sfx: Sfx
    var session: Session? = null
    var netMenuOpen = false
    var relayUrl = ""

    fun showTextInput(title: String, hint: String, initial: String, cb: (String) -> Unit) =
        activity.showTextInput(title, hint, initial, cb)

    fun clearNetResume() = activity.clearNetResume()

    private val neutral = InputState()
    private val showcase = arrayOf(
        Fighter(Characters.all[9], 0, 0),
        Fighter(Characters.all[12], 1, 0)
    )
    private val previewPose = FloatArray(Joints.POSE_SIZE)
    private var resultsTimer = 0f
    private var screenW = 0
    private var screenH = 0

    // shared render assets
    private val identity = FloatArray(16).also { M4.identity(it) }
    private val mTmp = FloatArray(16)
    private val mTmp2 = FloatArray(16)
    private val mModel = FloatArray(16)
    private val tintArr = FloatArray(3)
    var unitSphere: Mesh? = null
    var unitBox: Mesh? = null
    var disc: Mesh? = null
    var shadowDisc: Mesh? = null

    fun initGl(renderer: Renderer) {
        val b = MeshBuilder()
        b.sphere(0f, 0f, 0f, 1f, 8, 12, rgb(0xFFFFFF))
        unitSphere = b.build()
        val b2 = MeshBuilder()
        b2.box(0f, 0f, 0f, 1f, 1f, 1f, rgb(0xFFFFFF))
        unitBox = b2.build()
        val b3 = MeshBuilder()
        b3.cylinder(0f, 0f, 0f, 1f, 1f, 0.02f, 24, rgb(0xFFFFFF), 0.5f)
        disc = b3.build()
        val b4 = MeshBuilder()
        b4.cylinder(0f, 0f, 0f, 1f, 1f, 0.01f, 20, rgb(0x05060A))
        shadowDisc = b4.build()
        rebuildArena()
        Portraits.onContextLost()
        showcase[0].x = -1.7f; showcase[0].facing = 1
        showcase[1].x = 1.7f; showcase[1].facing = -1
        showcase[0].enterState(FState.IDLE)
        showcase[1].enterState(FState.IDLE)
    }

    fun rebuildArena() {
        arena?.mesh?.dispose()
        arena = Arenas.build(arenaId)
        world.arenaHalf = arena?.halfWidth ?: 6.5f
    }

    fun onScreenSize(w: Int, h: Int) {
        screenW = w; screenH = h
        relayoutControls()
    }

    private fun relayoutControls() {
        if (screenW == 0) return
        val dual = state == AppState.BATTLE && mode == BattleMode.LOCAL_2P
        controls.layout(screenW, screenH, if (dual) TouchControls.LayoutMode.DUAL else TouchControls.LayoutMode.SINGLE)
    }

    // ---------------- navigation ----------------

    fun goMenu() {
        session?.leave()
        session = null
        activity.clearNetResume()
        battle = null
        world.reset()
        world.listener = null
        world.p1 = null
        world.p2 = null
        paused = false
        state = AppState.MENU
        relayoutControls()
    }

    fun goModeSelect() { state = AppState.MODE_SELECT; sfx.play("ui") }

    private var pendingArena = -1
    private var helpReturn = AppState.MENU

    fun requestArena(i: Int) { pendingArena = i }

    fun openHelp(from: AppState) { helpReturn = from; state = AppState.HELP; sfx.play("ui") }

    fun closeHelp() { state = helpReturn; sfx.play("ui") }

    fun goSelect(m: BattleMode) {
        mode = m
        picks[0].clear(); picks[1].clear()
        selectingPlayer = 0
        hoverChar = 0
        state = AppState.SELECT
        sfx.play("ui")
    }

    fun randomTeam(player: Int) {
        val pool = (0..14).toMutableList()
        pool.shuffle()
        picks[player].clear()
        picks[player].addAll(pool.take(3))
        sfx.play("ui")
    }

    fun confirmPicks() {
        if (picks[selectingPlayer].size < 3) return
        sfx.play("ui")
        if (mode == BattleMode.LOCAL_2P && selectingPlayer == 0) {
            selectingPlayer = 1
            hoverChar = 0
            return
        }
        if (mode == BattleMode.ONLINE_HOST || mode == BattleMode.ONLINE_CLIENT) {
            session?.setTeam(picks[0].toIntArray())
            state = AppState.LOBBY
            return
        }
        if (mode != BattleMode.LOCAL_2P) randomTeam(1)
        state = AppState.ARENA_SELECT
    }

    fun startBattle() {
        val defs0 = picks[0].map { Characters.byId(it) }
        val defs1 = picks[1].map { Characters.byId(it) }
        battle?.let { world.listener = null }
        world.reset()
        val b = TeamBattle(world, defs0, defs1, mode, difficulty, if (mode == BattleMode.TRAINING) 0f else roundTime)
        b.externalListener = { e -> onBattleEvent(e) }
        if (mode == BattleMode.TRAINING) {
            b.dummyMode = DummyMode.STAND
            b.infiniteEnergy = true
        }
        battle = b
        world.sfx = { name -> sfx.play(name) }
        paused = false
        resultsTimer = 0f
        state = AppState.BATTLE
        relayoutControls()
        hud.addAnnounce(if (mode == BattleMode.TRAINING) "TRAINING" else "GET READY", 0xFF39FF9C.toInt(), 1.2f)
        sfx.play("fight")
    }

    private fun onBattleEvent(e: GameEvent) {
        when (e) {
            is GameEvent.Announce -> hud.addAnnounce(e.text, e.color, e.duration)
            is GameEvent.Parry -> hud.addAnnounce("COUNTER!", 0xFFFFD24D.toInt(), 0.7f)
            is GameEvent.Frozen -> hud.addAnnounce("FROZEN!", 0xFF9BE8FF.toInt(), 0.7f)
            is GameEvent.MatchEnd -> { resultsTimer = 0.0001f }
            else -> {}
        }
    }

    /** Start a synchronized network match (host simulates, guest replicates). */
    fun startNetBattle(t0: IntArray, t1: IntArray, arenaIdx: Int, rt: Float, asClient: Boolean) {
        battle?.let { world.listener = null }
        world.reset()
        arenaId = arenaIdx
        rebuildArena()
        mode = if (asClient) BattleMode.ONLINE_CLIENT else BattleMode.ONLINE_HOST
        val defs0 = t0.map { Characters.byId(it) }
        val defs1 = t1.map { Characters.byId(it) }
        val b = TeamBattle(world, defs0, defs1, mode, difficulty, rt)
        val s = session
        b.externalListener = if (asClient) { e ->
            if (e is GameEvent.MatchEnd) resultsTimer = 0.0001f
        } else { e ->
            onBattleEvent(e)
            forwardEvent(e)
        }
        battle = b
        world.sfx = if (asClient) { name -> sfx.play(name) } else { name ->
            sfx.play(name)
            s?.link?.send(EvtCodec.sound(name))
        }
        paused = false
        netMenuOpen = false
        resultsTimer = 0f
        state = AppState.BATTLE
        relayoutControls()
        s?.let { if (it.kind == com.furyclash.game.net.NetKind.ONLINE) activity.saveNetResume(it) }
        hud.addAnnounce(if (asClient) "CONNECTED - FIGHT!" else "NET MATCH - FIGHT!", 0xFF39FF9C.toInt(), 1.2f)
        sfx.play("fight")
    }

    private fun forwardEvent(e: GameEvent) {
        val l = session?.link ?: return
        when (e) {
            is GameEvent.Hit -> l.send(EvtCodec.hit(e.attacker.team, e.victim.team, e.damage, e.combo, e.heavy, e.victim.x, e.victim.y + 1.9f))
            is GameEvent.Blocked -> l.send(EvtCodec.simple(EvtCodec.BLOCKED, e.victim.team))
            is GameEvent.Parry -> { l.send(EvtCodec.simple(EvtCodec.PARRY, e.defender.team)); l.send(EvtCodec.announce("COUNTER!", 0xFFFFD24D.toInt(), 0.7f)) }
            is GameEvent.Shielded -> l.send(EvtCodec.simple(EvtCodec.SHIELDED, e.victim.team))
            is GameEvent.KO -> l.send(EvtCodec.simple(EvtCodec.KO, e.victim.team))
            is GameEvent.Frozen -> { l.send(EvtCodec.simple(EvtCodec.FROZEN, e.victim.team)); l.send(EvtCodec.announce("FROZEN!", 0xFF9BE8FF.toInt(), 0.7f)) }
            is GameEvent.Switch -> l.send(EvtCodec.switch(e.team, e.f.slot))
            is GameEvent.RoundStart -> l.send(EvtCodec.roundStart(e.round))
            is GameEvent.RoundEnd -> l.send(EvtCodec.roundEnd(e.winnerTeam, e.reason))
            is GameEvent.MatchEnd -> l.send(EvtCodec.matchEnd(e.winnerTeam))
            is GameEvent.Announce -> l.send(EvtCodec.announce(e.text, e.color, e.duration))
            is GameEvent.SkillUsed -> l.send(EvtCodec.skill(e.f.team, e.f.def.skills.indexOf(e.skill), false))
            is GameEvent.Ultimate -> l.send(EvtCodec.skill(e.f.team, 2, true))
            is GameEvent.Fight -> l.send(EvtCodec.simple(EvtCodec.FIGHT, 0))
            else -> {}
        }
    }

    /** Guest-side: replay host gameplay events (sound, announcements, numbers, VFX). */
    fun applyNetEvent(blob: ByteArray) {
        val d = Proto.reader(blob)
        val b = battle ?: return
        when (d.readByte().toInt()) {
            EvtCodec.HIT -> {
                d.readByte(); d.readByte()
                val dmg = d.readFloat(); d.readByte(); val heavy = d.readBoolean()
                val x = d.readFloat(); val y = d.readFloat()
                world.damageNumbers.add(com.furyclash.game.game.DamageNumber(x, y, 0f, dmg.toInt().toString(),
                    if (heavy) 0xFFFFC94D.toInt() else 0xFFFFFFFF.toInt(), 0f, heavy))
                world.burst(x, y - 0.8f, rgb(if (heavy) 0xFFC94D else 0xFFF2E0), if (heavy) 22 else 12, if (heavy) 4f else 2.8f)
                camera.shake(if (heavy) 0.5f else 0.24f)
            }
            EvtCodec.BLOCKED -> { val t = d.readByte().toInt(); world.burst(b.active(t).x, 1.1f, rgb(0x6BA8FF), 8, 2f) }
            EvtCodec.PARRY -> { val t = d.readByte().toInt(); world.burst(b.active(t).x, 1.1f, rgb(0xFFD24D), 22, 3.5f); camera.shake(0.35f) }
            EvtCodec.SHIELDED -> { val t = d.readByte().toInt(); world.burst(b.active(t).x, 1.1f, rgb(0xC99CFF), 16, 2.5f) }
            EvtCodec.KO -> { val t = d.readByte().toInt(); world.burst(b.active(t).x, 1f, rgb(0xFF5A3C), 30, 4.5f); camera.shake(0.8f) }
            EvtCodec.ANNOUNCE -> hud.addAnnounce(d.readUTF(), d.readInt(), d.readFloat())
            EvtCodec.SOUND -> sfx.play(d.readUTF())
            EvtCodec.SWITCH -> { val t = d.readByte().toInt(); d.readByte(); world.burst(b.active(t).x, 1f, rgb(if (t == 0) 0x3D7BFF else 0xFF5A3C), 16, 3f) }
            EvtCodec.FROZEN -> {}
            EvtCodec.ROUND_START -> d.readByte()
            EvtCodec.ROUND_END -> { d.readByte(); d.readUTF() }
            EvtCodec.MATCH_END -> { resultsTimer = 0.0001f }
            EvtCodec.FIGHT -> {}
            EvtCodec.SKILL -> {
                val t = d.readByte().toInt(); val idx = d.readByte().toInt(); val cin = d.readBoolean()
                val f = b.active(t)
                val col = rgb(f.def.skills[idx].color)
                world.burst(f.x + f.facing * 0.6f, 1.1f, col, 10, 2.5f)
                if (cin) camera.shake(0.5f)
            }
            else -> {}
        }
    }

    /** Match finished -> both players back to the lobby for a rematch. */
    fun endNetMatchToLobby() {
        activity.clearNetResume()
        battle = null
        world.reset()
        world.listener = null
        world.p1 = null; world.p2 = null
        paused = false
        netMenuOpen = false
        state = AppState.LOBBY
        relayoutControls()
    }

    fun togglePause() {
        if (state != AppState.BATTLE) return
        if (session != null && session!!.inMatch) {
            netMenuOpen = !netMenuOpen
            sfx.play("ui")
            return
        }
        paused = !paused
        sfx.play("ui")
    }

    fun rematch() { startBattle() }

    fun exitGame() { activity.finishAffinity() }

    fun saveSettings() { activity.saveSettings() }

    // ---------------- tick (GL thread) ----------------

    fun tick(dt: Float) {
        time += dt
        if (pendingArena >= 0) {
            arenaId = pendingArena
            pendingArena = -1
            rebuildArena()
        }
        inputs[0].update(dt)
        inputs[1].update(dt)
        controls.applyAxes(inputs)
        activity.applyKeyboard(inputs, mode == BattleMode.LOCAL_2P && state == AppState.BATTLE)
        session?.tick(dt)

        val a = arena
        when (state) {
            AppState.BATTLE -> {
                val b = battle ?: return
                if (!paused) {
                    if (mode == BattleMode.ONLINE_CLIENT) b.remoteVisualTick(dt) else b.update(dt, inputs)
                    a?.ambientParticles(particles, dt, time)
                    if (b.phase == Phase.MATCH_END) {
                        resultsTimer += dt
                        if (resultsTimer > 2.8f) {
                            state = AppState.RESULTS
                            relayoutControls()
                        }
                    }
                }
                hud.update(dt, b)
                updateBattleCamera(b)
            }
            AppState.RESULTS -> {
                if (mode == BattleMode.ONLINE_CLIENT) battle?.remoteVisualTick(dt) else battle?.update(dt, inputs)
                battle?.let { updateBattleCamera(it) }
                particles.update(dt)
            }
            else -> {
                // menu showcase / select preview
                neutral.reset()
                if (state == AppState.SELECT) {
                    val f = showcase[0]
                    f.x = 0f; f.y = 0f; f.facing = 1
                    Anims.compute("idle", time, previewPose)
                    previewPose[40] = sin(time * 0.5f) * 0.6f
                    f.model.compute(0f, 0f, 1, previewPose)
                } else {
                    showcase[0].update(dt, neutral, world, showcase[1])
                    showcase[1].update(dt, neutral, world, showcase[0])
                }
                a?.ambientParticles(particles, dt, time)
                particles.update(dt)
                camera.followSpeed = 3f
                camera.goalEye = Vec3(sin(time * 0.12f) * 1.2f, 2.0f, 6.8f)
                camera.goalTarget = Vec3(0f, 1.15f, 0f)
                camera.goalFov = 45f
            }
        }
        if (!shakeEnabled) camera.shakeAmount = 0f
        particles.density = particleQuality
    }

    private fun updateBattleCamera(b: TeamBattle) {
        val f0 = b.active(0)
        val f1 = b.active(1)
        val cin = world.cinematicTimer > 0f && world.cinematicFocus != null
        if (cin) {
            val f = world.cinematicFocus!!
            camera.followSpeed = 10f
            camera.goalEye = Vec3(f.x - f.facing * 1.4f, 1.5f, 3.1f)
            camera.goalTarget = Vec3(f.x, 1.15f, 0f)
            camera.goalFov = 37f
        } else {
            val mid = clamp((f0.x + f1.x) / 2f, -4.5f, 4.5f)
            val sep = abs(f0.x - f1.x)
            val dist = clamp(4.4f + sep * 0.55f, 4.6f, 9.6f)
            camera.followSpeed = 6f
            camera.goalEye = Vec3(mid, 1.95f + sep * 0.04f, dist)
            camera.goalTarget = Vec3(mid, 1.15f, 0f)
            camera.goalFov = 45f
        }
    }

    // ---------------- 3D scene (GL thread) ----------------

    fun drawScene(renderer: Renderer, w: Int, h: Int, dt: Float) {
        val a = arena ?: return
        a.apply(renderer)
        camera.update(dt, w.toFloat() / h.coerceAtLeast(1))
        renderer.beginFrame(camera)
        renderer.draw(a.mesh, identity)

        val b = battle
        if ((state == AppState.BATTLE || state == AppState.RESULTS) && b != null) {
            // ground shadows + team markers
            for (team in 0..1) {
                val f = b.active(team)
                shadowDisc?.let {
                    M4.translation(mTmp, f.x, 0.012f, 0f)
                    M4.scaling(mTmp2, 0.5f, 1f, 0.34f)
                    M4.multiply(mModel, mTmp, mTmp2)
                    renderer.draw(it, mModel)
                }
                if (f.alive) {
                    disc?.let {
                        M4.translation(mTmp, f.x, 0.02f, 0f)
                        M4.scaling(mTmp2, 0.52f, 1f, 0.52f)
                        M4.multiply(mModel, mTmp, mTmp2)
                        renderer.draw(it, mModel, rgb(if (team == 0) 0x3D7BFF else 0xFF5A3C), 0.7f)
                    }
                }
            }
            // fighters
            for (team in 0..1) {
                val f = b.active(team)
                val hasTint = f.tint(tintArr)
                f.model.draw(renderer, if (hasTint) tintArr else null, if (f.energy >= 100f) 1.25f else 1f)
            }
            // projectiles
            unitSphere?.let { sph ->
                for (pr in world.projectiles) {
                    M4.translation(mTmp, pr.x, pr.y, pr.z)
                    val r = pr.radius
                    M4.scaling(mTmp2, r, r, r)
                    M4.multiply(mModel, mTmp, mTmp2)
                    renderer.draw(sph, mModel, pr.color, 2.4f)
                }
            }
            // beams
            unitBox?.let { box ->
                for (beam in world.beams) {
                    val k = beam.life / beam.maxLife
                    M4.translation(mTmp, beam.x + beam.dir * beam.len / 2f, beam.y, 0.1f)
                    M4.scaling(mTmp2, beam.len, beam.radius * 2f * k, beam.radius * 2f * k)
                    M4.multiply(mModel, mTmp, mTmp2)
                    renderer.draw(box, mModel, beam.color, 3f * k)
                }
            }
        } else if (state == AppState.SELECT) {
            shadowDisc?.let {
                M4.translation(mTmp, 0f, 0.012f, 0f)
                M4.scaling(mTmp2, 0.55f, 1f, 0.4f)
                M4.multiply(mModel, mTmp, mTmp2)
                renderer.draw(it, mModel)
            }
            showcase[0].model.draw(renderer, null)
        } else {
            for (f in showcase) {
                shadowDisc?.let {
                    M4.translation(mTmp, f.x, 0.012f, 0f)
                    M4.scaling(mTmp2, 0.5f, 1f, 0.34f)
                    M4.multiply(mModel, mTmp, mTmp2)
                    renderer.draw(it, mModel)
                }
                f.model.draw(renderer, null)
            }
        }
        renderer.drawParticles(particles, camera.viewProj, h)
        if (!Portraits.ready) Portraits.captureNext(renderer, ModelRegistry.models, w, h)
    }

    // ---------------- UI (UI thread) ----------------

    fun drawUi(c: Canvas, w: Int, h: Int) {
        screens.beginFrame()
        when (state) {
            AppState.MENU -> screens.drawMenu(c, w, h, time)
            AppState.MODE_SELECT -> screens.drawModeSelect(c, w, h, time)
            AppState.SELECT -> screens.drawSelect(c, w, h, time)
            AppState.ARENA_SELECT -> screens.drawArenaSelect(c, w, h, time)
            AppState.SETTINGS -> screens.drawSettings(c, w, h, time)
            AppState.HELP -> screens.drawHelp(c, w, h)
            AppState.BATTLE -> {
                val b = battle ?: return
                hud.draw(c, w, h, b, camera, time)
                controls.draw(c, b, time)
                if (paused) screens.drawPause(c, w, h)
            }
            AppState.RESULTS -> screens.drawResults(c, w, h, time)
            AppState.MP_MENU -> screens.drawMpMenu(c, w, h, time)
            AppState.MP_LAN -> screens.drawMpLan(c, w, h, time)
            AppState.MP_ONLINE -> screens.drawMpOnline(c, w, h, time)
            AppState.LOBBY -> screens.drawLobby(c, w, h, time)
        }
        if (state == AppState.BATTLE && session != null) screens.drawNetOverlay(c, w, h, time)
        screens.endFrame()
    }

    fun onTouch(ev: MotionEvent): Boolean {
        if (state == AppState.BATTLE && session != null && (netMenuOpen || session!!.lostTimer >= 0f)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                return screens.touch(ev.x, ev.y)
            return true
        }
        if (state == AppState.BATTLE && !paused) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && hud.pauseRect.contains(ev.x, ev.y)) {
                togglePause()
                return true
            }
            return controls.onTouch(ev, inputs)
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            return screens.touch(ev.x, ev.y)
        }
        return true
    }

    fun handleKey(keyCode: Int, down: Boolean): Boolean = activity.routeKey(keyCode, down, inputs, state == AppState.BATTLE && mode == BattleMode.LOCAL_2P)

    fun backPressed() {
        when (state) {
            AppState.BATTLE -> togglePause()
            AppState.MODE_SELECT, AppState.SETTINGS, AppState.HELP -> goMenu()
            AppState.SELECT -> goModeSelect()
            AppState.ARENA_SELECT -> { state = AppState.SELECT }
            AppState.RESULTS -> if (session != null) session?.returnToLobby() else goMenu()
            AppState.LOBBY -> { session?.leave(); state = AppState.MP_MENU }
            AppState.MP_LAN, AppState.MP_ONLINE -> { session?.leave(); state = AppState.MP_MENU }
            AppState.MP_MENU -> goMenu()
            AppState.MENU -> exitGame()
        }
    }
}
