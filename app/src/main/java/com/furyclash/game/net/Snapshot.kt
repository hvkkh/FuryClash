package com.furyclash.game.net

import com.furyclash.game.engine.rgb
import com.furyclash.game.game.Beam
import com.furyclash.game.game.Characters
import com.furyclash.game.game.FState
import com.furyclash.game.game.Fighter
import com.furyclash.game.game.Phase
import com.furyclash.game.game.Projectile
import com.furyclash.game.game.TeamBattle
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Host-authoritative state sync: the host serializes the live TeamBattle at
 * ~20 Hz; the guest applies it onto a replica battle (same classes, no local
 * simulation). Positions/velocities are included so the guest can dead-reckon
 * between snapshots for smooth motion.
 */
object Snapshot {
    private val ATTACKS = arrayOf(
        Fighter.PUNCH, Fighter.PUNCH2, Fighter.KICK, Fighter.HEAVY,
        Fighter.AIR_PUNCH, Fighter.AIR_KICK, Fighter.CROUCH_PUNCH, Fighter.SWEEP
    )
    private val dummySkill = Characters.all[0].skills[0]

    fun capture(b: TeamBattle): ByteArray = Proto.encode(Proto.SNAPSHOT) { d ->
        val w = b.world
        d.writeByte(b.phase.ordinal)
        d.writeFloat(b.phaseTime)
        d.writeByte(b.round)
        d.writeFloat(b.timer)
        d.writeByte(b.matchWinner + 1)
        d.writeByte(b.roundWinner + 1)
        d.writeFloat(w.hitstop)
        d.writeFloat(w.timeScale)
        d.writeFloat(w.cinematicTimer)
        d.writeByte(if (w.cinematicFocus === w.p1) 0 else if (w.cinematicFocus === w.p2) 1 else -1)
        d.writeFloat(w.flash)
        d.writeInt(w.flashColor)
        for (t in 0..1) {
            val team = b.teams[t]
            d.writeByte(team.active)
            d.writeByte(team.roundsWon)
            d.writeFloat(team.switchCooldown)
            for (f in team.fighters) writeFighter(d, f)
        }
        d.writeByte(w.projectiles.size)
        for (p in w.projectiles) {
            d.writeFloat(p.x); d.writeFloat(p.y); d.writeFloat(p.z)
            d.writeFloat(p.vx); d.writeFloat(p.vy)
            d.writeFloat(p.life); d.writeFloat(p.radius)
            d.writeByte(p.owner.team)
            d.writeInt(packColor(p.color))
        }
        d.writeByte(w.beams.size)
        for (bm in w.beams) {
            d.writeFloat(bm.x); d.writeFloat(bm.y); d.writeFloat(bm.dir); d.writeFloat(bm.len)
            d.writeFloat(bm.life); d.writeFloat(bm.maxLife); d.writeFloat(bm.radius)
            d.writeInt(packColor(bm.color))
        }
    }

    private fun packColor(c: FloatArray): Int =
        ((c[0] * 255).toInt() shl 16) or ((c[1] * 255).toInt() shl 8) or (c[2] * 255).toInt()

    private fun writeFighter(d: DataOutputStream, f: Fighter) {
        d.writeBoolean(f.alive)
        d.writeFloat(f.hp); d.writeFloat(f.energy)
        d.writeFloat(f.x); d.writeFloat(f.y); d.writeFloat(f.vx); d.writeFloat(f.vy)
        d.writeByte(f.facing + 1)
        d.writeByte(f.state.ordinal)
        d.writeFloat(f.stateTime)
        d.writeByte(ATTACKS.indexOf(f.attack) + 1)
        d.writeByte(if (f.castSkill == null) -1 else f.def.skills.indexOf(f.castSkill))
        d.writeFloat(f.castTotal)
        d.writeBoolean(f.castFired)
        d.writeFloat(f.hitstunDur)
        d.writeBoolean(f.crouchBlocking)
        for (i in 0..2) d.writeFloat(f.cooldowns[i])
        d.writeFloat(f.invuln); d.writeFloat(f.freezeTimer); d.writeFloat(f.slowTimer); d.writeFloat(f.burnTimer)
        d.writeFloat(f.speedBuff); d.writeFloat(f.speedPower); d.writeFloat(f.damageBuff); d.writeFloat(f.damagePower)
        d.writeByte(f.shieldHits)
        d.writeFloat(f.counterStance); d.writeFloat(f.armorTimer); d.writeFloat(f.flashTimer); d.writeFloat(f.blockAge)
        d.writeByte(f.comboDealt); d.writeByte(f.comboTaken)
        d.writeFloat(f.comboResetTimer); d.writeFloat(f.lastDamageDealt)
    }

    fun apply(b: TeamBattle, blob: ByteArray) {
        val d = Proto.reader(blob)
        val w = b.world
        val phase = Phase.entries[d.readByte().toInt()]
        if (b.phase != phase) {
            b.phase = phase
        }
        b.phaseTime = d.readFloat()
        b.round = d.readByte().toInt()
        b.timer = d.readFloat()
        b.matchWinner = d.readByte().toInt() - 1
        b.roundWinner = d.readByte().toInt() - 1
        w.hitstop = d.readFloat()
        w.timeScale = d.readFloat()
        w.cinematicTimer = d.readFloat()
        val cin = d.readByte().toInt()
        w.cinematicFocus = if (cin == 0) w.p1 else if (cin == 1) w.p2 else null
        w.flash = d.readFloat()
        w.flashColor = d.readInt()
        for (t in 0..1) {
            val team = b.teams[t]
            team.active = d.readByte().toInt()
            team.roundsWon = d.readByte().toInt()
            team.switchCooldown = d.readFloat()
            for (f in team.fighters) readFighter(d, f)
        }
        w.p1 = b.active(0)
        w.p2 = b.active(1)
        val np = d.readByte().toInt()
        w.projectiles.clear()
        for (i in 0 until np) {
            val x = d.readFloat(); val y = d.readFloat(); val z = d.readFloat()
            val vx = d.readFloat(); val vy = d.readFloat()
            val life = d.readFloat(); val radius = d.readFloat()
            val ownerTeam = d.readByte().toInt()
            val color = rgb(d.readInt())
            val p = Projectile(x, y, z, vx, vy, b.active(ownerTeam), dummySkill, life, radius)
            p.color = color
            w.projectiles.add(p)
        }
        val nb = d.readByte().toInt()
        w.beams.clear()
        for (i in 0 until nb) {
            val x = d.readFloat(); val y = d.readFloat(); val dir = d.readFloat(); val len = d.readFloat()
            val life = d.readFloat(); val maxLife = d.readFloat(); val radius = d.readFloat()
            w.beams.add(Beam(x, y, dir, len, rgb(d.readInt()), life, maxLife, radius))
        }
    }

    private fun readFighter(d: DataInputStream, f: Fighter) {
        f.alive = d.readBoolean()
        f.hp = d.readFloat(); f.energy = d.readFloat()
        f.x = d.readFloat(); f.y = d.readFloat(); f.vx = d.readFloat(); f.vy = d.readFloat()
        f.facing = d.readByte().toInt() - 1
        val st = FState.entries[d.readByte().toInt()]
        if (f.state != st) f.enterState(st)
        f.stateTime = d.readFloat()
        val atk = d.readByte().toInt() - 1
        f.attack = if (atk >= 0) ATTACKS[atk] else null
        val cast = d.readByte().toInt()
        f.castSkill = if (cast >= 0) f.def.skills[cast] else null
        f.castTotal = d.readFloat()
        f.castFired = d.readBoolean()
        f.hitstunDur = d.readFloat()
        f.crouchBlocking = d.readBoolean()
        for (i in 0..2) f.cooldowns[i] = d.readFloat()
        f.invuln = d.readFloat(); f.freezeTimer = d.readFloat(); f.slowTimer = d.readFloat(); f.burnTimer = d.readFloat()
        f.speedBuff = d.readFloat(); f.speedPower = d.readFloat(); f.damageBuff = d.readFloat(); f.damagePower = d.readFloat()
        f.shieldHits = d.readByte().toInt()
        f.counterStance = d.readFloat(); f.armorTimer = d.readFloat(); f.flashTimer = d.readFloat(); f.blockAge = d.readFloat()
        f.comboDealt = d.readByte().toInt(); f.comboTaken = d.readByte().toInt()
        f.comboResetTimer = d.readFloat(); f.lastDamageDealt = d.readFloat()
    }
}
