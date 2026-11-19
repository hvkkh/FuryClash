package com.furyclash.game.game

import com.furyclash.game.engine.M4
import com.furyclash.game.engine.Mesh
import com.furyclash.game.engine.MeshBuilder
import com.furyclash.game.engine.Renderer
import com.furyclash.game.engine.rgb

/**
 * Joint indices. Pose layout: [j*3+0..2] = Euler rx,ry,rz per joint,
 * [36]=rootYOffset [37]=rootPitch [38]=rootRoll [39]=rootForward [40]=rootYaw.
 * Model faces +Z in local space; limbs hang along -Y.
 */
object Joints {
    const val ROOT = 0; const val SPINE = 1; const val CHEST = 2; const val HEAD = 3
    const val SHOULDER_L = 4; const val ELBOW_L = 5; const val SHOULDER_R = 6; const val ELBOW_R = 7
    const val HIP_L = 8; const val KNEE_L = 9; const val HIP_R = 10; const val KNEE_R = 11
    const val COUNT = 12
    const val POSE_SIZE = 41
    val PARENT = intArrayOf(-1, ROOT, SPINE, CHEST, CHEST, SHOULDER_L, CHEST, SHOULDER_R, ROOT, HIP_L, ROOT, HIP_R)
}

/** One shared 3D model per character (meshes upload to GPU lazily). */
object ModelRegistry {
    val models: List<FighterModel> = Characters.all.map { FighterModel(it) }
}

class FighterModel(val def: CharacterDef) {
    private class Part(val mesh: Mesh, val joint: Int, val ox: Float, val oy: Float, val oz: Float)

    private val parts = ArrayList<Part>(24)
    private val jointWorld = Array(Joints.COUNT) { FloatArray(16) }
    private val offsets = Array(Joints.COUNT) { FloatArray(3) }
    private val mA = FloatArray(16)
    private val mB = FloatArray(16)
    private val mC = FloatArray(16)
    private val mPart = FloatArray(16)
    private val hipBase = 0.90f * def.height

    init {
        val b = def.bulk
        val h = def.height
        val skin = rgb(def.skin)
        val primary = rgb(def.primary)
        val secondary = rgb(def.secondary)
        val accent = rgb(def.accent)
        val hair = rgb(def.hair)
        val glow = if (def.glow) 0.85f else 0f

        offsets[Joints.SPINE][1] = 0.10f * h
        offsets[Joints.CHEST][1] = 0.24f * h
        offsets[Joints.HEAD][1] = 0.26f * h
        offsets[Joints.SHOULDER_L][0] = 0.24f * b; offsets[Joints.SHOULDER_L][1] = 0.20f * h
        offsets[Joints.SHOULDER_R][0] = -0.24f * b; offsets[Joints.SHOULDER_R][1] = 0.20f * h
        offsets[Joints.ELBOW_L][1] = -0.30f * h; offsets[Joints.ELBOW_R][1] = -0.30f * h
        offsets[Joints.HIP_L][0] = 0.12f * b; offsets[Joints.HIP_L][1] = -0.05f * h
        offsets[Joints.HIP_R][0] = -0.12f * b; offsets[Joints.HIP_R][1] = -0.05f * h
        offsets[Joints.KNEE_L][1] = -0.40f * h; offsets[Joints.KNEE_R][1] = -0.40f * h

        fun part(joint: Int, ox: Float, oy: Float, oz: Float, build: (MeshBuilder) -> Unit) {
            val mb = MeshBuilder()
            build(mb)
            parts.add(Part(mb.build(), joint, ox, oy, oz))
        }

        // pelvis + belt
        part(Joints.ROOT, 0f, 0f, 0f) { mb ->
            mb.box(0f, 0.02f, 0f, 0.34f * b, 0.18f, 0.23f, secondary)
            mb.box(0f, 0.09f, 0f, 0.35f * b, 0.035f, 0.24f, accent, glow * 0.5f)
        }
        // abdomen
        part(Joints.SPINE, 0f, 0f, 0f) { mb ->
            mb.box(0f, 0.11f * h, 0f, 0.30f * b, 0.22f * h, 0.21f, primary)
        }
        // chest + accent stripe
        part(Joints.CHEST, 0f, 0f, 0f) { mb ->
            mb.box(0f, 0.10f * h, 0f, 0.42f * b, 0.27f * h, 0.26f, primary)
            mb.box(0f, 0.10f * h, 0.13f, 0.11f * b, 0.20f * h, 0.012f, accent, glow)
        }
        // head + face + headgear
        part(Joints.HEAD, 0f, 0f, 0f) { mb ->
            mb.sphere(0f, 0.10f * h, 0f, 0.13f, 7, 10, skin)
            // eyes
            mb.box(0.05f, 0.12f * h, 0.112f, 0.03f, 0.018f, 0.012f, rgb(0x1A1A22), 0.15f)
            mb.box(-0.05f, 0.12f * h, 0.112f, 0.03f, 0.018f, 0.012f, rgb(0x1A1A22), 0.15f)
            when (def.headgear) {
                Headgear.NONE -> mb.sphere(0f, 0.145f * h, -0.018f, 0.132f, 5, 9, hair, scaleY = 0.72f)
                Headgear.HOOD -> {
                    mb.sphere(0f, 0.115f * h, -0.025f, 0.15f, 6, 10, secondary, scaleY = 1.05f)
                    mb.box(0f, 0.10f * h, 0.105f, 0.13f, 0.10f, 0.03f, skin)
                    mb.box(0f, 0.125f * h, 0.115f, 0.12f, 0.018f, 0.012f, accent, glow)
                }
                Headgear.MASK -> {
                    mb.sphere(0f, 0.145f * h, -0.018f, 0.132f, 5, 9, hair, scaleY = 0.72f)
                    mb.box(0f, 0.055f * h, 0.075f, 0.17f, 0.09f, 0.09f, secondary)
                    mb.box(0f, 0.125f * h, 0.108f, 0.13f, 0.016f, 0.012f, accent, glow)
                }
                Headgear.HELMET -> {
                    mb.sphere(0f, 0.13f * h, -0.01f, 0.143f, 6, 10, secondary, scaleY = 0.9f)
                    mb.box(0f, 0.115f * h, 0.105f, 0.16f, 0.035f, 0.03f, accent, glow + 0.3f)
                }
                Headgear.HEADBAND -> {
                    mb.sphere(0f, 0.145f * h, -0.018f, 0.132f, 5, 9, hair, scaleY = 0.72f)
                    mb.box(0f, 0.135f * h, 0f, 0.245f, 0.035f, 0.245f, accent, glow * 0.4f)
                    mb.box(0f, 0.12f * h, -0.15f, 0.05f, 0.10f, 0.02f, accent)
                }
                Headgear.TOPKNOT -> {
                    mb.sphere(0f, 0.145f * h, -0.018f, 0.132f, 5, 9, hair, scaleY = 0.72f)
                    mb.sphere(0f, 0.245f * h, -0.03f, 0.045f, 5, 7, hair)
                }
                Headgear.CROWN -> {
                    mb.sphere(0f, 0.145f * h, -0.018f, 0.132f, 5, 9, hair, scaleY = 0.72f)
                    mb.cylinder(0f, 0.235f * h, 0f, 0.028f, 0.002f, 0.09f, 5, accent, glow + 0.4f)
                    mb.cylinder(0.06f, 0.215f * h, 0f, 0.022f, 0.002f, 0.06f, 5, accent, glow + 0.2f)
                    mb.cylinder(-0.06f, 0.215f * h, 0f, 0.022f, 0.002f, 0.06f, 5, accent, glow + 0.2f)
                }
                Headgear.VISOR -> {
                    mb.sphere(0f, 0.145f * h, -0.018f, 0.132f, 5, 9, hair, scaleY = 0.72f)
                    mb.box(0f, 0.12f * h, 0.095f, 0.19f, 0.04f, 0.05f, accent, glow + 0.4f)
                }
            }
        }
        // shoulders + upper arms
        for (side in intArrayOf(Joints.SHOULDER_L, Joints.SHOULDER_R)) {
            part(side, 0f, 0f, 0f) { mb ->
                mb.sphere(0f, 0f, 0f, 0.095f * b, 5, 8, primary)
                mb.cylinder(0f, -0.15f * h, 0f, 0.072f * b, 0.065f * b, 0.28f * h, 8, primary)
            }
        }
        // forearms + hands + weapons
        for (side in intArrayOf(Joints.ELBOW_L, Joints.ELBOW_R)) {
            val isRight = side == Joints.ELBOW_R
            part(side, 0f, 0f, 0f) { mb ->
                mb.cylinder(0f, -0.13f * h, 0f, 0.062f * b, 0.055f * b, 0.26f * h, 8, skin)
                if (def.weapon == Weapon.GAUNTLETS) {
                    mb.box(0f, -0.27f * h, 0f, 0.14f * b, 0.15f, 0.14f, accent, glow * 0.6f)
                } else {
                    mb.sphere(0f, -0.27f * h, 0f, 0.072f * b, 5, 7, skin)
                }
                if (isRight && def.weapon == Weapon.SWORD) {
                    mb.box(0f, -0.27f * h, 0.44f, 0.024f, 0.014f, 0.78f, rgb(0xD8DCE8), 0.25f)
                    mb.box(0f, -0.27f * h, 0.05f, 0.10f, 0.05f, 0.028f, accent)
                    mb.box(0f, -0.27f * h, -0.03f, 0.03f, 0.03f, 0.13f, secondary)
                }
                if (isRight && def.weapon == Weapon.STAFF) {
                    val rot = FloatArray(16)
                    val tr = FloatArray(16)
                    val xf = FloatArray(16)
                    M4.rotationX(rot, Math.PI.toFloat() / 2f)
                    M4.translation(tr, 0f, -0.27f * h, 0f)
                    M4.multiply(xf, tr, rot)
                    mb.cylinder(0f, 0f, 0f, 0.022f, 0.022f, 1.5f, 6, secondary, 0f, xf)
                    M4.translation(tr, 0f, -0.27f * h, 0.75f)
                    mb.sphere(0f, 0f, 0f, 0.05f, 5, 7, accent, glow + 0.3f, tr)
                }
            }
        }
        // thighs
        for (side in intArrayOf(Joints.HIP_L, Joints.HIP_R)) {
            part(side, 0f, 0f, 0f) { mb ->
                mb.cylinder(0f, -0.19f * h, 0f, 0.092f * b, 0.08f * b, 0.38f * h, 8, secondary)
            }
        }
        // shins + feet
        for (side in intArrayOf(Joints.KNEE_L, Joints.KNEE_R)) {
            part(side, 0f, 0f, 0f) { mb ->
                mb.cylinder(0f, -0.18f * h, 0f, 0.072f * b, 0.062f * b, 0.36f * h, 8, secondary)
                mb.box(0f, -0.42f * h, 0.05f, 0.12f * b, 0.08f, 0.26f, accent)
            }
        }
    }

    /**
     * Compute all joint world matrices from fighter position + pose.
     * facing: +1 faces +X (world), -1 faces -X.
     */
    fun compute(x: Float, y: Float, facing: Int, pose: FloatArray) {
        val faceAngle = if (facing >= 0) Math.PI.toFloat() / 2f else -Math.PI.toFloat() / 2f
        // root = T(x, hipBase + y + rootY, 0) * Ry(face + yaw) * Rx(pitch) * Rz(roll) * T(0,0,forward)
        M4.translation(mA, x, hipBase + y + pose[36], 0f)
        M4.rotationY(mB, faceAngle + pose[40])
        M4.multiply(mC, mA, mB)
        M4.rotationX(mB, pose[37])
        M4.multiply(mA, mC, mB)
        M4.rotationZ(mB, pose[38])
        M4.multiply(mC, mA, mB)
        M4.translation(mB, 0f, 0f, pose[39])
        M4.multiply(jointWorld[Joints.ROOT], mC, mB)

        for (j in 1 until Joints.COUNT) {
            val parent = jointWorld[Joints.PARENT[j]]
            val off = offsets[j]
            M4.translation(mA, off[0], off[1], off[2])
            M4.multiply(mB, parent, mA)
            // R = Ry * Rx * Rz
            M4.rotationY(mA, pose[j * 3 + 1])
            M4.multiply(mC, mB, mA)
            M4.rotationX(mA, pose[j * 3])
            M4.multiply(mB, mC, mA)
            M4.rotationZ(mA, pose[j * 3 + 2])
            M4.multiply(jointWorld[j], mB, mA)
        }
    }

    fun draw(renderer: Renderer, tint: FloatArray?, emissiveBoost: Float = 1f) {
        for (p in parts) {
            M4.translation(mB, p.ox, p.oy, p.oz)
            M4.multiply(mPart, jointWorld[p.joint], mB)
            renderer.draw(p.mesh, mPart, tint, emissiveBoost)
        }
    }

    fun dispose() {
        for (p in parts) p.mesh.dispose()
    }

    /** World-space position of a joint (for VFX attachment). */
    fun jointPos(j: Int, out: FloatArray) {
        out[0] = jointWorld[j][12]; out[1] = jointWorld[j][13]; out[2] = jointWorld[j][14]
    }
}
