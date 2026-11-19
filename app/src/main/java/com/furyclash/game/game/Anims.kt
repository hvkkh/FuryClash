package com.furyclash.game.game

import com.furyclash.game.engine.lerp
import com.furyclash.game.engine.smoothstep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Procedural animation library. Every pose is generated from time — no asset
 * files. Conventions: model faces +Z; negative shoulder/hip rx swings a limb
 * forward, positive knee rx bends the shin backward, positive rootPitch leans
 * the torso forward.
 */
object Anims {
    private const val PI = Math.PI.toFloat()

    private fun j(p: FloatArray, joint: Int, rx: Float, ry: Float = 0f, rz: Float = 0f) {
        p[joint * 3] = rx; p[joint * 3 + 1] = ry; p[joint * 3 + 2] = rz
    }

    /** Zero pose + relaxed fighting guard. */
    private fun guard(p: FloatArray) {
        for (i in p.indices) p[i] = 0f
        j(p, Joints.SHOULDER_L, -0.95f, 0f, 0.30f)
        j(p, Joints.ELBOW_L, -1.55f)
        j(p, Joints.SHOULDER_R, -0.75f, 0f, -0.24f)
        j(p, Joints.ELBOW_R, -1.70f)
        j(p, Joints.SPINE, 0.03f, 0.14f)
        j(p, Joints.CHEST, 0f, 0.10f)
        j(p, Joints.HEAD, -0.04f, -0.12f)
        j(p, Joints.HIP_L, -0.14f, 0f, 0.04f)
        j(p, Joints.KNEE_L, 0.26f)
        j(p, Joints.HIP_R, 0.18f, 0f, -0.04f)
        j(p, Joints.KNEE_R, 0.16f)
        p[37] = 0.04f
    }

    /**
     * @param name anim id
     * @param t time in seconds (loops) or normalized 0..1 phase for one-shots
     * @param p1 variant (arm index / intensity)
     * @param p2 auxiliary (vertical velocity for air pose)
     */
    fun compute(name: String, t: Float, p: FloatArray, p1: Float = 0f, p2: Float = 0f) {
        guard(p)
        when (name) {
            "idle" -> {
                val br = sin(t * 2.2f)
                p[Joints.SPINE * 3] += 0.02f * br
                p[36] = 0.012f * br
                p[Joints.SHOULDER_L * 3] += 0.03f * br
                p[Joints.SHOULDER_R * 3] += 0.03f * br
            }
            "walk", "run" -> {
                val run = name == "run"
                val amp = if (run) 0.95f else 0.55f
                val freq = if (run) 12f else 9f
                val ph = t * freq
                val s = sin(ph)
                j(p, Joints.HIP_L, s * amp - 0.05f, 0f, 0.03f)
                j(p, Joints.HIP_R, -s * amp - 0.05f, 0f, -0.03f)
                j(p, Joints.KNEE_L, max(0f, -s) * amp * 1.1f + 0.10f)
                j(p, Joints.KNEE_R, max(0f, s) * amp * 1.1f + 0.10f)
                p[Joints.SHOULDER_L * 3] = -0.9f - s * amp * 0.45f
                p[Joints.SHOULDER_R * 3] = -0.75f + s * amp * 0.45f
                p[Joints.ELBOW_L * 3] = if (run) -1.35f else -1.45f
                p[Joints.ELBOW_R * 3] = if (run) -1.35f else -1.55f
                p[36] = abs(sin(ph)) * (if (run) 0.045f else 0.025f) - 0.02f
                p[37] = if (run) 0.24f else 0.08f
                p[Joints.SPINE * 3 + 1] = 0.05f
            }
            "air" -> {
                if (p2 > 0f) {
                    j(p, Joints.HIP_L, -0.85f, 0f, 0.1f)
                    j(p, Joints.HIP_R, -0.65f, 0f, -0.1f)
                    j(p, Joints.KNEE_L, 1.35f)
                    j(p, Joints.KNEE_R, 1.15f)
                    j(p, Joints.SHOULDER_L, -0.6f, 0f, 0.55f)
                    j(p, Joints.SHOULDER_R, -0.6f, 0f, -0.55f)
                    p[37] = 0.10f
                } else {
                    j(p, Joints.HIP_L, -0.35f, 0f, 0.08f)
                    j(p, Joints.HIP_R, -0.15f, 0f, -0.08f)
                    j(p, Joints.KNEE_L, 0.55f)
                    j(p, Joints.KNEE_R, 0.35f)
                    j(p, Joints.SHOULDER_L, -0.9f, 0f, 0.8f)
                    j(p, Joints.SHOULDER_R, -0.9f, 0f, -0.8f)
                    p[37] = -0.05f
                }
            }
            "crouch" -> {
                p[36] = -0.33f
                j(p, Joints.HIP_L, -1.5f, 0f, 0.12f)
                j(p, Joints.HIP_R, -1.4f, 0f, -0.12f)
                j(p, Joints.KNEE_L, 2.0f)
                j(p, Joints.KNEE_R, 1.9f)
                p[Joints.SPINE * 3] = 0.35f
                p[37] = 0.12f
                j(p, Joints.SHOULDER_L, -1.0f, 0f, 0.35f)
                j(p, Joints.SHOULDER_R, -0.85f, 0f, -0.3f)
            }
            "block" -> {
                val crouch = p1 > 0.5f
                j(p, Joints.SHOULDER_L, -1.15f, 0f, 0.55f)
                j(p, Joints.SHOULDER_R, -1.05f, 0f, -0.60f)
                j(p, Joints.ELBOW_L, -1.95f)
                j(p, Joints.ELBOW_R, -2.05f)
                p[Joints.SPINE * 3] = 0.10f
                p[36] = if (crouch) -0.33f else -0.05f
                if (crouch) {
                    j(p, Joints.HIP_L, -1.5f, 0f, 0.12f)
                    j(p, Joints.HIP_R, -1.4f, 0f, -0.12f)
                    j(p, Joints.KNEE_L, 2.0f)
                    j(p, Joints.KNEE_R, 1.9f)
                } else {
                    j(p, Joints.HIP_L, -0.2f)
                    j(p, Joints.HIP_R, 0.2f)
                    j(p, Joints.KNEE_L, 0.3f)
                    j(p, Joints.KNEE_R, 0.25f)
                }
            }
            "punch", "airpunch", "crouchpunch" -> {
                val left = p1 > 0.5f
                val sh = if (left) Joints.SHOULDER_L else Joints.SHOULDER_R
                val el = if (left) Joints.ELBOW_L else Joints.ELBOW_R
                val w = smoothstep(t / 0.25f)
                val e = smoothstep((t - 0.25f) / 0.2f)
                val r = smoothstep((t - 0.6f) / 0.4f)
                val ext = e * (1f - r)
                p[sh * 3] = -0.8f + w * 0.45f - ext * 1.25f
                p[sh * 3 + 2] = (if (left) 0.25f else -0.25f) * (1f - ext)
                p[el * 3] = -1.6f + ext * 1.6f
                p[Joints.SPINE * 3 + 1] = 0.14f + (if (left) 0.35f else -0.35f) * ext
                p[39] = 0.14f * ext
                if (name == "crouchpunch") {
                    p[36] = -0.33f
                    j(p, Joints.HIP_L, -1.5f, 0f, 0.12f)
                    j(p, Joints.HIP_R, -1.4f, 0f, -0.12f)
                    j(p, Joints.KNEE_L, 2.0f)
                    j(p, Joints.KNEE_R, 1.9f)
                    p[sh * 3] += 0.25f
                }
                if (name == "airpunch") {
                    j(p, Joints.HIP_L, -0.8f); j(p, Joints.HIP_R, -0.6f)
                    j(p, Joints.KNEE_L, 1.3f); j(p, Joints.KNEE_R, 1.1f)
                }
            }
            "kick", "airkick" -> {
                val w = smoothstep(t / 0.3f)
                val e = smoothstep((t - 0.3f) / 0.2f)
                val r = smoothstep((t - 0.65f) / 0.35f)
                val ext = e * (1f - r)
                j(p, Joints.HIP_R, -0.15f - w * 0.5f - ext * 0.95f, 0f, -0.06f)
                j(p, Joints.KNEE_R, 0.2f + w * 1.1f - ext * 1.2f)
                j(p, Joints.HIP_L, -0.1f)
                j(p, Joints.KNEE_L, 0.3f)
                p[37] = -0.14f * ext
                p[36] = -0.03f * ext
                j(p, Joints.SHOULDER_L, -0.7f, 0f, 0.7f * ext + 0.3f)
                j(p, Joints.SHOULDER_R, -0.5f, 0f, -0.6f * ext - 0.2f)
                p[Joints.SPINE * 3 + 1] = 0.25f * ext
                if (name == "airkick") {
                    j(p, Joints.HIP_L, -0.7f)
                    j(p, Joints.KNEE_L, 1.2f)
                    p[37] = -0.25f * ext
                }
            }
            "sweep" -> {
                val e = smoothstep((t - 0.25f) / 0.25f) * (1f - smoothstep((t - 0.7f) / 0.3f))
                p[36] = -0.36f
                j(p, Joints.HIP_L, -1.6f, 0f, 0.15f)
                j(p, Joints.KNEE_L, 2.1f)
                j(p, Joints.HIP_R, -0.3f - e * 1.15f, 0f, -0.1f)
                j(p, Joints.KNEE_R, 0.4f - e * 0.3f)
                p[37] = 0.25f * e
                j(p, Joints.SHOULDER_L, -0.6f, 0f, 0.6f)
                j(p, Joints.SHOULDER_R, -0.3f, 0f, -0.7f)
            }
            "heavy" -> {
                val w = smoothstep(t / 0.45f)
                val e = smoothstep((t - 0.45f) / 0.2f)
                val r = smoothstep((t - 0.75f) / 0.25f)
                val slam = e * (1f - r)
                p[Joints.SHOULDER_R * 3] = -0.8f - w * 1.9f + slam * 1.8f
                p[Joints.ELBOW_R * 3] = -1.6f - w * 0.5f + slam * 2.0f
                p[Joints.SHOULDER_L * 3] = -0.9f + slam * 0.3f
                p[37] = -0.12f * w + 0.34f * slam
                p[39] = 0.22f * slam
                p[36] = -0.09f * slam
                j(p, Joints.KNEE_L, 0.26f + 0.4f * slam)
                j(p, Joints.KNEE_R, 0.16f + 0.45f * slam)
                p[Joints.SPINE * 3] = 0.03f - 0.15f * w + 0.3f * slam
            }
            "hit" -> {
                val k = 1f - smoothstep(t)
                p[37] = -0.28f * k
                p[Joints.SPINE * 3] = -0.18f * k
                p[Joints.HEAD * 3] = -0.3f * k
                p[Joints.SHOULDER_L * 3] = -0.5f
                p[Joints.SHOULDER_R * 3] = -0.4f
                p[Joints.SHOULDER_L * 3 + 2] = 0.5f * k + 0.2f
                p[Joints.SHOULDER_R * 3 + 2] = -0.5f * k - 0.2f
                p[36] = -0.03f * k
                p[39] = -0.06f * k
            }
            "launched" -> {
                p[37] = -0.5f - t * 0.8f
                j(p, Joints.SHOULDER_L, -0.6f, 0f, 1.0f)
                j(p, Joints.SHOULDER_R, -0.6f, 0f, -1.0f)
                j(p, Joints.HIP_L, -0.7f, 0f, 0.15f)
                j(p, Joints.HIP_R, -0.5f, 0f, -0.15f)
                j(p, Joints.KNEE_L, 0.8f)
                j(p, Joints.KNEE_R, 0.6f)
                p[Joints.HEAD * 3] = -0.3f
            }
            "knockdown", "defeat" -> {
                val fall = if (name == "knockdown") smoothstep(t / 0.45f) else 1f
                p[36] = lerp(-0.1f, -0.74f, fall)
                p[37] = lerp(-0.3f, -1.52f, fall)
                j(p, Joints.SHOULDER_L, -0.3f, 0f, 0.9f * fall)
                j(p, Joints.SHOULDER_R, -0.3f, 0f, -0.9f * fall)
                j(p, Joints.ELBOW_L, -0.3f)
                j(p, Joints.ELBOW_R, -0.3f)
                j(p, Joints.HIP_L, -0.25f * fall, 0f, 0.1f)
                j(p, Joints.HIP_R, -0.15f * fall, 0f, -0.1f)
                j(p, Joints.KNEE_L, 0.4f * fall)
                j(p, Joints.KNEE_R, 0.25f * fall)
                p[Joints.HEAD * 3 + 2] = 0.3f * fall
                if (name == "defeat") p[36] += 0.006f * sin(t * 1.8f)
            }
            "getup" -> {
                val k = smoothstep(t)
                p[36] = lerp(-0.74f, 0f, k)
                p[37] = lerp(-1.52f, 0.04f, k)
                j(p, Joints.HIP_L, lerp(-0.25f, -0.14f, k))
                j(p, Joints.HIP_R, lerp(-0.15f, 0.18f, k))
                j(p, Joints.KNEE_L, lerp(0.4f, 0.26f, k))
                j(p, Joints.KNEE_R, lerp(0.25f, 0.16f, k))
            }
            "victory" -> {
                val pump = sin(t * 6f)
                p[Joints.SHOULDER_R * 3] = -2.85f + pump * 0.12f
                p[Joints.ELBOW_R * 3] = -0.35f + pump * 0.2f
                p[Joints.SHOULDER_L * 3] = -0.35f
                p[Joints.SHOULDER_L * 3 + 2] = 0.35f
                p[Joints.ELBOW_L * 3] = -0.9f
                p[Joints.SPINE * 3] = -0.08f
                p[Joints.HEAD * 3] = -0.14f
                p[36] = 0.02f * sin(t * 3f)
                p[37] = -0.05f
            }
            "stagger" -> {
                val k = 1f - smoothstep(t)
                p[38] = sin(t * 18f) * 0.13f * k
                p[37] = -0.15f * k
                p[Joints.SHOULDER_L * 3] = -0.25f
                p[Joints.SHOULDER_R * 3] = -0.2f
                p[Joints.SHOULDER_L * 3 + 2] = 0.4f
                p[Joints.SHOULDER_R * 3 + 2] = -0.4f
                p[Joints.HEAD * 3 + 2] = 0.25f * k
                p[Joints.KNEE_L * 3] = 0.5f
                p[Joints.KNEE_R * 3] = 0.4f
            }
            "switchin" -> {
                val k = smoothstep(t)
                p[37] = 0.5f * (1f - k)
                p[Joints.SHOULDER_L * 3] = lerp(0.4f, -0.95f, k)
                p[Joints.SHOULDER_R * 3] = lerp(0.4f, -0.75f, k)
                j(p, Joints.HIP_L, lerp(-0.9f, -0.14f, k))
                j(p, Joints.HIP_R, lerp(0.6f, 0.18f, k))
                j(p, Joints.KNEE_L, lerp(0.9f, 0.26f, k))
                j(p, Joints.KNEE_R, lerp(0.5f, 0.16f, k))
            }
            "intro" -> {
                p[36] = 0.02f * sin(t * 3f)
                p[Joints.SHOULDER_R * 3] = -1.1f + 0.05f * sin(t * 3f)
            }
            "cast" -> castPose(p, t, "cast")
            "beam" -> castPose(p, t, "beam")
            "slam" -> castPose(p, t, "slam")
            "uppercut" -> castPose(p, t, "uppercut")
            "spin" -> castPose(p, t, "spin")
            "dash" -> castPose(p, t, "dash")
            "roar" -> castPose(p, t, "roar")
        }
    }

    private fun castPose(p: FloatArray, t: Float, style: String) {
        val w = smoothstep(t / 0.35f)
        val e = smoothstep((t - 0.35f) / 0.25f)
        val r = smoothstep((t - 0.8f) / 0.2f)
        val ext = e * (1f - r)
        when (style) {
            "cast" -> {
                // gather energy at chest, then palm thrust
                p[Joints.SHOULDER_L * 3] = -0.7f - w * 0.2f + ext * 0.1f
                p[Joints.SHOULDER_R * 3] = -0.7f - w * 0.2f - ext * 0.85f
                p[Joints.ELBOW_L * 3] = -1.8f + ext * 0.3f
                p[Joints.ELBOW_R * 3] = -1.8f + ext * 1.8f
                p[Joints.SHOULDER_L * 3 + 2] = 0.5f - ext * 0.2f
                p[Joints.SHOULDER_R * 3 + 2] = -0.5f + ext * 0.35f
                p[Joints.SPINE * 3 + 1] = 0.14f - 0.4f * ext
                p[39] = 0.1f * ext
            }
            "beam" -> {
                p[Joints.SHOULDER_L * 3] = -0.6f - ext * 0.97f
                p[Joints.SHOULDER_R * 3] = -0.6f - ext * 0.97f
                p[Joints.ELBOW_L * 3] = -1.7f + ext * 1.7f
                p[Joints.ELBOW_R * 3] = -1.7f + ext * 1.7f
                p[Joints.SHOULDER_L * 3 + 2] = 0.4f - ext * 0.25f
                p[Joints.SHOULDER_R * 3 + 2] = -0.4f + ext * 0.25f
                p[37] = 0.1f * ext
                p[39] = 0.08f * ext
                p[36] = -0.04f * ext
            }
            "slam" -> {
                p[Joints.SHOULDER_L * 3] = -0.8f - w * 2.0f + ext * 2.3f
                p[Joints.SHOULDER_R * 3] = -0.8f - w * 2.0f + ext * 2.3f
                p[Joints.ELBOW_L * 3] = -1.2f + ext * 1.0f
                p[Joints.ELBOW_R * 3] = -1.2f + ext * 1.0f
                p[36] = -0.18f * ext
                p[37] = -0.1f * w + 0.38f * ext
                j(p, Joints.KNEE_L, 0.26f + 0.5f * ext)
                j(p, Joints.KNEE_R, 0.16f + 0.5f * ext)
                j(p, Joints.HIP_L, -0.14f - 0.4f * ext)
                j(p, Joints.HIP_R, 0.18f - 0.4f * ext)
            }
            "uppercut" -> {
                p[Joints.SHOULDER_R * 3] = -0.4f + w * 0.2f - ext * 2.4f
                p[Joints.ELBOW_R * 3] = -1.4f + ext * 1.1f
                p[Joints.SHOULDER_L * 3] = -0.9f
                p[36] = 0.14f * ext
                p[37] = -0.16f * ext
                p[Joints.SPINE * 3] = -0.15f * ext
                j(p, Joints.KNEE_L, 0.26f + 0.3f * ext)
                j(p, Joints.KNEE_R, 0.16f + 0.3f * ext)
            }
            "spin" -> {
                p[40] = t * 4f * PI
                j(p, Joints.HIP_R, -1.4f, 0f, -0.15f)
                j(p, Joints.KNEE_R, 0.15f)
                j(p, Joints.HIP_L, -0.2f)
                j(p, Joints.KNEE_L, 0.4f)
                j(p, Joints.SHOULDER_L, -0.4f, 0f, 1.2f)
                j(p, Joints.SHOULDER_R, -0.4f, 0f, -1.2f)
                p[36] = 0.06f * sin(t * 2f * PI)
                p[37] = -0.1f
            }
            "dash" -> {
                p[37] = 0.5f
                p[Joints.SHOULDER_L * 3] = 0.45f
                p[Joints.SHOULDER_R * 3] = 0.45f
                p[Joints.SHOULDER_L * 3 + 2] = 0.35f
                p[Joints.SHOULDER_R * 3 + 2] = -0.35f
                p[Joints.ELBOW_L * 3] = -0.4f
                p[Joints.ELBOW_R * 3] = -0.4f
                j(p, Joints.HIP_L, -0.95f)
                j(p, Joints.HIP_R, 0.55f)
                j(p, Joints.KNEE_L, 0.7f)
                j(p, Joints.KNEE_R, 0.9f)
                p[36] = -0.06f
            }
            "roar" -> {
                p[Joints.SHOULDER_L * 3] = 0.25f
                p[Joints.SHOULDER_R * 3] = 0.25f
                p[Joints.SHOULDER_L * 3 + 2] = 0.95f
                p[Joints.SHOULDER_R * 3 + 2] = -0.95f
                p[Joints.ELBOW_L * 3] = -0.5f
                p[Joints.ELBOW_R * 3] = -0.5f
                p[Joints.SPINE * 3] = -0.25f
                p[Joints.HEAD * 3] = -0.35f
                p[37] = -0.12f
                p[38] = sin(t * 30f) * 0.02f
                p[36] = 0.02f
            }
        }
    }
}
