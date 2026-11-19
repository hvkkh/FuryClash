package com.furyclash.game.game

import com.furyclash.game.engine.M4
import com.furyclash.game.engine.Mesh
import com.furyclash.game.engine.MeshBuilder
import com.furyclash.game.engine.ParticleSystem
import com.furyclash.game.engine.Renderer
import com.furyclash.game.engine.Vec3
import com.furyclash.game.engine.rgb
import kotlin.random.Random

class Arena(
    val id: Int,
    val name: String,
    val mesh: Mesh,
    private val fog: FloatArray,
    private val lightDir: Vec3,
    private val lightColor: FloatArray,
    private val ambient: FloatArray,
    private val ground: FloatArray,
    private val fogDensity: Float,
    private val specular: Float
) {
    val halfWidth = 6.5f

    fun apply(r: Renderer) {
        r.fogColor = fog
        r.lightDir = lightDir
        r.lightColor = lightColor
        r.ambient = ambient
        r.ground = ground
        r.fogDensity = fogDensity
        r.specular = specular
    }

    fun ambientParticles(ps: ParticleSystem, dt: Float, time: Float) {
        when (id) {
            0 -> {
                // light rain + occasional neon spark
                if (Random.nextFloat() < dt * 40f) {
                    ps.spawn((Random.nextFloat() - 0.5f) * 24f, 6f + Random.nextFloat() * 2f, -2f + Random.nextFloat() * 5f,
                        -0.4f, -7f, 0f, 0.9f, 0.035f, 0.42f, 0.48f, 0.62f, 0.3f, 0f, 0f)
                }
                if (Random.nextFloat() < dt * 2f) {
                    ps.spawn(-6.4f, 3.4f, -5.9f, 0f, -1f, 0f, 0.3f, 0.08f, 1f, 0.24f, 0.43f, 0.9f, 4f, 0f)
                }
            }
            1 -> {
                // drifting sakura petals
                if (Random.nextFloat() < dt * 14f) {
                    ps.spawn((Random.nextFloat() - 0.5f) * 22f, 3.5f + Random.nextFloat() * 2.5f, -3f + Random.nextFloat() * 5f,
                        -0.5f - Random.nextFloat() * 0.4f, -0.5f, 0.1f, 4f, 0.055f, 0.95f, 0.78f, 0.85f, 0.75f, 0.15f, 0.4f)
                }
            }
            2 -> {
                // dust motes + crowd camera flashes
                if (Random.nextFloat() < dt * 8f) {
                    ps.spawn((Random.nextFloat() - 0.5f) * 14f, 0.5f + Random.nextFloat() * 3f, -1f + Random.nextFloat() * 3f,
                        0.1f, 0.15f, 0f, 2.5f, 0.03f, 0.9f, 0.85f, 0.7f, 0.25f, 0f, 0.2f)
                }
                if (Random.nextFloat() < dt * 3f) {
                    ps.spawn((Random.nextFloat() - 0.5f) * 16f, 1.2f + Random.nextFloat() * 1.5f, -4.5f - Random.nextFloat() * 2f,
                        0f, 0f, 0f, 0.15f, 0.14f, 1f, 1f, 1f, 1f, 0f, 0f)
                }
            }
        }
    }
}

object Arenas {
    val names = listOf("NIGHT CITY STREET", "ANCIENT TEMPLE", "UNDERGROUND ARENA")
    val subtitles = listOf("Neon-soaked asphalt", "Sacred ground at dusk", "No rules. No escape.")

    fun build(id: Int): Arena = when (id) {
        0 -> nightCity()
        1 -> temple()
        else -> underground()
    }

    private fun nightCity(): Arena {
        val b = MeshBuilder()
        val rnd = Random(42)
        val asphalt = rgb(0x1A1D26)
        val strip = rgb(0x232733)
        val curb = rgb(0x2E3340)
        b.plane(0f, 0f, 0f, 46f, 18f, asphalt)
        b.plane(0f, 0.006f, 0f, 15f, 3.8f, strip)
        // lane dashes
        for (x in -20..20 step 3) {
            b.box(x.toFloat(), 0.012f, -2.9f, 0.8f, 0.015f, 0.12f, rgb(0xC9A227), 0.35f)
        }
        b.box(0f, 0.09f, -3.7f, 46f, 0.18f, 0.7f, curb)
        b.plane(0f, 0.18f, -5.4f, 46f, 3f, rgb(0x262B36))
        // buildings with lit windows
        val winColors = listOf(rgb(0xFFD27A), rgb(0x9BC9FF), rgb(0xFF8AB0), rgb(0x7AFFC9))
        for (i in 0 until 11) {
            val x = -20f + i * 4f + rnd.nextFloat() * 1.2f
            val h = 4f + rnd.nextFloat() * 8f
            val w = 3.1f + rnd.nextFloat() * 0.7f
            val shade = 0x10 + rnd.nextInt(8)
            val body = rgb((shade shl 16) or ((shade + 4) shl 8) or (shade + 12))
            b.box(x, h / 2f + 0.18f, -7f, w, h, 3f, body)
            val rows = (h / 1.0f).toInt()
            for (r in 0 until rows) {
                for (c in 0 until 3) {
                    if (rnd.nextFloat() < 0.55f) {
                        val wc = winColors[rnd.nextInt(winColors.size)]
                        b.box(x - w / 3f + c * (w / 3f), 0.9f + r * 1.0f, -5.45f, 0.4f, 0.5f, 0.03f, wc, 0.9f)
                    }
                }
            }
            // neon signage
            if (i % 3 == 0) {
                val neon = listOf(rgb(0xFF3D6E), rgb(0x39FF9C), rgb(0x6BE8FF))[i % 3]
                b.box(x + w / 2f - 0.2f, 2.6f, -5.4f, 0.28f, 2.4f, 0.1f, neon, 1.3f)
                b.box(x, 4.2f, -5.4f, 1.9f, 0.45f, 0.1f, neon, 1.1f)
            }
        }
        // distant skyline
        for (i in 0 until 8) {
            val x = -24f + i * 7f
            b.box(x, 6f + rnd.nextFloat() * 5f, -16f, 5f, 14f + rnd.nextFloat() * 8f, 4f, rgb(0x0C0F18))
        }
        // street lamps
        for (x in floatArrayOf(-9f, -4.5f, 4.5f, 9f)) {
            b.cylinder(x, 1.7f, -3.4f, 0.06f, 0.05f, 3.4f, 6, rgb(0x2A2E38))
            b.box(x, 3.4f, -3.1f, 0.08f, 0.08f, 0.7f, rgb(0x2A2E38))
            b.box(x, 3.32f, -2.8f, 0.3f, 0.1f, 0.18f, rgb(0xFFE0A0), 1.5f)
        }
        // moon
        b.sphere(-13f, 12f, -22f, 1.3f, 8, 12, rgb(0xE8E4D8), 1.2f)
        // foreground props (outside fight lane)
        b.cylinder(7.9f, 0.45f, 2.1f, 0.32f, 0.28f, 0.9f, 8, rgb(0x3A4048))
        b.box(-8.1f, 0.3f, 2.2f, 0.7f, 0.6f, 0.6f, rgb(0x4A3E30))
        b.box(-7.7f, 0.75f, 2.3f, 0.5f, 0.35f, 0.45f, rgb(0x55483A))
        return Arena(0, names[0], b.build(),
            rgb(0x0B0E1A), Vec3(-0.3f, 0.8f, 0.5f).normalized(), rgb(0x8A9BC9),
            rgb(0x3A4560), rgb(0x1E1A26), 0.026f, 0.4f)
    }

    private fun temple(): Arena {
        val b = MeshBuilder()
        val rnd = Random(7)
        val stone = rgb(0x6E6455)
        val stoneTop = rgb(0x7A7060)
        val vermillion = rgb(0x8A2E2E)
        b.plane(0f, -0.5f, 0f, 70f, 50f, rgb(0x33402E))
        b.box(0f, -0.25f, 0f, 18f, 0.5f, 7.5f, stone)
        b.plane(0f, 0.002f, 0f, 18f, 7.5f, stoneTop)
        // tile seams
        for (x in -7..7 step 2) b.box(x.toFloat(), 0.006f, 0f, 0.035f, 0.006f, 7.5f, rgb(0x55503F))
        for (z in -3..3 step 2) b.box(0f, 0.006f, z.toFloat(), 18f, 0.006f, 0.035f, rgb(0x55503F))
        // front steps
        for (s in 0 until 3) {
            b.box(0f, -0.12f - s * 0.16f, 4.1f + s * 0.5f, 6f, 0.16f, 0.5f, stone)
        }
        // pillars
        for (x in floatArrayOf(-8f, -5.5f, 5.5f, 8f)) {
            b.box(x, 0.12f, -2.7f, 0.75f, 0.25f, 0.75f, stone)
            b.cylinder(x, 1.9f, -2.7f, 0.26f, 0.22f, 3.4f, 10, vermillion)
            b.box(x, 3.65f, -2.7f, 0.7f, 0.22f, 0.7f, rgb(0x3A2A22))
        }
        // torii gate
        b.cylinder(-2.2f, 1.8f, -3.4f, 0.2f, 0.17f, 3.6f, 10, rgb(0xA3271F))
        b.cylinder(2.2f, 1.8f, -3.4f, 0.2f, 0.17f, 3.6f, 10, rgb(0xA3271F))
        b.box(0f, 3.62f, -3.4f, 6.2f, 0.28f, 0.4f, rgb(0xA3271F))
        b.box(0f, 3.85f, -3.4f, 6.6f, 0.14f, 0.45f, rgb(0x2A1A16))
        b.box(0f, 2.9f, -3.4f, 5.2f, 0.2f, 0.3f, rgb(0xA3271F))
        // stone lanterns
        for (x in floatArrayOf(-3.9f, 3.9f)) {
            b.box(x, 0.1f, -2.2f, 0.55f, 0.2f, 0.55f, stone)
            b.cylinder(x, 0.55f, -2.2f, 0.12f, 0.1f, 0.7f, 6, stone)
            b.box(x, 1.05f, -2.2f, 0.42f, 0.35f, 0.42f, rgb(0xFFB03A), 0.95f)
            b.cylinder(x, 1.35f, -2.2f, 0.42f, 0.05f, 0.28f, 4, stone)
        }
        // hanging lanterns
        for (x in -4..4 step 2) {
            b.box(x.toFloat(), 2.9f, -2.5f, 0.02f, 0.7f, 0.02f, rgb(0x2A2018))
            b.sphere(x.toFloat(), 2.45f, -2.5f, 0.18f, 6, 8, rgb(0xFF8C3A), 1.1f, scaleY = 1.2f)
        }
        // sakura tree
        b.cylinder(-8.6f, 1.1f, -3f, 0.28f, 0.18f, 2.4f, 7, rgb(0x4A3526))
        b.sphere(-8.6f, 2.7f, -3f, 1.3f, 6, 9, rgb(0xE8A8C0))
        b.sphere(-7.7f, 2.3f, -2.7f, 0.9f, 6, 8, rgb(0xF2C9D8))
        b.sphere(-9.4f, 2.4f, -3.3f, 0.95f, 6, 8, rgb(0xE8A8C0))
        // mountains
        b.sphere(-14f, -1f, -20f, 11f, 6, 10, rgb(0x2A3040), scaleY = 0.55f)
        b.sphere(6f, -1.5f, -24f, 14f, 6, 10, rgb(0x232A3A), scaleY = 0.5f)
        b.sphere(18f, -1f, -18f, 9f, 6, 9, rgb(0x2A3040), scaleY = 0.6f)
        // sun glow
        b.sphere(10f, 7f, -26f, 2.2f, 8, 12, rgb(0xFFC98A), 1.4f)
        return Arena(1, names[1], b.build(),
            rgb(0x4A3040), Vec3(-0.5f, 0.6f, 0.4f).normalized(), rgb(0xFFC98A),
            rgb(0x7A5560), rgb(0x3A2E2E), 0.018f, 0.25f)
    }

    private fun underground(): Arena {
        val b = MeshBuilder()
        val rnd = Random(99)
        val concrete = rgb(0x3A3E44)
        val mat = rgb(0x4A4038)
        val steel = rgb(0x555C66)
        b.plane(0f, 0f, 0f, 32f, 16f, concrete)
        b.plane(0f, 0.006f, 0f, 13.5f, 4.6f, mat)
        // mat border
        b.box(0f, 0.012f, -2.3f, 13.5f, 0.015f, 0.06f, rgb(0xFF3D3D), 0.6f)
        b.box(0f, 0.012f, 2.3f, 13.5f, 0.015f, 0.06f, rgb(0xFF3D3D), 0.6f)
        b.box(-6.75f, 0.012f, 0f, 0.06f, 0.015f, 4.6f, rgb(0xFF3D3D), 0.6f)
        b.box(6.75f, 0.012f, 0f, 0.06f, 0.015f, 4.6f, rgb(0xFF3D3D), 0.6f)
        // cage: back + sides full height, front low so camera sees through
        for (x in floatArrayOf(-7.2f, -3.6f, 0f, 3.6f, 7.2f)) {
            b.cylinder(x, 1.3f, -3f, 0.05f, 0.05f, 2.6f, 6, steel)
        }
        for (x in floatArrayOf(-7.2f, 7.2f)) {
            for (z in floatArrayOf(-1.5f, 0f, 1.5f, 3f)) b.cylinder(x, 1.3f, z, 0.05f, 0.05f, 2.6f, 6, steel)
        }
        for (y in floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f)) {
            b.box(0f, y, -3f, 14.4f, 0.025f, 0.025f, steel)
            b.box(-7.2f, y, 0f, 0.025f, 0.025f, 6f, steel)
            b.box(7.2f, y, 0f, 0.025f, 0.025f, 6f, steel)
        }
        for (y in floatArrayOf(0.3f, 0.6f, 0.9f)) {
            b.box(0f, y, 3f, 14.4f, 0.025f, 0.025f, steel)
        }
        for (x in floatArrayOf(-7.2f, -3.6f, 0f, 3.6f, 7.2f)) b.cylinder(x, 0.45f, 3f, 0.05f, 0.05f, 0.9f, 6, steel)
        // back wall + pipes
        b.box(0f, 3f, -7.6f, 32f, 6f, 0.4f, rgb(0x2A2E34))
        val pipeRot = FloatArray(16)
        M4.rotationZ(pipeRot, Math.PI.toFloat() / 2f)
        b.cylinder(0f, 4.6f, -7.3f, 0.12f, 0.12f, 30f, 6, rgb(0x4A4038), 0f, pipeRot)
        b.cylinder(0f, 5.1f, -7.3f, 0.08f, 0.08f, 30f, 6, rgb(0x3A4A44), 0f, pipeRot)
        // banners
        b.box(-5f, 3f, -7.35f, 1.3f, 2.2f, 0.05f, rgb(0xA3271F), 0.35f)
        b.box(5f, 3f, -7.35f, 1.3f, 2.2f, 0.05f, rgb(0x1F4FA3), 0.35f)
        // bleachers + crowd
        val crowdColors = listOf(rgb(0x2A2A32), rgb(0x332A2A), rgb(0x2A332E), rgb(0x302A38), rgb(0x3A3428))
        for (row in 0 until 3) {
            val z = -4.4f - row * 1.1f
            val y = 0.35f + row * 0.55f
            b.box(0f, y - 0.2f, z, 24f, 0.4f, 1f, rgb(0x23262C))
            for (i in 0 until 22) {
                val x = -11f + i * 1.05f + rnd.nextFloat() * 0.3f
                val c = crowdColors[rnd.nextInt(crowdColors.size)]
                b.box(x, y + 0.35f, z, 0.36f, 0.7f, 0.3f, c)
                b.sphere(x, y + 0.82f, z, 0.13f, 4, 6, rgb(0x8A7060))
                if (rnd.nextFloat() < 0.12f) {
                    b.box(x + 0.15f, y + 0.75f, z + 0.16f, 0.06f, 0.1f, 0.02f, rgb(0x9BC9FF), 1.4f)
                }
            }
        }
        // hanging spotlights + faint cones
        for (x in floatArrayOf(-4f, 0f, 4f)) {
            b.box(x, 5.4f, -0.5f, 0.04f, 1.2f, 0.04f, rgb(0x1A1A1E))
            b.cylinder(x, 4.7f, -0.5f, 0.06f, 0.34f, 0.4f, 8, rgb(0x2A2E34))
            b.cylinder(x, 4.48f, -0.5f, 0.3f, 0.3f, 0.04f, 8, rgb(0xFFE8C9), 1.6f)
            // light pool on the floor instead of a solid volumetric cone
            b.cylinder(x, 0.015f, -0.5f, 1.5f, 1.5f, 0.01f, 16, rgb(0x3A362C), 0.35f)
        }
        return Arena(2, names[2], b.build(),
            rgb(0x121014), Vec3(0f, 0.9f, 0.35f).normalized(), rgb(0xFFE0B0),
            rgb(0x4A4038), rgb(0x221E1A), 0.03f, 0.2f)
    }
}
