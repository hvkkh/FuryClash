package com.furyclash.game.game

import com.furyclash.game.input.InputState
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Difficulty-scaled AI: spacing per fighting style, attack strings, reactive
 * blocking/parries, anti-airs, projectile dodges, skill/ultimate usage and
 * team switches when hurt.
 */
class AIController(private val difficulty: Difficulty) {
    private class Queued(var delay: Float, val btn: Int)

    private val params = when (difficulty) {
        Difficulty.EASY -> floatArrayOf(0.50f, 0.12f, 0.25f, 0.30f, 0.10f, 0.04f, 0.10f)
        Difficulty.NORMAL -> floatArrayOf(0.28f, 0.45f, 0.50f, 0.60f, 0.30f, 0.10f, 0.35f)
        Difficulty.HARD -> floatArrayOf(0.14f, 0.80f, 0.75f, 0.90f, 0.50f, 0.16f, 0.70f)
    }
    private val thinkInterval = params[0]
    private val blockChance = params[1]
    private val skillChance = params[2]
    private val ultChance = params[3]
    private val switchChance = params[4]
    private val jumpChance = params[5]
    private val dodgeChance = params[6]

    private var think = 0f
    private var blockHold = 0f
    private var moveIntent = 0f
    private var crouchIntent = false
    private val queue = ArrayList<Queued>(6)

    fun update(dt: Float, me: Fighter, opp: Fighter, input: InputState, battle: TeamBattle) {
        input.moveX = 0f
        input.moveY = 0f
        input.blockHeld = false
        if (dt <= 0f || !me.alive) return

        val dist = opp.x - me.x
        val adist = abs(dist)
        val toward = sign(dist)

        // ---- reactive defense ----
        blockHold = maxOf(0f, blockHold - dt)
        if (opp.state == FState.ATTACK) {
            val a = opp.attack
            if (a != null && adist < a.reach + 0.9f && opp.stateTime < a.startup + a.active &&
                Random.nextFloat() < blockChance * dt * 30f) {
                blockHold = 0.3f
                crouchIntent = a.height == HitHeight.LOW
            }
        }
        for (pr in battle.world.projectiles) {
            if (pr.owner === me) continue
            val heading = (me.x - pr.x) * pr.vx > 0f
            if (heading && abs(pr.x - me.x) < 2.6f && Random.nextFloat() < dodgeChance * dt * 20f) {
                if (Random.nextBoolean() && me.grounded) input.press(InputState.JUMP)
                else blockHold = 0.4f
            }
        }
        if (blockHold > 0f && me.grounded) {
            input.blockHeld = true
            input.moveY = if (crouchIntent) -1f else 0f
            return
        }

        // ---- queued combo presses ----
        var i = queue.size - 1
        while (i >= 0) {
            val q = queue[i]
            q.delay -= dt
            if (q.delay <= 0f) { input.press(q.btn); queue.removeAt(i) }
            i--
        }

        think -= dt
        if (think <= 0f) {
            think = thinkInterval * (0.7f + Random.nextFloat() * 0.6f)
            decide(me, opp, input, battle, adist, toward)
        }
        input.moveX = moveIntent
        if (crouchIntent && me.grounded) input.moveY = -1f
    }

    private fun decide(me: Fighter, opp: Fighter, input: InputState, battle: TeamBattle, adist: Float, toward: Float) {
        crouchIntent = false
        val team = battle.teams[me.team]

        // ultimate when meter is full and it will connect
        if (me.energy >= me.def.skills[2].cost && me.cooldowns[2] <= 0f &&
            (adist < 3f || opp.busy) && Random.nextFloat() < ultChance) {
            input.press(InputState.ULT)
            moveIntent = 0f
            return
        }

        // tag out when badly hurt
        if (me.hpFraction < 0.28f && team.aliveCount() > 1 && team.switchCooldown <= 0f &&
            Random.nextFloat() < switchChance) {
            input.press(InputState.SWITCH)
            return
        }

        // special skills when range fits
        for (idx in 0..1) {
            val skill = me.def.skills[idx]
            if (me.energy < skill.cost || me.cooldowns[idx] > 0f) continue
            val fits = when (skill.type) {
                SkillType.AOE_SELF, SkillType.SPIN -> adist < skill.radius + 0.4f
                SkillType.DASH_ATTACK, SkillType.TELEPORT_STRIKE, SkillType.MULTI_RUSH -> adist < skill.range + 1f
                SkillType.BUFF -> adist > 1.5f || skill.buff == BuffKind.COUNTER_STANCE
                else -> adist > 1.2f
            }
            if (fits && Random.nextFloat() < skillChance) {
                input.press(if (idx == 0) InputState.S1 else InputState.S2)
                moveIntent = 0f
                return
            }
        }

        // anti-air
        if (!opp.grounded && adist < 1.7f && Random.nextFloat() < 0.55f) {
            input.press(InputState.HEAVY)
            moveIntent = 0f
            return
        }

        // offense: pick a combo route when in range
        if (adist < 1.3f && opp.state != FState.KO) {
            val route = when (Random.nextInt(5)) {
                0 -> intArrayOf(InputState.PUNCH, InputState.PUNCH, InputState.KICK)
                1 -> intArrayOf(InputState.PUNCH, InputState.KICK, InputState.HEAVY)
                2 -> intArrayOf(InputState.KICK, InputState.HEAVY)
                3 -> intArrayOf(InputState.PUNCH, InputState.PUNCH, InputState.PUNCH)
                else -> intArrayOf(InputState.HEAVY)
            }
            queue.clear()
            for (k in route.indices) queue.add(Queued(k * 0.17f, route[k]))
            moveIntent = toward * 0.4f
            return
        }

        // spacing per style
        val desired = when (me.def.style) {
            Style.ZONER -> 4.2f
            Style.RUSHDOWN -> 0.9f
            Style.GRAPPLER -> 1.0f
            Style.TRICKY -> 2.2f
            Style.BALANCED -> 1.6f
        }
        moveIntent = when {
            adist > desired + 0.5f -> toward * (if (adist > 3f) 1f else 0.6f)
            adist < desired - 0.5f -> -toward * 0.6f
            else -> (Random.nextFloat() - 0.5f) * 0.4f
        }

        // jump-in
        if (me.grounded && adist in 1.6f..3.6f && Random.nextFloat() < jumpChance) {
            input.press(InputState.JUMP)
            moveIntent = toward
        }
    }
}
