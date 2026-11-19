package com.furyclash.game.game

import com.furyclash.game.engine.clamp
import com.furyclash.game.engine.lerp
import com.furyclash.game.engine.rgb
import com.furyclash.game.engine.smoothstep
import com.furyclash.game.input.InputState
import kotlin.math.abs

enum class FState {
    IDLE, WALK, RUN, AIR, CROUCH, ATTACK, BLOCK, BLOCKSTUN, HITSTUN, LAUNCHED,
    KNOCKDOWN, GETUP, CAST, STAGGER, KO, VICTORY, DEFEAT, INTRO, SWITCH_IN
}

enum class HitHeight { MID, LOW, OVERHEAD }

data class AttackDef(
    val id: String,
    val damage: Float,
    val startup: Float,
    val active: Float,
    val recovery: Float,
    val reach: Float,
    val height: HitHeight,
    val knockback: Float,
    val hitstun: Float,
    val blockstun: Float,
    val anim: String,
    val arm: Int = 0,
    val launcher: Boolean = false,
    val knockdown: Boolean = false,
    val energyGain: Float = 7f,
    val cancelWindow: Float = 0f,
    val lunge: Float = 0f
) {
    val total = startup + active + recovery
}

/**
 * Runtime fighter: physics + state machine + animation blending.
 * Pure gameplay logic — no Android dependencies (unit-testable).
 */
class Fighter(val def: CharacterDef, val team: Int, val slot: Int, val model: FighterModel = ModelRegistry.models[def.id]) {

    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var facing = 1
    var hp = def.hp
    var energy = 0f
    var alive = true

    var state = FState.IDLE
    var stateTime = 0f
    var attack: AttackDef? = null
    var attackHit = false
    var chainReady = false
    var castSkill: SkillDef? = null
    var castTotal = 0.62f
    var castFired = false
    var hitstunDur = 0.3f
    var crouchBlocking = false

    val cooldowns = FloatArray(3)
    var invuln = 0f
    var switchCooldown = 0f
    var freezeTimer = 0f
    var slowTimer = 0f
    var burnTimer = 0f
    var speedBuff = 0f
    var speedPower = 1f
    var damageBuff = 0f
    var damagePower = 1f
    var shieldHits = 0
    var counterStance = 0f
    var armorTimer = 0f
    var flashTimer = 0f
    var blockAge = 99f

    // combo bookkeeping
    var comboDealt = 0       // hits landed in current combo (attacker side)
    var comboTaken = 0       // hits received in current combo (victim side, for scaling)
    var comboResetTimer = 0f
    var lastDamageDealt = 0f

    // animation
    val pose = FloatArray(Joints.POSE_SIZE)
    private val prevPose = FloatArray(Joints.POSE_SIZE)
    private val targetPose = FloatArray(Joints.POSE_SIZE)
    private var blend = 1f
    private var animName = "idle"
    private var animTime = 0f
    private var burnTick = 0f

    val grounded get() = y <= 0.001f
    val maxHp get() = def.hp
    val hpFraction get() = clamp(hp / def.hp, 0f, 1f)
    val busy get() = state == FState.ATTACK || state == FState.CAST || state == FState.HITSTUN ||
        state == FState.BLOCKSTUN || state == FState.LAUNCHED || state == FState.KNOCKDOWN ||
        state == FState.GETUP || state == FState.STAGGER || state == FState.KO || state == FState.SWITCH_IN
    val actionable get() = !busy && freezeTimer <= 0f && alive

    fun enterState(s: FState) {
        if (state == s) return
        System.arraycopy(pose, 0, prevPose, 0, pose.size)
        blend = 0f
        state = s
        stateTime = 0f
    }

    fun addEnergy(v: Float) { energy = clamp(energy + v, 0f, 100f) }

    fun resetForRound(px: Float, face: Int) {
        x = px; y = 0f; vx = 0f; vy = 0f
        facing = face
        hp = def.hp
        energy = 0f
        alive = true
        attack = null; castSkill = null
        cooldowns.fill(0f)
        invuln = 0f; switchCooldown = 0f; freezeTimer = 0f; slowTimer = 0f; burnTimer = 0f
        speedBuff = 0f; damageBuff = 0f; shieldHits = 0; counterStance = 0f; armorTimer = 0f
        flashTimer = 0f; comboDealt = 0; comboTaken = 0; comboResetTimer = 0f
        enterState(FState.INTRO)
    }

    fun startAttack(a: AttackDef) {
        attack = a
        attackHit = false
        chainReady = false
        enterState(FState.ATTACK)
        stateTime = 0f // enterState no-ops when already ATTACK (chain cancels)
        vx = facing * a.lunge
        animName = a.anim
    }

    fun startCast(index: Int, world: CombatWorld) {
        val skill = def.skills[index]
        energy -= skill.cost
        cooldowns[index] = skill.cooldown
        castSkill = skill
        castFired = false
        castTotal = if (skill.cinematic) 1.5f else 0.62f
        enterState(FState.CAST)
        stateTime = 0f
        animName = skill.anim
        vx = 0f
        if (skill.cinematic) {
            armorTimer = castTotal
            world.startCinematic(this)
            world.emit(GameEvent.Ultimate(this, skill))
        } else {
            world.emit(GameEvent.SkillUsed(this, skill))
        }
    }

    fun update(dt: Float, input: InputState, world: CombatWorld, opp: Fighter?) {
        // ---- timers ----
        for (i in cooldowns.indices) cooldowns[i] = maxOf(0f, cooldowns[i] - dt)
        invuln = maxOf(0f, invuln - dt)
        switchCooldown = maxOf(0f, switchCooldown - dt)
        flashTimer = maxOf(0f, flashTimer - dt)
        slowTimer = maxOf(0f, slowTimer - dt)
        speedBuff = maxOf(0f, speedBuff - dt)
        damageBuff = maxOf(0f, damageBuff - dt)
        counterStance = maxOf(0f, counterStance - dt)
        armorTimer = maxOf(0f, armorTimer - dt)
        if (freezeTimer > 0f) freezeTimer = maxOf(0f, freezeTimer - dt)
        if (burnTimer > 0f) {
            burnTimer -= dt
            hp -= 18f * dt
            burnTick -= dt
            if (burnTick <= 0f) {
                burnTick = 0.09f
                world.particles.spawn(x + (Math.random().toFloat() - 0.5f) * 0.4f, y + 0.9f, 0.1f,
                    0f, 1.2f, 0f, 0.4f, 0.1f, 1f, 0.55f, 0.15f, 0.8f, 0f, 1f)
            }
            if (hp <= 0f && alive) world.kill(this)
        }
        if (comboResetTimer > 0f) {
            comboResetTimer -= dt
            if (comboResetTimer <= 0f) { comboTaken = 0; comboDealt = 0 }
        }
        if (state == FState.BLOCK) blockAge += dt else blockAge = 99f

        stateTime += dt
        animTime += dt

        // ---- KO / frozen: physics only ----
        if (state == FState.KO || state == FState.DEFEAT || state == FState.VICTORY) {
            physics(dt, world)
            updateAnim(dt)
            return
        }
        if (freezeTimer > 0f) {
            physics(dt, world)
            updateAnim(dt)
            return
        }

        when (state) {
            FState.HITSTUN -> if (stateTime >= hitstunDur) enterState(FState.IDLE)
            FState.BLOCKSTUN -> if (stateTime >= hitstunDur) enterState(FState.IDLE)
            FState.STAGGER -> if (stateTime >= 0.6f) enterState(FState.IDLE)
            FState.KNOCKDOWN -> if (stateTime >= 0.62f) { enterState(FState.GETUP); invuln = 0.4f }
            FState.GETUP -> if (stateTime >= 0.45f) enterState(FState.IDLE)
            FState.LAUNCHED -> { /* physics handles landing */ }
            FState.SWITCH_IN -> if (stateTime >= 0.35f) enterState(FState.IDLE)
            FState.INTRO -> { /* battle flow flips to IDLE */ }
            FState.ATTACK -> updateAttack(dt, input, world)
            FState.CAST -> updateCast(dt, world)
            FState.AIR -> updateAir(dt, input, world)
            else -> updateGrounded(dt, input, world, opp)
        }

        // auto-face opponent while neutral
        if (opp != null && (state == FState.IDLE || state == FState.WALK || state == FState.RUN ||
                state == FState.CROUCH || state == FState.BLOCK || state == FState.AIR)) {
            facing = if (opp.x >= x) 1 else -1
        }

        physics(dt, world)
        updateAnim(dt)
    }

    private fun updateAttack(dt: Float, input: InputState, world: CombatWorld) {
        val a = attack ?: return
        // active frames: attempt hit
        if (!attackHit && stateTime >= a.startup && stateTime <= a.startup + a.active) {
            world.tryMeleeHit(this, a)
        }
        // chain cancel on connect during early recovery
        if (chainReady && a.cancelWindow > 0f &&
            stateTime >= a.startup + a.active &&
            stateTime <= a.startup + a.active + a.cancelWindow) {
            val next = when {
                input.consume(InputState.PUNCH) && a.id == "punch" -> PUNCH2
                input.consume(InputState.KICK) && (a.id == "punch" || a.id == "punch2") -> KICK
                input.consume(InputState.HEAVY) && a.id != "heavy" -> HEAVY
                else -> null
            }
            if (next != null) { startAttack(next); return }
        }
        if (stateTime >= a.total) {
            attack = null
            if (grounded) enterState(FState.IDLE) else enterState(FState.AIR)
        }
    }

    private fun updateCast(dt: Float, world: CombatWorld) {
        val skill = castSkill ?: return
        if (!castFired && stateTime >= castTotal * 0.42f) {
            castFired = true
            SkillExecutor.fire(world, this, skill)
        }
        if (stateTime >= castTotal) {
            castSkill = null
            enterState(FState.IDLE)
        }
    }

    private fun updateAir(dt: Float, input: InputState, world: CombatWorld) {
        // limited air drift
        vx += input.moveX * 6f * dt
        vx = clamp(vx, -def.runSpeed, def.runSpeed)
        if (input.consume(InputState.PUNCH)) { startAttack(AIR_PUNCH); return }
        if (input.consume(InputState.KICK)) { startAttack(AIR_KICK); return }
    }

    private fun updateGrounded(dt: Float, input: InputState, world: CombatWorld, opp: Fighter?) {
        val speedMul = (if (slowTimer > 0f) 0.5f else 1f) * (if (speedBuff > 0f) speedPower else 1f)

        // block (hold)
        if (input.blockHeld) {
            crouchBlocking = input.moveY < -0.5f
            if (state != FState.BLOCK) {
                enterState(FState.BLOCK)
                blockAge = 0f
            }
            vx = 0f
            // can still throw attacks out of block? no — block is committal, but skills allowed
            trySkills(input, world)
            return
        }
        if (state == FState.BLOCK) enterState(FState.IDLE)

        // attacks
        if (input.consume(InputState.PUNCH)) {
            startAttack(if (input.moveY < -0.5f) CROUCH_PUNCH else PUNCH); return
        }
        if (input.consume(InputState.KICK)) {
            startAttack(if (input.moveY < -0.5f) SWEEP else KICK); return
        }
        if (input.consume(InputState.HEAVY)) { startAttack(HEAVY); return }
        if (trySkills(input, world)) return

        // jump
        if (input.consume(InputState.JUMP) || input.moveY > 0.6f) {
            vy = def.jumpVel
            y = 0.002f
            enterState(FState.AIR)
            vx = input.moveX * def.runSpeed * 0.8f * speedMul
            world.emit(GameEvent.Jump(this))
            return
        }

        // crouch
        if (input.moveY < -0.5f) {
            if (state != FState.CROUCH) enterState(FState.CROUCH)
            vx = 0f
            return
        }
        if (state == FState.CROUCH) enterState(FState.IDLE)

        // move
        val mx = input.moveX
        if (abs(mx) > 0.15f) {
            val running = abs(mx) > 0.82f
            val speed = (if (running) def.runSpeed else def.walkSpeed) * speedMul
            vx = mx * speed
            val want = if (running) FState.RUN else FState.WALK
            if (state != want) enterState(want)
        } else {
            vx = 0f
            if (state != FState.IDLE) enterState(FState.IDLE)
        }
    }

    private fun trySkills(input: InputState, world: CombatWorld): Boolean {
        if (!grounded) return false
        if (input.consume(InputState.S1) && energy >= def.skills[0].cost && cooldowns[0] <= 0f) { startCast(0, world); return true }
        if (input.consume(InputState.S2) && energy >= def.skills[1].cost && cooldowns[1] <= 0f) { startCast(1, world); return true }
        if (input.consume(InputState.ULT) && energy >= def.skills[2].cost && cooldowns[2] <= 0f) { startCast(2, world); return true }
        return false
    }

    private fun physics(dt: Float, world: CombatWorld) {
        // knockback friction
        if (state == FState.HITSTUN || state == FState.BLOCKSTUN || state == FState.KNOCKDOWN ||
            state == FState.STAGGER || state == FState.KO || state == FState.LAUNCHED) {
            vx *= maxOf(0f, 1f - 5.5f * dt)
        } else if (state == FState.ATTACK || state == FState.CAST) {
            vx *= maxOf(0f, 1f - 8f * dt)
        }
        x += vx * dt
        x = clamp(x, -world.arenaHalf, world.arenaHalf)

        if (y > 0f || vy > 0f) {
            vy -= GRAVITY * dt
            y += vy * dt
            if (y <= 0f) {
                y = 0f; vy = 0f
                when (state) {
                    FState.LAUNCHED -> { enterState(FState.KNOCKDOWN); world.emit(GameEvent.Land(this, true)) }
                    FState.AIR -> enterState(FState.IDLE)
                    FState.ATTACK -> { attack = null; enterState(FState.IDLE) }
                    else -> {}
                }
            }
        }
    }

    /**
     * Remote-replica tick: no gameplay logic, only dead-reckoning between
     * snapshots plus animation blending so the guest renders smooth motion.
     */
    fun visualTick(dt: Float, arenaHalf: Float) {
        stateTime += dt
        animTime += dt
        x = clamp(x + vx * dt, -arenaHalf, arenaHalf)
        if (y > 0f || vy > 0f) {
            vy -= GRAVITY * dt
            y += vy * dt
            if (y <= 0f) { y = 0f; vy = 0f }
        }
        updateAnim(dt)
    }

    private fun updateAnim(dt: Float) {
        var name = "idle"
        var t = animTime
        var p1 = 0f
        var p2 = 0f
        var oneShotTotal = 0f
        when (state) {
            FState.IDLE -> name = "idle"
            FState.WALK -> name = "walk"
            FState.RUN -> name = "run"
            FState.AIR -> { name = "air"; p2 = vy }
            FState.CROUCH -> name = "crouch"
            FState.BLOCK -> { name = "block"; p1 = if (crouchBlocking) 1f else 0f }
            FState.BLOCKSTUN -> { name = "block"; p1 = if (crouchBlocking) 1f else 0f }
            FState.ATTACK -> {
                val a = attack
                if (a != null) { name = a.anim; p1 = a.arm.toFloat(); oneShotTotal = a.total }
            }
            FState.CAST -> { name = castSkill?.anim ?: "cast"; oneShotTotal = castTotal }
            FState.HITSTUN -> { name = "hit"; oneShotTotal = hitstunDur }
            FState.LAUNCHED -> { name = "launched"; t = stateTime }
            FState.KNOCKDOWN -> { name = "knockdown"; t = stateTime }
            FState.GETUP -> { name = "getup"; oneShotTotal = 0.45f }
            FState.STAGGER -> { name = "stagger"; oneShotTotal = 0.6f }
            FState.KO -> { name = "knockdown"; t = stateTime }
            FState.VICTORY -> name = "victory"
            FState.DEFEAT -> { name = "defeat"; t = stateTime }
            FState.INTRO -> name = "intro"
            FState.SWITCH_IN -> { name = "switchin"; oneShotTotal = 0.35f }
        }
        if (oneShotTotal > 0f) t = clamp(stateTime / oneShotTotal, 0f, 1f)
        if (freezeTimer > 0f) { name = "block"; t = 0f }
        if (name != animName) { animName = name; animTime = 0f }
        Anims.compute(name, t, targetPose, p1, p2)
        blend = clamp(blend + dt / 0.08f, 0f, 1f)
        val k = smoothstep(blend)
        for (i in pose.indices) pose[i] = lerp(prevPose[i], targetPose[i], k)
        model.compute(x, y, facing, pose)
    }

    /** Visual tint: hit flash / freeze / burn, computed at draw time. */
    fun tint(out: FloatArray): Boolean {
        when {
            flashTimer > 0f -> { out[0] = 2.2f; out[1] = 1.6f; out[2] = 1.6f; return true }
            freezeTimer > 0f -> { out[0] = 0.6f; out[1] = 0.85f; out[2] = 1.5f; return true }
            burnTimer > 0f -> { out[0] = 1.4f; out[1] = 0.9f; out[2] = 0.7f; return true }
            counterStance > 0f -> { out[0] = 1.3f; out[1] = 1.1f; out[2] = 0.8f; return true }
            speedBuff > 0f -> { out[0] = 0.8f; out[1] = 1.4f; out[2] = 1.1f; return true }
            else -> return false
        }
    }

    companion object {
        const val GRAVITY = 18f

        val PUNCH = AttackDef("punch", 45f, 0.08f, 0.07f, 0.16f, 0.95f, HitHeight.MID, 1.3f, 0.34f, 0.18f, "punch", arm = 0, cancelWindow = 0.14f, lunge = 1.3f)
        val PUNCH2 = AttackDef("punch2", 48f, 0.07f, 0.07f, 0.17f, 1.0f, HitHeight.MID, 1.5f, 0.36f, 0.18f, "punch", arm = 1, cancelWindow = 0.15f, lunge = 1.4f)
        val KICK = AttackDef("kick", 62f, 0.12f, 0.08f, 0.22f, 1.15f, HitHeight.MID, 2.2f, 0.40f, 0.20f, "kick", cancelWindow = 0.16f, lunge = 1.7f)
        val HEAVY = AttackDef("heavy", 95f, 0.24f, 0.10f, 0.34f, 1.25f, HitHeight.MID, 4.8f, 0.58f, 0.26f, "heavy", lunge = 2.3f, energyGain = 10f)
        val AIR_PUNCH = AttackDef("airpunch", 50f, 0.08f, 0.14f, 0.10f, 0.95f, HitHeight.OVERHEAD, 1.6f, 0.36f, 0.18f, "airpunch", lunge = 0f)
        val AIR_KICK = AttackDef("airkick", 68f, 0.12f, 0.16f, 0.12f, 1.15f, HitHeight.OVERHEAD, 2.8f, 0.42f, 0.20f, "airkick", lunge = 0f)
        val CROUCH_PUNCH = AttackDef("crouchpunch", 40f, 0.07f, 0.06f, 0.14f, 0.9f, HitHeight.LOW, 1.0f, 0.30f, 0.16f, "crouchpunch", cancelWindow = 0.12f, lunge = 0.8f)
        val SWEEP = AttackDef("sweep", 72f, 0.16f, 0.09f, 0.30f, 1.2f, HitHeight.LOW, 3.2f, 0.5f, 0.24f, "sweep", knockdown = true, lunge = 1.2f)
    }
}
