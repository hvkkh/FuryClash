package com.furyclash.game.game

import com.furyclash.game.engine.FightCamera
import com.furyclash.game.engine.ParticleSystem
import com.furyclash.game.engine.clamp
import com.furyclash.game.engine.lerp
import com.furyclash.game.engine.rgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

sealed class GameEvent {
    data class Hit(val attacker: Fighter, val victim: Fighter, val damage: Float, val combo: Int, val heavy: Boolean) : GameEvent()
    data class Blocked(val attacker: Fighter, val victim: Fighter) : GameEvent()
    data class Parry(val defender: Fighter, val attacker: Fighter) : GameEvent()
    data class Shielded(val victim: Fighter) : GameEvent()
    data class KO(val victim: Fighter) : GameEvent()
    data class SkillUsed(val f: Fighter, val skill: SkillDef) : GameEvent()
    data class Ultimate(val f: Fighter, val skill: SkillDef) : GameEvent()
    data class Jump(val f: Fighter) : GameEvent()
    data class Land(val f: Fighter, val hard: Boolean) : GameEvent()
    data class Switch(val team: Int, val f: Fighter) : GameEvent()
    data class Frozen(val victim: Fighter) : GameEvent()
    data class RoundStart(val round: Int) : GameEvent()
    data class RoundEnd(val winnerTeam: Int, val reason: String) : GameEvent()
    data class MatchEnd(val winnerTeam: Int) : GameEvent()
    data class Announce(val text: String, val color: Int, val duration: Float) : GameEvent()
    object Fight : GameEvent()
}

class Projectile(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float,
    val owner: Fighter, val skill: SkillDef,
    var life: Float, val radius: Float
) {
    var color = rgb(skill.color)
    var trailTick = 0f
}

class Beam(var x: Float, var y: Float, val dir: Float, val len: Float, val color: FloatArray, var life: Float, val maxLife: Float, val radius: Float)

class DamageNumber(var x: Float, var y: Float, var z: Float, val text: String, val color: Int, var t: Float, val big: Boolean)

private class PendingAction(var t: Float, val action: () -> Unit)

/**
 * Combat simulation: hit resolution, projectiles, status effects, hitstop,
 * slow-mo cinematics and floating damage numbers. Pure logic + VFX hooks.
 */
class CombatWorld(val particles: ParticleSystem, val camera: FightCamera) {
    var p1: Fighter? = null
    var p2: Fighter? = null
    var arenaHalf = 6.5f

    val projectiles = ArrayList<Projectile>(16)
    val beams = ArrayList<Beam>(4)
    val damageNumbers = ArrayList<DamageNumber>(16)
    private val pending = ArrayList<PendingAction>(16)

    var hitstop = 0f
    var timeScale = 1f
    var cinematicTimer = 0f
    var cinematicFocus: Fighter? = null
    var flash = 0f
    var flashColor = 0xFFFFFF

    var listener: ((GameEvent) -> Unit)? = null
    var sfx: ((String) -> Unit)? = null

    fun emit(e: GameEvent) { listener?.invoke(e) }
    fun sound(name: String) { sfx?.invoke(name) }
    fun oppOf(f: Fighter): Fighter? = if (f === p1) p2 else p1
    fun pending(delay: Float, action: () -> Unit) { pending.add(PendingAction(delay, action)) }

    fun reset() {
        projectiles.clear(); beams.clear(); damageNumbers.clear(); pending.clear()
        hitstop = 0f; timeScale = 1f; cinematicTimer = 0f; cinematicFocus = null; flash = 0f
        particles.clear()
    }

    fun startCinematic(f: Fighter) {
        cinematicTimer = 1.2f
        cinematicFocus = f
        flash = 1f
        flashColor = f.def.skills[2].color
        timeScale = 0.3f
        camera.shake(0.5f)
        sound("ultimate")
    }

    /** Visual-only aging (beams, damage numbers, particles, flash, slow-mo ramp). */
    fun updateVisuals(dtReal: Float) {
        if (cinematicTimer > 0f) {
            cinematicTimer -= dtReal
            timeScale = if (cinematicTimer > 0.7f) 0.3f else lerp(1f, 0.3f, clamp(cinematicTimer / 0.7f, 0f, 1f))
            if (cinematicTimer <= 0f) { timeScale = 1f; cinematicFocus = null }
        }
        flash = max(0f, flash - dtReal * 2.2f)
        var i = beams.size - 1
        while (i >= 0) { beams[i].life -= dtReal; if (beams[i].life <= 0) beams.removeAt(i); i-- }
        i = damageNumbers.size - 1
        while (i >= 0) {
            val d = damageNumbers[i]
            d.t += dtReal; d.y += dtReal * 1.1f
            if (d.t > 0.9f) damageNumbers.removeAt(i)
            i--
        }
        particles.update(dtReal * (if (hitstop > 0f) 0.25f else timeScale))
    }

    /** dtSim may be 0 during hitstop; dtReal always advances visuals. */
    fun update(dtSim: Float, dtReal: Float) {
        updateVisuals(dtReal)
        var i: Int
        if (hitstop > 0f) { hitstop -= dtReal; return }
        if (dtSim <= 0f) return

        // scheduled actions
        i = pending.size - 1
        while (i >= 0) {
            val p = pending[i]
            p.t -= dtSim
            if (p.t <= 0f) { pending.removeAt(i); p.action() }
            i--
        }

        // projectiles
        i = projectiles.size - 1
        while (i >= 0) {
            val pr = projectiles[i]
            pr.life -= dtSim
            pr.x += pr.vx * dtSim
            pr.y += pr.vy * dtSim
            pr.trailTick -= dtSim
            if (pr.trailTick <= 0f) {
                pr.trailTick = 0.03f
                particles.spawn(pr.x, pr.y, pr.z, -pr.vx * 0.08f, 0.3f, 0f, 0.3f, pr.radius * 0.8f,
                    pr.color[0], pr.color[1], pr.color[2], 0.7f, 0f, 1f)
            }
            val target = oppOf(pr.owner)
            var dead = pr.life <= 0f || abs(pr.x) > arenaHalf + 1.5f
            if (!dead && target != null && target.alive && target.invuln <= 0f) {
                val top = target.y + if (target.state == FState.CROUCH || target.crouchBlocking) 1.05f else 1.72f
                if (abs(pr.x - target.x) < pr.radius + 0.34f && pr.y > target.y + 0.1f && pr.y < top) {
                    resolveHit(pr.owner, target, pr.skill.damage * pr.owner.def.power * dmgBuff(pr.owner),
                        2.6f, 0.45f, 0.22f, pr.skill.launcher, pr.skill.knockdown, HitHeight.MID,
                        pr.skill.effect, pr.skill.effectDuration, 6f, false, pr.skill.cinematic)
                    burst(pr.x, pr.y, pr.color, 14, 3f)
                    dead = true
                }
            }
            if (dead) {
                burst(pr.x, pr.y, pr.color, 6, 1.5f)
                projectiles.removeAt(i)
            }
            i--
        }

        // pushboxes: keep grounded fighters from overlapping
        val a = p1; val b = p2
        if (a != null && b != null && a.alive && b.alive && a.grounded && b.grounded &&
            a.state != FState.KO && b.state != FState.KO) {
            val dx = b.x - a.x
            val minDist = 0.62f
            if (abs(dx) < minDist) {
                val push = (minDist - abs(dx)) / 2f
                val dir = if (dx >= 0f) 1f else -1f
                a.x = clamp(a.x - dir * push, -arenaHalf, arenaHalf)
                b.x = clamp(b.x + dir * push, -arenaHalf, arenaHalf)
            }
        }
    }

    private fun dmgBuff(f: Fighter) = if (f.damageBuff > 0f) f.damagePower else 1f

    fun comboScale(taken: Int) = max(0.35f, 1f - 0.09f * taken)

    fun tryMeleeHit(attacker: Fighter, a: AttackDef) {
        val victim = oppOf(attacker) ?: return
        if (!victim.alive || victim.invuln > 0f || victim.state == FState.KO) return
        val hx = attacker.x + attacker.facing * (0.25f + a.reach * 0.5f)
        val halfW = a.reach * 0.5f + 0.22f
        if (abs(hx - victim.x) > halfW + 0.32f * victim.def.bulk) return
        if (abs(attacker.y - victim.y) > 1.3f) return
        if (a.height == HitHeight.LOW && victim.y > 0.5f) return
        attacker.attackHit = true
        attacker.chainReady = true
        resolveHit(attacker, victim, a.damage * attacker.def.power * dmgBuff(attacker),
            a.knockback, a.hitstun, a.blockstun, a.launcher, a.knockdown, a.height,
            ProjEffect.NONE, 0f, a.energyGain, a.id == "heavy" || a.id == "sweep", false)
    }

    fun resolveHit(
        attacker: Fighter, victim: Fighter, baseDamage: Float,
        kb: Float, hitstun: Float, blockstun: Float,
        launcher: Boolean, knockdown: Boolean, height: HitHeight,
        effect: ProjEffect, effectDuration: Float, energyGain: Float,
        heavy: Boolean, unblockable: Boolean
    ) {
        if (!victim.alive || victim.state == FState.KO) return
        val dir = if (attacker.x <= victim.x) 1f else -1f
        val cx = (attacker.x + victim.x) / 2f
        val cy = victim.y + 1.05f

        // radiant barrier absorbs
        if (victim.shieldHits > 0 && !unblockable) {
            victim.shieldHits--
            burst(cx, cy, rgb(0xC99CFF), 16, 2.5f)
            emit(GameEvent.Shielded(victim))
            sound("block")
            return
        }
        // flowing counter stance
        if (victim.counterStance > 0f && !unblockable) {
            victim.counterStance = 0f
            attacker.enterState(FState.STAGGER)
            attacker.vx = -dir * 2.5f
            applyDirectDamage(attacker, 60f, victim)
            burst(cx, cy, rgb(0xFF8C3A), 20, 3.5f)
            emit(GameEvent.Parry(victim, attacker))
            sound("parry")
            camera.shake(0.4f)
            return
        }

        val facingAttacker = (victim.facing == 1 && attacker.x > victim.x) || (victim.facing == -1 && attacker.x < victim.x)
        val blocking = victim.state == FState.BLOCK && facingAttacker && !unblockable &&
            (height != HitHeight.LOW || victim.crouchBlocking) &&
            (height != HitHeight.OVERHEAD || !victim.crouchBlocking)

        if (blocking) {
            if (victim.blockAge < 0.13f) {
                // perfect parry: attacker staggered, defender gets a free punish
                attacker.enterState(FState.STAGGER)
                attacker.vx = -dir * 2.8f
                victim.addEnergy(15f)
                burst(cx, cy, rgb(0xFFD24D), 22, 3.5f)
                emit(GameEvent.Parry(victim, attacker))
                sound("parry")
                camera.shake(0.35f)
                hitstop = 0.09f
                return
            }
            val chip = baseDamage * 0.12f
            victim.hp -= chip
            victim.enterState(FState.BLOCKSTUN)
            victim.hitstunDur = blockstun
            victim.vx = dir * kb * 0.6f / victim.def.weight
            victim.addEnergy(3f)
            attacker.addEnergy(energyGain * 0.5f)
            burst(cx, cy, rgb(0x6BA8FF), 8, 2f)
            emit(GameEvent.Blocked(attacker, victim))
            sound("block")
            if (victim.hp <= 0f) kill(victim)
            return
        }

        // ---- clean hit ----
        var dmg = baseDamage
        if (victim.freezeTimer > 0f) { dmg *= 1.2f; victim.freezeTimer = 0f }
        dmg *= comboScale(victim.comboTaken)
        victim.comboTaken++
        victim.comboResetTimer = 1.0f
        attacker.comboDealt = victim.comboTaken
        attacker.lastDamageDealt = dmg
        victim.hp -= dmg
        victim.flashTimer = 0.12f
        victim.addEnergy(5f)
        attacker.addEnergy(energyGain)

        val armored = victim.armorTimer > 0f
        if (!armored) {
            victim.stateTime = 0f // refresh stun timer on every hit of a combo
            when {
                launcher -> {
                    victim.enterState(FState.LAUNCHED)
                    victim.vy = 6.4f
                    victim.y = max(victim.y, 0.01f)
                    victim.vx = dir * kb * 0.6f / victim.def.weight
                }
                knockdown -> {
                    victim.enterState(FState.LAUNCHED)
                    victim.vy = 4.2f
                    victim.y = max(victim.y, 0.01f)
                    victim.vx = dir * kb / victim.def.weight
                }
                else -> {
                    victim.enterState(FState.HITSTUN)
                    victim.hitstunDur = hitstun
                    victim.vx = dir * kb / victim.def.weight
                }
            }
        }

        when (effect) {
            ProjEffect.SLOW -> victim.slowTimer = max(victim.slowTimer, effectDuration)
            ProjEffect.FREEZE -> {
                victim.freezeTimer = max(victim.freezeTimer, effectDuration)
                emit(GameEvent.Frozen(victim))
                sound("ice")
            }
            ProjEffect.BURN -> victim.burnTimer = max(victim.burnTimer, effectDuration)
            ProjEffect.NONE -> {}
        }

        hitstop = if (heavy) 0.09f else 0.055f
        camera.shake(if (heavy) 0.5f else 0.24f)
        val hitColor = when (effect) {
            ProjEffect.BURN -> rgb(0xFF7A2E)
            ProjEffect.FREEZE, ProjEffect.SLOW -> rgb(0x9BE8FF)
            else -> rgb(if (heavy) 0xFFC94D else 0xFFF2E0)
        }
        burst(cx, cy, hitColor, if (heavy) 22 else 12, if (heavy) 4f else 2.8f)
        damageNumbers.add(DamageNumber(victim.x, victim.y + 1.9f, 0f, dmg.toInt().toString(),
            if (heavy) 0xFFC94D else 0xFFFFFF, 0f, heavy))
        emit(GameEvent.Hit(attacker, victim, dmg, victim.comboTaken, heavy))
        sound(if (heavy) "heavy" else "hit")
        if (victim.hp <= 0f) kill(victim)
    }

    /** Damage that bypasses block/combo logic (counter ripostes, burn handled elsewhere). */
    private fun applyDirectDamage(victim: Fighter, dmg: Float, source: Fighter) {
        victim.hp -= dmg
        victim.flashTimer = 0.12f
        victim.comboTaken++
        victim.comboResetTimer = 1.0f
        source.comboDealt = victim.comboTaken
        damageNumbers.add(DamageNumber(victim.x, victim.y + 1.9f, 0f, dmg.toInt().toString(), 0xFF8C3A, 0f, true))
        emit(GameEvent.Hit(source, victim, dmg, victim.comboTaken, true))
        if (victim.hp <= 0f) kill(victim)
    }

    fun kill(f: Fighter) {
        if (!f.alive) return
        f.alive = false
        f.hp = 0f
        f.enterState(FState.KO)
        f.vx = -f.facing * 2.5f
        f.freezeTimer = 0f
        hitstop = 0.12f
        camera.shake(0.8f)
        burst(f.x, f.y + 1f, rgb(0xFF5A3C), 30, 4.5f)
        emit(GameEvent.KO(f))
        sound("ko")
    }

    fun burst(x: Float, y: Float, color: FloatArray, n: Int, speed: Float) {
        particles.burst(x, y, 0.15f, n, speed, 0.45f, 0.13f, color, 1f, 2f, 0.5f)
    }

    fun spawnProjectile(f: Fighter, skill: SkillDef, yOffset: Float, spreadY: Float) {
        val speed = skill.speed
        projectiles.add(Projectile(
            f.x + f.facing * 0.55f, 1.1f + yOffset, 0.1f,
            f.facing * speed, spreadY,
            f, skill, skill.range / speed + 0.4f, skill.radius
        ))
        burst(f.x + f.facing * 0.6f, 1.1f + yOffset, rgb(skill.color), 8, 2f)
    }
}
