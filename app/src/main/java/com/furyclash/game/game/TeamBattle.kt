package com.furyclash.game.game

import com.furyclash.game.engine.clamp
import com.furyclash.game.input.InputState
import kotlin.math.max

enum class BattleMode { VS_AI, LOCAL_2P, TRAINING, ONLINE_HOST, ONLINE_CLIENT }
enum class Difficulty { EASY, NORMAL, HARD }
enum class DummyMode { STAND, BLOCK, JUMP, CPU }
enum class Phase { INTRO, FIGHT, KO_SLOW, ROUND_END, MATCH_END }

/**
 * Full 3v3 team battle: one active fighter per team, manual + auto switching,
 * defeated fighters stay out for the round, Best-of-5 (first to 3 round wins).
 */
class TeamBattle(
    val world: CombatWorld,
    p1Defs: List<CharacterDef>,
    p2Defs: List<CharacterDef>,
    val mode: BattleMode,
    val difficulty: Difficulty,
    val roundTime: Float
) {
    class Team(val index: Int, defs: List<CharacterDef>) {
        val fighters = defs.mapIndexed { i, d -> Fighter(d, index, i) }
        var active = 0
        var roundsWon = 0
        var switchCooldown = 0f

        fun aliveCount() = fighters.count { it.alive }
        fun nextAlive(from: Int): Int {
            for (k in 1..3) {
                val i = (from + k) % 3
                if (fighters[i].alive) return i
            }
            return -1
        }
        fun totalHpFraction() = fighters.sumOf { it.hpFraction.toDouble() }.toFloat()
    }

    val teams = arrayOf(Team(0, p1Defs), Team(1, p2Defs))
    private val ai = AIController(difficulty)
    private val dummyAi = AIController(Difficulty.EASY)
    private val neutral = InputState()

    var round = 1
    var timer = roundTime
    var phase = Phase.INTRO
    var phaseTime = 0f
    var matchWinner = -1
    var roundWinner = -1
    var roundReason = ""
    private var pendingSwitchTeam = -1
    private var pendingSwitchTo = -1
    private var fightAnnounced = false

    // training
    var dummyMode = DummyMode.STAND
    var infiniteEnergy = false
    private var trainingResetTimer = 0f
    private var dummyJumpTimer = 0f
    var statsLastDamage = 0f
    var statsTotalDamage = 0f
    var statsMaxCombo = 0

    init {
        world.listener = { e -> onEvent(e) }
        startRound()
    }

    fun active(team: Int): Fighter = teams[team].fighters[teams[team].active]

    private fun startRound() {
        world.reset()
        for (t in teams) {
            t.active = 0
            t.switchCooldown = 0f
            for (f in t.fighters) {
                f.resetForRound(if (t.index == 0) -2.2f else 2.2f, if (t.index == 0) 1 else -1)
            }
        }
        world.p1 = active(0)
        world.p2 = active(1)
        timer = roundTime
        phase = Phase.INTRO
        phaseTime = 0f
        fightAnnounced = false
        roundWinner = -1
        world.emit(GameEvent.RoundStart(round))
        world.emit(GameEvent.Announce(if (round >= 5) "FINAL ROUND" else "ROUND $round", 0xFFFFFF, 1.1f))
    }

    /** External hook for HUD announcements / audio / UI flow. */
    var externalListener: ((GameEvent) -> Unit)? = null

    private fun onEvent(e: GameEvent) {
        externalListener?.invoke(e)
        when (e) {
            is GameEvent.KO -> onKO(e.victim)
            is GameEvent.Hit -> {
                if (mode == BattleMode.TRAINING && e.attacker.team == 0) {
                    statsLastDamage = e.damage
                    statsTotalDamage += e.damage
                    statsMaxCombo = max(statsMaxCombo, e.combo)
                }
            }
            else -> {}
        }
    }

    private fun onKO(victim: Fighter) {
        if (mode == BattleMode.TRAINING) {
            trainingResetTimer = 1.4f
            return
        }
        val t = teams[victim.team]
        val next = t.nextAlive(t.active)
        world.emit(GameEvent.Announce("K.O.!", 0xFF5A3C, 1.3f))
        phase = Phase.KO_SLOW
        phaseTime = 0f
        if (next < 0) {
            roundWinner = 1 - victim.team
            roundReason = "K.O."
        } else {
            roundWinner = -1
            pendingSwitchTeam = victim.team
            pendingSwitchTo = next
        }
    }

    private fun doSwitch(team: Int, to: Int, auto: Boolean) {
        val t = teams[team]
        val old = t.fighters[t.active]
        val incoming = t.fighters[to]
        t.active = to
        incoming.x = clamp(old.x, -world.arenaHalf, world.arenaHalf)
        incoming.y = 0f; incoming.vx = 0f; incoming.vy = 0f
        incoming.facing = if (team == 0) 1 else -1
        incoming.invuln = 0.5f
        incoming.enterState(FState.SWITCH_IN)
        t.switchCooldown = 1.6f
        if (team == 0) world.p1 = incoming else world.p2 = incoming
        world.burst(incoming.x, 1f, com.furyclash.game.engine.rgb(if (team == 0) 0x3D7BFF else 0xFF5A3C), 16, 3f)
        world.emit(GameEvent.Switch(team, incoming))
        world.sound("switch")
    }

    private fun timeUp() {
        val f0 = teams[0].totalHpFraction()
        val f1 = teams[1].totalHpFraction()
        roundWinner = when {
            f0 > f1 + 0.001f -> 0
            f1 > f0 + 0.001f -> 1
            teams[0].aliveCount() != teams[1].aliveCount() -> if (teams[0].aliveCount() > teams[1].aliveCount()) 0 else 1
            else -> -1
        }
        roundReason = "TIME UP"
        world.emit(GameEvent.Announce("TIME UP", 0xFFFFFF, 1.2f))
        endRound()
    }

    private fun endRound() {
        world.projectiles.clear()
        world.beams.clear()
        phase = Phase.ROUND_END
        phaseTime = 0f
        if (roundWinner >= 0) {
            teams[roundWinner].roundsWon++
            active(roundWinner).enterState(FState.VICTORY)
            val loser = active(1 - roundWinner)
            if (loser.alive) loser.enterState(FState.DEFEAT)
            world.emit(GameEvent.RoundEnd(roundWinner, roundReason))
            world.emit(GameEvent.Announce("TEAM ${roundWinner + 1} TAKES THE ROUND", if (roundWinner == 0) 0x3D7BFF else 0xFF5A3C, 1.8f))
        } else {
            world.emit(GameEvent.RoundEnd(-1, "DRAW"))
            world.emit(GameEvent.Announce("DRAW — RUN IT BACK", 0xFFFFFF, 1.8f))
        }
        world.sound(if (roundWinner >= 0) "roundwin" else "ui")
    }

    fun update(dtReal: Float, inputs: Array<InputState>) {
        phaseTime += dtReal
        for (t in teams) t.switchCooldown = max(0f, t.switchCooldown - dtReal)

        if (mode == BattleMode.TRAINING && trainingResetTimer > 0f) {
            trainingResetTimer -= dtReal
            if (trainingResetTimer <= 0f) {
                for (t in teams) {
                    val f = active(t.index)
                    f.hp = f.maxHp
                    f.alive = true
                    f.enterState(FState.IDLE)
                    f.comboTaken = 0; f.comboDealt = 0
                }
            }
        }

        val simDt = if (world.hitstop > 0f) 0f else dtReal * world.timeScale
        neutral.reset()

        when (phase) {
            Phase.INTRO -> {
                active(0).update(simDt, neutral, world, active(1))
                active(1).update(simDt, neutral, world, active(0))
                if (!fightAnnounced && phaseTime >= 1.0f) {
                    fightAnnounced = true
                    world.emit(GameEvent.Fight)
                    world.emit(GameEvent.Announce("FIGHT!", 0xFFC94D, 0.8f))
                    world.sound("fight")
                }
                if (phaseTime >= 1.7f) {
                    phase = Phase.FIGHT
                    active(0).enterState(FState.IDLE)
                    active(1).enterState(FState.IDLE)
                }
            }

            Phase.FIGHT -> {
                if (roundTime > 0f) timer = max(0f, timer - simDt)

                if (mode == BattleMode.VS_AI) ai.update(simDt, active(1), active(0), inputs[1], this)
                if (mode == BattleMode.TRAINING) dummyInput(simDt, inputs[1])

                // manual switching
                for (team in 0..1) {
                    val t = teams[team]
                    if (inputs[team].consume(InputState.SWITCH) && t.switchCooldown <= 0f) {
                        val next = t.nextAlive(t.active)
                        if (next >= 0 && active(team).actionable) doSwitch(team, next, false)
                    }
                }

                active(0).update(simDt, inputs[0], world, active(1))
                active(1).update(simDt, inputs[1], world, active(0))

                if (mode == BattleMode.TRAINING && infiniteEnergy) active(0).energy = 100f

                if (roundTime > 0f && timer <= 0f && phase == Phase.FIGHT) timeUp()
            }

            Phase.KO_SLOW -> {
                world.timeScale = 0.35f
                active(0).update(simDt, neutral, world, active(1))
                active(1).update(simDt, neutral, world, active(0))
                if (phaseTime >= 1.4f) {
                    world.timeScale = 1f
                    if (roundWinner >= 0) {
                        endRound()
                    } else {
                        doSwitch(pendingSwitchTeam, pendingSwitchTo, true)
                        phase = Phase.FIGHT
                    }
                }
            }

            Phase.ROUND_END -> {
                active(0).update(simDt, neutral, world, active(1))
                active(1).update(simDt, neutral, world, active(0))
                if (phaseTime >= 2.5f) {
                    if (teams[0].roundsWon >= 3 || teams[1].roundsWon >= 3) {
                        matchWinner = if (teams[0].roundsWon >= 3) 0 else 1
                        phase = Phase.MATCH_END
                        phaseTime = 0f
                        world.emit(GameEvent.MatchEnd(matchWinner))
                        world.emit(GameEvent.Announce("TEAM ${matchWinner + 1} WINS THE MATCH", if (matchWinner == 0) 0x3D7BFF else 0xFF5A3C, 2.5f))
                        world.sound("win")
                    } else {
                        if (roundWinner >= 0) round++
                        startRound()
                    }
                }
            }

            Phase.MATCH_END -> {
                active(0).update(simDt, neutral, world, active(1))
                active(1).update(simDt, neutral, world, active(0))
            }
        }

        world.update(simDt, dtReal)
    }

    private fun dummyInput(dt: Float, input: InputState) {
        input.reset()
        when (dummyMode) {
            DummyMode.STAND -> {}
            DummyMode.BLOCK -> input.blockHeld = true
            DummyMode.JUMP -> {
                dummyJumpTimer -= dt
                if (dummyJumpTimer <= 0f) { dummyJumpTimer = 1.3f; input.press(InputState.JUMP) }
            }
            DummyMode.CPU -> dummyAi.update(dt, active(1), active(0), input, this)
        }
    }

    fun resetTrainingStats() {
        statsLastDamage = 0f; statsTotalDamage = 0f; statsMaxCombo = 0
    }

    /** Forfeit / disconnect resolution: end the match immediately for `winner`. */
    fun forceEnd(winner: Int) {
        if (phase == Phase.MATCH_END) return
        matchWinner = winner
        phase = Phase.MATCH_END
        phaseTime = 0f
        world.emit(GameEvent.MatchEnd(winner))
    }

    /** Guest-side tick: advance visuals only; authoritative state arrives via snapshots. */
    fun remoteVisualTick(dt: Float) {
        phaseTime += dt
        active(0).visualTick(dt, world.arenaHalf)
        active(1).visualTick(dt, world.arenaHalf)
        world.updateVisuals(dt)
    }
}
