package com.furyclash.game.game

import com.furyclash.game.engine.clamp
import com.furyclash.game.engine.rgb
import kotlin.math.abs
import kotlin.math.max

/**
 * Executes a skill's gameplay effect + VFX. Every skill type produces real
 * damage/status/movement — nothing is cosmetic-only.
 */
object SkillExecutor {

    fun fire(world: CombatWorld, f: Fighter, skill: SkillDef) {
        val opp = world.oppOf(f) ?: return
        val color = rgb(skill.color)
        val dir = f.facing.toFloat()
        val power = f.def.power * (if (f.damageBuff > 0f) f.damagePower else 1f)

        when (skill.type) {
            SkillType.PROJECTILE -> {
                val n = skill.hits
                for (i in 0 until n) {
                    val off = (i - (n - 1) / 2f) * 0.3f
                    world.spawnProjectile(f, skill, off, off * 0.4f)
                }
                world.sound(if (skill.effect == ProjEffect.FREEZE || skill.effect == ProjEffect.SLOW) "ice" else if (skill.effect == ProjEffect.BURN) "fire" else "shot")
            }

            SkillType.BEAM -> {
                world.beams.add(Beam(f.x + dir * 0.6f, 1.15f, dir, skill.range, color, 0.5f, 0.5f, skill.radius))
                world.particles.ring(f.x + dir * 0.7f, 1.15f, 0.1f, 16, 0.5f, 3f, 0.4f, 0.16f, color)
                val inLine = (opp.x - f.x) * dir > 0f && abs(opp.x - f.x) < skill.range && opp.y < 1.3f
                if (inLine && opp.alive) {
                    world.resolveHit(f, opp, skill.damage * power, 5.5f, 0.75f, 0.3f,
                        false, skill.knockdown, HitHeight.MID, skill.effect, skill.effectDuration,
                        8f, true, skill.cinematic)
                }
                for (i in 1..6) {
                    world.particles.burst(f.x + dir * i * skill.range / 6f, 1.15f, 0.1f, 6, 2f, 0.4f, 0.14f, color)
                }
                world.camera.shake(0.7f)
                world.sound("beam")
            }

            SkillType.TELEPORT_STRIKE -> {
                world.burst(f.x, f.y + 1f, color, 18, 3f)
                f.x = clamp(opp.x - opp.facing * 0.9f, -world.arenaHalf, world.arenaHalf)
                f.facing = if (opp.x >= f.x) 1 else -1
                world.burst(f.x, f.y + 1f, color, 18, 3f)
                if (opp.alive && abs(f.x - opp.x) < 1.4f) {
                    world.resolveHit(f, opp, skill.damage * power, 3.2f, 0.5f, 0.24f,
                        false, skill.knockdown, HitHeight.MID, ProjEffect.NONE, 0f, 7f, true, false)
                }
                world.sound("teleport")
            }

            SkillType.AOE_SELF -> {
                world.particles.ring(f.x, 0.15f, 0f, 26, skill.radius, 5f, 0.5f, 0.18f, color)
                world.burst(f.x, 0.4f, color, 24, 4f)
                if (opp.alive && abs(opp.x - f.x) < skill.radius + 0.35f && opp.y < 1.4f) {
                    world.resolveHit(f, opp, skill.damage * power, 4.2f, 0.6f, 0.28f,
                        skill.launcher, skill.knockdown, HitHeight.MID, skill.effect, skill.effectDuration,
                        8f, true, false)
                }
                if (skill.buff == BuffKind.DAMAGE) {
                    f.damageBuff = skill.buffDuration
                    f.damagePower = skill.buffPower
                }
                world.camera.shake(0.55f)
                world.sound("slam")
            }

            SkillType.AOE_TARGET -> {
                val tx = opp.x
                // warning circle
                world.particles.ring(tx, 0.12f, 0f, 20, skill.radius, 1.5f, 0.45f, 0.15f, color, 0.7f)
                world.pending(0.45f) {
                    world.particles.ring(tx, 0.15f, 0f, 30, skill.radius, 6f, 0.5f, 0.2f, color)
                    for (i in 0 until 24) {
                        world.particles.spawn(tx + (Math.random().toFloat() - 0.5f) * skill.radius * 2f, 0.1f, 0f,
                            0f, 5f + Math.random().toFloat() * 4f, 0f, 0.5f, 0.16f, color[0], color[1], color[2], 1f, 3f, 0.5f)
                    }
                    val o = world.oppOf(f)
                    if (o != null && o.alive && abs(o.x - tx) < skill.radius + 0.3f) {
                        world.resolveHit(f, o, skill.damage * power, 4.5f, 0.7f, 0.3f,
                            false, skill.knockdown, HitHeight.MID, skill.effect, skill.effectDuration,
                            8f, true, skill.cinematic)
                    }
                    world.camera.shake(0.7f)
                    world.sound("slam")
                }
            }

            SkillType.DASH_ATTACK -> {
                if (f.def.weight >= 1.3f) f.armorTimer = 0.4f // heavy bodies armor through
                val startX = f.x
                val targetX = clamp(f.x + dir * skill.range, -world.arenaHalf, world.arenaHalf)
                val steps = max(skill.hits, 3)
                var landed = 0
                for (i in 1..steps) {
                    world.pending(i * 0.07f) {
                        val t = i.toFloat() / steps
                        f.x = clamp(startX + (targetX - startX) * t, -world.arenaHalf, world.arenaHalf)
                        world.particles.spawn(f.x - dir * 0.4f, 1f, 0.1f, -dir * 2f, 0.3f, 0f, 0.3f, 0.15f,
                            color[0], color[1], color[2], 0.8f, 0f, 1f)
                        val o = world.oppOf(f)
                        if (o != null && o.alive && landed < skill.hits && abs(f.x - o.x) < 1.05f && o.y < 1.3f) {
                            landed++
                            val last = landed == skill.hits
                            world.resolveHit(f, o, skill.damage * power, if (last) 3.5f else 0.8f,
                                if (last) 0.5f else 0.42f, 0.2f, false, last && skill.knockdown,
                                HitHeight.MID, skill.effect, skill.effectDuration, 5f, last, false)
                            f.facing = if (o.x >= f.x) 1 else -1
                        }
                    }
                }
                world.sound("dash")
            }

            SkillType.BUFF -> {
                when (skill.buff) {
                    BuffKind.SPEED -> { f.speedBuff = skill.buffDuration; f.speedPower = skill.buffPower; f.damageBuff = skill.buffDuration; f.damagePower = 1.25f }
                    BuffKind.DAMAGE -> { f.damageBuff = skill.buffDuration; f.damagePower = skill.buffPower }
                    BuffKind.SHIELD -> f.shieldHits = skill.buffPower.toInt()
                    BuffKind.COUNTER_STANCE -> f.counterStance = skill.buffDuration
                    BuffKind.ARMOR -> f.armorTimer = skill.buffDuration
                    null -> {}
                }
                world.particles.ring(f.x, 0.2f, 0f, 22, 1f, 2.5f, 0.6f, 0.16f, color)
                world.burst(f.x, 1.2f, color, 16, 2f)
                world.sound("buff")
            }

            SkillType.SPIN -> {
                for (i in 0 until skill.hits) {
                    world.pending(i * 0.13f) {
                        world.particles.ring(f.x, 0.8f + i * 0.15f, 0f, 12, skill.radius, 3f, 0.3f, 0.14f, color, 0.8f)
                        val o = world.oppOf(f)
                        if (o != null && o.alive && abs(o.x - f.x) < skill.radius + 0.3f && o.y < 1.5f) {
                            val last = i == skill.hits - 1
                            world.resolveHit(f, o, skill.damage * power, if (last) 2.5f else 0.7f,
                                0.42f, 0.18f, last && skill.launcher, false, HitHeight.MID,
                                skill.effect, skill.effectDuration, 4f, last, false)
                        }
                    }
                }
                world.sound("spin")
            }

            SkillType.SKY_STRIKE -> {
                val perHit = skill.damage / skill.hits
                for (i in 0 until skill.hits) {
                    world.pending(0.4f + i * 0.28f) {
                        val o = world.oppOf(f) ?: return@pending
                        val tx = o.x
                        world.particles.ring(tx, 0.12f, 0f, 16, skill.radius, 1.5f, 0.25f, 0.14f, color, 0.7f)
                        world.pending(0.22f) {
                            // lightning column
                            for (k in 0 until 20) {
                                world.particles.spawn(tx + (Math.random().toFloat() - 0.5f) * 0.5f, 6f - k * 0.3f, 0f,
                                    0f, -8f, 0f, 0.3f, 0.18f, color[0], color[1], color[2], 1f, 0f, 0f)
                            }
                            world.particles.ring(tx, 0.15f, 0f, 24, skill.radius, 5f, 0.4f, 0.18f, color)
                            val oo = world.oppOf(f)
                            if (oo != null && oo.alive && abs(oo.x - tx) < skill.radius + 0.3f) {
                                world.resolveHit(f, oo, perHit * power, 2.5f, 0.55f, 0.25f,
                                    false, skill.knockdown && i == skill.hits - 1, HitHeight.MID,
                                    skill.effect, skill.effectDuration, 5f, true, skill.cinematic)
                            }
                            world.camera.shake(0.6f)
                            world.sound("lightning")
                        }
                    }
                }
            }

            SkillType.MULTI_RUSH -> {
                val n = skill.hits
                val interval = if (n > 1) 1.0f / n else 0f
                for (i in 0 until n) {
                    world.pending(0.1f + i * interval) {
                        val o = world.oppOf(f) ?: return@pending
                        if (!o.alive) return@pending
                        val side = if (i % 2 == 0) -o.facing.toFloat() else o.facing.toFloat()
                        if (n > 1) {
                            world.burst(f.x, f.y + 1f, color, 8, 2.5f)
                            f.x = clamp(o.x + side * 0.85f, -world.arenaHalf, world.arenaHalf)
                            f.facing = if (o.x >= f.x) 1 else -1
                        }
                        world.burst((f.x + o.x) / 2f, o.y + 1.1f, color, 12, 3.5f)
                        val last = i == n - 1
                        world.resolveHit(f, o, skill.damage * power,
                            if (last) 5f else 0.5f, if (last) 0.65f else 0.5f, 0.2f,
                            false, last && skill.knockdown, HitHeight.MID,
                            skill.effect, skill.effectDuration, 4f, last, skill.cinematic)
                        if (last) {
                            world.camera.shake(0.9f)
                            world.particles.ring(o.x, 0.2f, 0f, 30, 2f, 6f, 0.5f, 0.2f, color)
                        }
                    }
                }
                world.sound(if (n > 1) "rush" else "heavy")
            }
        }
    }
}
