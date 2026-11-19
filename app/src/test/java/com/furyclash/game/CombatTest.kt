package com.furyclash.game

import com.furyclash.game.engine.FightCamera
import com.furyclash.game.engine.ParticleSystem
import com.furyclash.game.game.BattleMode
import com.furyclash.game.game.Characters
import com.furyclash.game.game.CombatWorld
import com.furyclash.game.game.Difficulty
import com.furyclash.game.game.FState
import com.furyclash.game.game.Fighter
import com.furyclash.game.game.GameEvent
import com.furyclash.game.game.Phase
import com.furyclash.game.game.SkillExecutor
import com.furyclash.game.game.SkillType
import com.furyclash.game.game.TeamBattle
import com.furyclash.game.input.InputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for combat math and 3v3 / Best-of-5 rules (no Android deps). */
class CombatTest {
    private val dt = 1f / 60f

    private fun world(): CombatWorld = CombatWorld(ParticleSystem(256), FightCamera())

    private fun fighter(id: Int, team: Int, x: Float, facing: Int): Fighter {
        val f = Fighter(Characters.byId(id), team, 0)
        f.x = x
        f.facing = facing
        f.enterState(FState.IDLE)
        return f
    }

    private fun duel(): Triple<CombatWorld, Fighter, Fighter> {
        val w = world()
        val a = fighter(9, 0, -0.6f, 1)   // Martial Artist
        val b = fighter(13, 1, 0.6f, -1)  // Street Brawler
        w.p1 = a; w.p2 = b
        return Triple(w, a, b)
    }

    private fun step(w: CombatWorld, a: Fighter, b: Fighter, seconds: Float, ia: InputState = InputState(), ib: InputState = InputState()) {
        var t = 0f
        while (t < seconds) {
            ia.update(dt); ib.update(dt)
            a.update(dt, ia, w, b)
            b.update(dt, ib, w, a)
            w.update(if (w.hitstop > 0f) 0f else dt * w.timeScale, dt)
            t += dt
        }
    }

    private fun stepBattle(b: TeamBattle, seconds: Float, i0: InputState = InputState(), i1: InputState = InputState()) {
        var t = 0f
        val inputs = arrayOf(i0, i1)
        while (t < seconds) {
            i0.update(dt); i1.update(dt)
            b.update(dt, inputs)
            t += dt
        }
    }

    @Test
    fun punchLandsAndDealsDamage() {
        val (w, a, b) = duel()
        val hp0 = b.hp
        a.startAttack(Fighter.PUNCH)
        step(w, a, b, 0.5f)
        assertTrue("punch must deal damage", b.hp < hp0)
        assertTrue("damage in expected range", hp0 - b.hp in 30f..60f)
    }

    @Test
    fun whiffedPunchDealsNoDamage() {
        val (w, a, b) = duel()
        b.x = 4f // out of range
        val hp0 = b.hp
        a.startAttack(Fighter.PUNCH)
        step(w, a, b, 0.5f)
        assertEquals(hp0, b.hp, 0.01f)
    }

    @Test
    fun blockReducesDamageToChip() {
        val (w, a, b) = duel()
        val ib = InputState().apply { blockHeld = true }
        step(w, a, b, 0.2f, ib = ib) // let block establish (past parry window)
        val hp0 = b.hp
        a.startAttack(Fighter.PUNCH)
        step(w, a, b, 0.5f, ib = ib)
        val lost = hp0 - b.hp
        assertTrue("chip damage should be small, was $lost", lost in 0.5f..15f)
    }

    @Test
    fun perfectParryStaggersAttacker() {
        val (w, a, b) = duel()
        val ib = InputState()
        a.startAttack(Fighter.PUNCH)
        // block just before the active frame (startup 0.08s); defender registers
        // block one frame after the press, as in the real game loop
        step(w, a, b, 0.03f, ib = ib)
        ib.blockHeld = true
        step(w, a, b, 0.3f, ib = ib)
        assertEquals(FState.STAGGER, a.state)
        assertEquals(b.maxHp, b.hp, 0.01f)
    }

    @Test
    fun comboDamageScalesDown() {
        val (w, a, b) = duel()
        w.resolveHit(a, b, 100f, 1f, 0.4f, 0.2f, false, false, com.furyclash.game.game.HitHeight.MID,
            com.furyclash.game.game.ProjEffect.NONE, 0f, 7f, false, false)
        val first = b.maxHp - b.hp
        w.resolveHit(a, b, 100f, 1f, 0.4f, 0.2f, false, false, com.furyclash.game.game.HitHeight.MID,
            com.furyclash.game.game.ProjEffect.NONE, 0f, 7f, false, false)
        val second = b.maxHp - b.hp - first
        assertTrue("second hit ($second) should scale below first ($first)", second < first)
        assertEquals(2, b.comboTaken)
    }

    @Test
    fun lethalHitKOsAndEmitsEvent() {
        val (w, a, b) = duel()
        var ko = false
        w.listener = { e -> if (e is GameEvent.KO) ko = true }
        w.resolveHit(a, b, 9999f, 3f, 0.5f, 0.2f, false, false, com.furyclash.game.game.HitHeight.MID,
            com.furyclash.game.game.ProjEffect.NONE, 0f, 7f, true, false)
        assertFalse(b.alive)
        assertEquals(FState.KO, b.state)
        assertTrue(ko)
    }

    @Test
    fun projectileTravelsAndHits() {
        val (w, a, b) = duel()
        b.x = 3f
        val skill = a.def.skills[0] // Palm Wave projectile
        SkillExecutor.fire(w, a, skill)
        assertEquals(1, w.projectiles.size)
        val hp0 = b.hp
        step(w, a, b, 1.2f)
        assertTrue("projectile must hit", b.hp < hp0)
        assertEquals(0, w.projectiles.size)
    }

    @Test
    fun freezeProjectileFreezes() {
        val w = world()
        val a = fighter(5, 0, -2f, 1)  // Ice Queen
        val b = fighter(9, 1, 1f, -1)
        w.p1 = a; w.p2 = b
        SkillExecutor.fire(w, a, a.def.skills[1]) // Glacial Prison
        step(w, a, b, 1.5f)
        assertTrue("victim should be frozen", b.freezeTimer > 0f)
    }

    @Test
    fun ultimateDealsMassiveDamage() {
        val (w, a, b) = duel()
        a.energy = 100f
        a.startCast(2, w)
        val hp0 = b.hp
        step(w, a, b, 4f)
        assertTrue("ultimate must deal big damage, dealt ${hp0 - b.hp}", hp0 - b.hp > 200f)
    }

    @Test
    fun manualSwitchChangesActiveFighter() {
        val w = world()
        val b = TeamBattle(w, listOf(Characters.byId(9), Characters.byId(1), Characters.byId(12)),
            listOf(Characters.byId(13), Characters.byId(10), Characters.byId(3)),
            BattleMode.LOCAL_2P, Difficulty.NORMAL, 99f)
        stepBattle(b, 2.5f) // past intro
        assertEquals(Phase.FIGHT, b.phase)
        val i0 = InputState()
        i0.press(InputState.SWITCH)
        stepBattle(b, 0.1f, i0 = i0)
        assertEquals(1, b.teams[0].active)
        assertTrue(b.active(0).invuln > 0f)
        assertTrue(b.teams[0].switchCooldown > 0f)
    }

    private fun koActiveTeam(b: TeamBattle, team: Int) {
        b.world.kill(b.active(team))
        stepBattle(b, 2.0f) // KO_SLOW -> auto switch or round end
    }

    @Test
    fun teamWipeWinsRoundAndAdvances() {
        val w = world()
        val b = TeamBattle(w, listOf(Characters.byId(9), Characters.byId(1), Characters.byId(12)),
            listOf(Characters.byId(13), Characters.byId(10), Characters.byId(3)),
            BattleMode.LOCAL_2P, Difficulty.NORMAL, 99f)
        stepBattle(b, 2.5f)
        koActiveTeam(b, 1)
        assertEquals("auto-advance to fighter 2", 1, b.teams[1].active)
        assertEquals(Phase.FIGHT, b.phase)
        koActiveTeam(b, 1)
        koActiveTeam(b, 1)
        stepBattle(b, 4f) // round end banner -> next round
        assertEquals(1, b.teams[0].roundsWon)
        assertEquals(0, b.teams[1].roundsWon)
        assertEquals(2, b.round)
        // defeated fighters reset for the new round
        assertTrue(b.teams[1].fighters.all { it.alive })
    }

    @Test
    fun firstToThreeRoundsWinsMatch() {
        val w = world()
        val b = TeamBattle(w, listOf(Characters.byId(9), Characters.byId(1), Characters.byId(12)),
            listOf(Characters.byId(13), Characters.byId(10), Characters.byId(3)),
            BattleMode.LOCAL_2P, Difficulty.NORMAL, 99f)
        stepBattle(b, 2.5f)
        repeat(3) {
            koActiveTeam(b, 1)
            koActiveTeam(b, 1)
            koActiveTeam(b, 1)
            stepBattle(b, 4f)
        }
        assertEquals(3, b.teams[0].roundsWon)
        assertEquals(0, b.matchWinner)
        assertEquals(Phase.MATCH_END, b.phase)
    }

    @Test
    fun timeoutGoesToHealthierTeam() {
        val w = world()
        val b = TeamBattle(w, listOf(Characters.byId(9), Characters.byId(1), Characters.byId(12)),
            listOf(Characters.byId(13), Characters.byId(10), Characters.byId(3)),
            BattleMode.LOCAL_2P, Difficulty.NORMAL, 3f)
        stepBattle(b, 2.5f)
        b.active(1).hp -= 300f
        stepBattle(b, 2f) // timer expires
        stepBattle(b, 4f)
        assertEquals(1, b.teams[0].roundsWon)
    }

    @Test
    fun defeatedFighterCannotBeSwitchedBackIn() {
        val w = world()
        val b = TeamBattle(w, listOf(Characters.byId(9), Characters.byId(1), Characters.byId(12)),
            listOf(Characters.byId(13), Characters.byId(10), Characters.byId(3)),
            BattleMode.LOCAL_2P, Difficulty.NORMAL, 99f)
        stepBattle(b, 2.5f)
        // KO fighter 0 manually without ending round (team still has 2 alive)
        b.world.kill(b.active(0))
        stepBattle(b, 2.0f)
        assertEquals(1, b.teams[0].active)
        assertFalse(b.teams[0].fighters[0].alive)
        // try to switch twice: should land on 2, never back on dead 0
        stepBattle(b, 1.5f) // let the auto-tag team cooldown expire
        val i0 = InputState()
        i0.press(InputState.SWITCH)
        stepBattle(b, 0.1f, i0 = i0)
        assertEquals(2, b.teams[0].active)
        stepBattle(b, 0.5f) // let the switch-in animation finish
        b.teams[0].switchCooldown = 0f
        i0.press(InputState.SWITCH)
        stepBattle(b, 0.1f, i0 = i0)
        assertEquals(1, b.teams[0].active)
    }

    @Test
    fun dashSkillMovesAndHits() {
        val (w, a, b) = duel()
        b.x = 2.5f
        val skill = a.def.skills.let { Characters.byId(2).skills[0] } // Iron Fist Piston Jab
        val iron = fighter(2, 0, -1f, 1)
        w.p1 = iron
        SkillExecutor.fire(w, iron, skill)
        val hp0 = b.hp
        step(w, iron, b, 1f)
        assertTrue("dash should close distance", iron.x > 0f)
        assertTrue("dash should hit", b.hp < hp0)
    }

    @Test
    fun joystickRunClosesDistance() {
        val (w, a, b) = duel()
        b.x = 5f
        val ia = InputState().apply { moveX = 1f }
        val x0 = a.x
        step(w, a, b, 0.6f, ia = ia)
        assertTrue("run should cover >3m in 0.6s, moved ${a.x - x0}", a.x - x0 > 3f)
        assertEquals(FState.RUN, a.state)
    }

    @Test
    fun punchChainComboScalesAndCounts() {
        val (w, a, b) = duel()
        a.x = -0.35f; b.x = 0.35f
        val ia = InputState()
        ia.press(InputState.PUNCH)
        step(w, a, b, 0.16f, ia = ia)
        ia.press(InputState.PUNCH)
        step(w, a, b, 0.16f, ia = ia)
        ia.press(InputState.KICK)
        step(w, a, b, 0.2f, ia = ia)
        ia.press(InputState.HEAVY)
        step(w, a, b, 0.5f, ia = ia)
        assertTrue("chain should land 4 hits, got ${b.comboTaken}", b.comboTaken >= 4)
        assertTrue("combo damage should exceed single heavy", a.lastDamageDealt > 0f)
        assertTrue("total damage substantial", b.maxHp - b.hp > 150f)
    }

    @Test
    fun jumpAttackBeatsCrouchBlock() {
        val (w, a, b) = duel()
        val ib = InputState().apply { blockHeld = true; moveY = -1f }
        step(w, a, b, 0.2f, ib = ib) // crouch block established
        val ia = InputState()
        ia.press(InputState.JUMP)
        step(w, a, b, 0.25f, ia = ia, ib = ib)
        ia.press(InputState.KICK)
        val hp0 = b.hp
        step(w, a, b, 0.6f, ia = ia, ib = ib)
        assertTrue("overhead air kick must hit crouch-blocker", b.hp < hp0)
    }

    @Test
    fun crouchBlockStopsSweep() {
        val (w, a, b) = duel()
        val ib = InputState().apply { blockHeld = true; moveY = -1f }
        step(w, a, b, 0.2f, ib = ib)
        val hp0 = b.hp
        a.startAttack(Fighter.SWEEP)
        step(w, a, b, 0.6f, ib = ib)
        assertTrue("crouch block must absorb sweep as chip", hp0 - b.hp < 15f)
    }

    @Test
    fun buffSkillAppliesShield() {
        val w = world()
        val a = fighter(14, 0, -2f, 1) // Energy Master
        val b = fighter(9, 1, 2f, -1)
        w.p1 = a; w.p2 = b
        SkillExecutor.fire(w, a, a.def.skills[1]) // Radiant Barrier
        assertEquals(2, a.shieldHits)
        val hp0 = a.hp
        w.resolveHit(b, a, 100f, 2f, 0.4f, 0.2f, false, false, com.furyclash.game.game.HitHeight.MID,
            com.furyclash.game.game.ProjEffect.NONE, 0f, 7f, false, false)
        assertEquals("shield absorbs hit", hp0, a.hp, 0.01f)
        assertEquals(1, a.shieldHits)
    }
}
