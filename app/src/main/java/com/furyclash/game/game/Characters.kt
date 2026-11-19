package com.furyclash.game.game

enum class SkillType { PROJECTILE, BEAM, TELEPORT_STRIKE, AOE_SELF, AOE_TARGET, DASH_ATTACK, BUFF, SPIN, SKY_STRIKE, MULTI_RUSH }
enum class BuffKind { SPEED, DAMAGE, SHIELD, COUNTER_STANCE, ARMOR }
enum class ProjEffect { NONE, SLOW, FREEZE, BURN }
enum class Style { RUSHDOWN, ZONER, BALANCED, GRAPPLER, TRICKY }
enum class Headgear { NONE, HOOD, MASK, HELMET, HEADBAND, TOPKNOT, CROWN, VISOR }
enum class Weapon { NONE, SWORD, STAFF, GAUNTLETS }

data class SkillDef(
    val name: String,
    val type: SkillType,
    val cost: Float,
    val cooldown: Float,
    val damage: Float,
    val hits: Int = 1,
    val range: Float = 7f,
    val speed: Float = 9f,
    val radius: Float = 0.45f,
    val effect: ProjEffect = ProjEffect.NONE,
    val effectDuration: Float = 0f,
    val buff: BuffKind? = null,
    val buffDuration: Float = 0f,
    val buffPower: Float = 0f,
    val color: Int,
    val cinematic: Boolean = false,
    val knockdown: Boolean = false,
    val launcher: Boolean = false,
    val anim: String = "cast",
    val desc: String
)

data class CharacterDef(
    val id: Int,
    val name: String,
    val title: String,
    val style: Style,
    val hp: Float,
    val walkSpeed: Float,
    val runSpeed: Float,
    val jumpVel: Float,
    val power: Float,
    val weight: Float,
    val skin: Int,
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val hair: Int,
    val bulk: Float,
    val height: Float,
    val headgear: Headgear,
    val weapon: Weapon,
    val glow: Boolean,
    val skills: List<SkillDef>,
    val desc: String
)

object Characters {
    val all: List<CharacterDef> = listOf(
        CharacterDef(
            0, "Shadow Ninja", "Silent teleport assassin", Style.TRICKY,
            950f, 3.0f, 6.0f, 6.8f, 0.95f, 0.85f,
            0xFFC9A184.toInt(), 0xFF2B2F45.toInt(), 0xFF1A1D2E.toInt(), 0xFF8A5CFF.toInt(), 0xFF14161F.toInt(),
            0.9f, 1.0f, Headgear.MASK, Weapon.NONE, true,
            listOf(
                SkillDef("Shadow Step", SkillType.TELEPORT_STRIKE, 25f, 3f, 105f, range = 3.5f, color = 0xFF8A5CFF.toInt(), anim = "dash", desc = "Vanish and reappear behind the foe with a slashing strike."),
                SkillDef("Umbral Shuriken", SkillType.PROJECTILE, 25f, 2.5f, 90f, speed = 11f, radius = 0.32f, color = 0xFF6B3FD4.toInt(), desc = "Hurl a spinning blade of condensed shadow."),
                SkillDef("Nightfall Barrage", SkillType.MULTI_RUSH, 100f, 8f, 62f, hits = 5, range = 3f, color = 0xFF8A5CFF.toInt(), cinematic = true, anim = "dash", desc = "Five teleporting slashes from every direction, ending in a shadow burst.")
            ),
            "Strikes from impossible angles. Fragile, but nearly impossible to pin down."
        ),
        CharacterDef(
            1, "Dragon Warrior", "Heir of the ember fist", Style.BALANCED,
            1050f, 2.7f, 5.2f, 6.4f, 1.05f, 1.0f,
            0xFFD8A878.toInt(), 0xFFA3271F.toInt(), 0xFF5C1512.toInt(), 0xFFFFB03A.toInt(), 0xFF2A1A12.toInt(),
            1.05f, 1.04f, Headgear.HEADBAND, Weapon.NONE, true,
            listOf(
                SkillDef("Ember Fang", SkillType.PROJECTILE, 25f, 2.5f, 100f, speed = 8.5f, radius = 0.42f, effect = ProjEffect.BURN, effectDuration = 2.5f, color = 0xFFFF7A2E.toInt(), desc = "A roaring fireball that leaves the foe burning."),
                SkillDef("Rising Drake", SkillType.AOE_SELF, 30f, 4.5f, 125f, radius = 1.3f, launcher = true, color = 0xFFFFB03A.toInt(), anim = "uppercut", desc = "A flaming rising uppercut that launches nearby enemies skyward."),
                SkillDef("Dragon Inferno", SkillType.BEAM, 100f, 8f, 300f, range = 9f, radius = 0.6f, effect = ProjEffect.BURN, effectDuration = 3f, color = 0xFFFF5A2E.toInt(), cinematic = true, anim = "beam", desc = "Channel a dragon's breath: a searing beam across the whole arena.")
            ),
            "Balanced pressure with fire. His flame lingers, punishing careless approaches."
        ),
        CharacterDef(
            2, "Iron Fist", "The living wrecking ball", Style.GRAPPLER,
            1200f, 2.2f, 4.4f, 5.8f, 1.25f, 1.35f,
            0xFFB98A68.toInt(), 0xFF6E7681.toInt(), 0xFF3C434C.toInt(), 0xFFFFC94D.toInt(), 0xFF3A3F45.toInt(),
            1.3f, 1.08f, Headgear.NONE, Weapon.GAUNTLETS, false,
            listOf(
                SkillDef("Piston Jab", SkillType.DASH_ATTACK, 25f, 3f, 115f, range = 3.2f, color = 0xFFFFC94D.toInt(), anim = "dash", desc = "Rocket forward behind a steel-plated straight punch."),
                SkillDef("Quake Slam", SkillType.AOE_SELF, 35f, 5f, 150f, radius = 2.0f, knockdown = true, color = 0xFFC9A227.toInt(), anim = "slam", desc = "Slam the ground, toppling everything nearby."),
                SkillDef("Titan Breaker", SkillType.MULTI_RUSH, 100f, 8f, 330f, hits = 1, range = 2f, color = 0xFFFFC94D.toInt(), cinematic = true, knockdown = true, anim = "slam", desc = "One world-ending haymaker. The arena cracks beneath the impact.")
            ),
            "Slow but devastating. Every clean hit feels like a car crash."
        ),
        CharacterDef(
            3, "Thunder Strike", "Storm given human form", Style.ZONER,
            950f, 2.8f, 5.4f, 6.6f, 1.0f, 0.9f,
            0xFFE0B48E.toInt(), 0xFF1F4FA3.toInt(), 0xFF14294F.toInt(), 0xFF6BE8FF.toInt(), 0xFFF2D24B.toInt(),
            0.95f, 1.02f, Headgear.VISOR, Weapon.NONE, true,
            listOf(
                SkillDef("Bolt Javelin", SkillType.PROJECTILE, 25f, 2.2f, 95f, speed = 14f, radius = 0.3f, color = 0xFF6BE8FF.toInt(), desc = "A lightning-fast spear of electricity."),
                SkillDef("Static Field", SkillType.AOE_SELF, 30f, 5f, 120f, radius = 1.8f, effect = ProjEffect.SLOW, effectDuration = 2f, color = 0xFF3D9BFF.toInt(), anim = "slam", desc = "Electrify the air around you, slowing and shocking foes."),
                SkillDef("Storm Call", SkillType.SKY_STRIKE, 100f, 8f, 310f, hits = 3, radius = 1.5f, color = 0xFF9BE8FF.toInt(), cinematic = true, anim = "cast", desc = "Call three thunderbolts down onto the opponent's position.")
            ),
            "Controls space with blinding speed. Keep him at bay or pay the toll."
        ),
        CharacterDef(
            4, "Flame Fighter", "Burn-rush combo specialist", Style.RUSHDOWN,
            1000f, 3.0f, 5.8f, 6.5f, 1.0f, 0.95f,
            0xFFC98F6B.toInt(), 0xFFE85D2A.toInt(), 0xFF7A2410.toInt(), 0xFFFFD24D.toInt(), 0xFFB33A1A.toInt(),
            1.0f, 0.98f, Headgear.NONE, Weapon.NONE, true,
            listOf(
                SkillDef("Cinder Rush", SkillType.DASH_ATTACK, 25f, 3f, 45f, hits = 3, range = 3f, color = 0xFFFF7A2E.toInt(), anim = "dash", desc = "A blazing dash that lands a three-hit burning combo."),
                SkillDef("Flame Wheel", SkillType.SPIN, 30f, 4.5f, 50f, hits = 3, radius = 1.3f, effect = ProjEffect.BURN, effectDuration = 2f, color = 0xFFFFB03A.toInt(), anim = "spin", desc = "A cartwheel of fire that scorches everything around you."),
                SkillDef("Phoenix Burst", SkillType.AOE_SELF, 100f, 8f, 300f, radius = 2.6f, knockdown = true, color = 0xFFFFD24D.toInt(), cinematic = true, anim = "slam", desc = "Erupt in a phoenix-shaped nova of flame.")
            ),
            "Relentless close-range pressure. Once he starts burning, he never stops."
        ),
        CharacterDef(
            5, "Ice Queen", "Sovereign of the frozen court", Style.ZONER,
            900f, 2.6f, 5.0f, 6.3f, 0.95f, 0.85f,
            0xFFE8C9B0.toInt(), 0xFF7FC4E8.toInt(), 0xFF2E5F8A.toInt(), 0xFFCFF6FF.toInt(), 0xFFE6F4FA.toInt(),
            0.88f, 1.0f, Headgear.CROWN, Weapon.STAFF, true,
            listOf(
                SkillDef("Frost Shard", SkillType.PROJECTILE, 25f, 2.4f, 85f, speed = 9.5f, radius = 0.35f, effect = ProjEffect.SLOW, effectDuration = 2.5f, color = 0xFF9BE8FF.toInt(), desc = "A razor shard of ice that chills the foe's movement."),
                SkillDef("Glacial Prison", SkillType.PROJECTILE, 40f, 6f, 70f, speed = 7.5f, radius = 0.5f, effect = ProjEffect.FREEZE, effectDuration = 1.3f, color = 0xFFCFF6FF.toInt(), desc = "Encase the opponent in ice, leaving them helpless."),
                SkillDef("Absolute Zero", SkillType.AOE_TARGET, 100f, 8f, 290f, radius = 2.4f, effect = ProjEffect.FREEZE, effectDuration = 1.6f, color = 0xFFCFF6FF.toInt(), cinematic = true, anim = "cast", desc = "Flash-freeze the entire battlefield around the enemy.")
            ),
            "Dictates the pace with slows and freezes. Land the prison and the round is hers."
        ),
        CharacterDef(
            6, "Cyber Soldier", "Augmented combat platform", Style.BALANCED,
            1050f, 2.7f, 5.3f, 6.2f, 1.0f, 1.1f,
            0xFFA8B4C0.toInt(), 0xFF3C4F5C.toInt(), 0xFF232F38.toInt(), 0xFF39FF9C.toInt(), 0xFF2C3A44.toInt(),
            1.1f, 1.05f, Headgear.HELMET, Weapon.GAUNTLETS, true,
            listOf(
                SkillDef("Plasma Disc", SkillType.PROJECTILE, 25f, 2.3f, 95f, speed = 10f, radius = 0.38f, color = 0xFF39FF9C.toInt(), desc = "Launch a disc of superheated plasma."),
                SkillDef("Overclock", SkillType.BUFF, 30f, 7f, 0f, buff = BuffKind.SPEED, buffDuration = 5f, buffPower = 1.5f, color = 0xFF39FF9C.toInt(), anim = "roar", desc = "Overdrive servos: +50% speed and +25% damage for 5 seconds."),
                SkillDef("Orbital Laser", SkillType.SKY_STRIKE, 100f, 8f, 320f, hits = 1, radius = 1.2f, color = 0xFF39FF9C.toInt(), cinematic = true, anim = "cast", desc = "Paint the target and call a precision strike from orbit.")
            ),
            "A walking weapons system. Overclock turns him into a blur of green light."
        ),
        CharacterDef(
            7, "Wind Master", "The untouchable gale", Style.TRICKY,
            900f, 3.4f, 6.6f, 7.2f, 0.9f, 0.8f,
            0xFFD6A98C.toInt(), 0xFFE8E4D8.toInt(), 0xFF7FA88F.toInt(), 0xFF9CFFD8.toInt(), 0xFFC9C2B0.toInt(),
            0.85f, 1.0f, Headgear.TOPKNOT, Weapon.STAFF, false,
            listOf(
                SkillDef("Gale Dash", SkillType.DASH_ATTACK, 25f, 2.5f, 90f, range = 4f, color = 0xFF9CFFD8.toInt(), anim = "dash", desc = "Ride the wind straight through the enemy, cutting as you pass."),
                SkillDef("Cyclone Kick", SkillType.SPIN, 30f, 4.5f, 45f, hits = 4, radius = 1.4f, launcher = true, color = 0xFFC9FFE8.toInt(), anim = "spin", desc = "A rising cyclone of kicks that carries foes into the air."),
                SkillDef("Tempest Dive", SkillType.AOE_TARGET, 100f, 8f, 290f, radius = 2.2f, knockdown = true, color = 0xFF9CFFD8.toInt(), cinematic = true, anim = "slam", desc = "Leap into the stratosphere and crash down like a hurricane.")
            ),
            "The fastest fighter alive. He fights like weather — everywhere at once."
        ),
        CharacterDef(
            8, "Dark Assassin", "Contract killer of the eclipse", Style.TRICKY,
            920f, 3.1f, 6.1f, 6.9f, 1.0f, 0.85f,
            0xFFB08A70.toInt(), 0xFF1F1B2E.toInt(), 0xFF120F1E.toInt(), 0xFFFF3D6E.toInt(), 0xFF0E0C16.toInt(),
            0.92f, 1.02f, Headgear.HOOD, Weapon.NONE, true,
            listOf(
                SkillDef("Vanish Strike", SkillType.TELEPORT_STRIKE, 25f, 3f, 110f, range = 3.5f, color = 0xFFFF3D6E.toInt(), anim = "dash", desc = "Melt into darkness and drive a blade into the foe's back."),
                SkillDef("Night Blades", SkillType.PROJECTILE, 30f, 4f, 42f, hits = 3, speed = 10f, radius = 0.28f, color = 0xFFC92E5A.toInt(), desc = "Fan out three cursed daggers in a spread."),
                SkillDef("Eclipse Execution", SkillType.MULTI_RUSH, 100f, 8f, 60f, hits = 6, range = 3f, color = 0xFFFF3D6E.toInt(), cinematic = true, anim = "dash", desc = "Six executioner's slashes under a blood-red eclipse.")
            ),
            "Teleports, daggers, and cruelty. He punishes every whiffed attack."
        ),
        CharacterDef(
            9, "Martial Artist", "Master of the flowing fist", Style.BALANCED,
            1000f, 2.9f, 5.5f, 6.6f, 1.0f, 1.0f,
            0xFFD8A878.toInt(), 0xFFF2EDE0.toInt(), 0xFFC9B896.toInt(), 0xFFFF8C3A.toInt(), 0xFF2A2018.toInt(),
            1.0f, 1.0f, Headgear.HEADBAND, Weapon.NONE, false,
            listOf(
                SkillDef("Palm Wave", SkillType.PROJECTILE, 25f, 2.4f, 95f, speed = 9f, radius = 0.4f, color = 0xFFFFB03A.toInt(), desc = "A focused wave of ki from an open palm."),
                SkillDef("Flowing Counter", SkillType.BUFF, 30f, 6f, 0f, buff = BuffKind.COUNTER_STANCE, buffDuration = 2.5f, color = 0xFFFF8C3A.toInt(), anim = "cast", desc = "Enter a flowing stance: the next hit is parried and answered automatically."),
                SkillDef("Hundred Fists", SkillType.MULTI_RUSH, 100f, 8f, 38f, hits = 8, range = 2.2f, color = 0xFFFFD24D.toInt(), cinematic = true, anim = "dash", desc = "A blur of eight palm strikes faster than the eye can follow.")
            ),
            "The complete fighter. Fundamentals, footsies, and a counter for everything."
        ),
        CharacterDef(
            10, "Beast Warrior", "Untamed mountain of muscle", Style.GRAPPLER,
            1300f, 2.0f, 4.2f, 5.5f, 1.35f, 1.5f,
            0xFF9C7050.toInt(), 0xFF5C4030.toInt(), 0xFF3A2A1E.toInt(), 0xFFE84D2A.toInt(), 0xFF4A3526.toInt(),
            1.4f, 1.12f, Headgear.NONE, Weapon.NONE, false,
            listOf(
                SkillDef("Savage Charge", SkillType.DASH_ATTACK, 25f, 3.5f, 130f, range = 3.5f, color = 0xFFE84D2A.toInt(), anim = "dash", desc = "An armored shoulder charge that ignores incoming hits."),
                SkillDef("Ground Breaker", SkillType.AOE_SELF, 35f, 5f, 160f, radius = 2.2f, knockdown = true, color = 0xFFC96A2E.toInt(), anim = "slam", desc = "Pulverize the earth, knocking down everything in reach."),
                SkillDef("Primal Roar", SkillType.AOE_SELF, 100f, 8f, 280f, radius = 2.8f, buff = BuffKind.DAMAGE, buffDuration = 6f, buffPower = 1.35f, color = 0xFFE84D2A.toInt(), cinematic = true, anim = "roar", knockdown = true, desc = "A roar that shatters the ground and fuels a primal rage.")
            ),
            "The heaviest hitter. Trading blows with him is a losing proposition."
        ),
        CharacterDef(
            11, "Lightning Monk", "Temple guardian of the storm", Style.RUSHDOWN,
            980f, 3.0f, 5.7f, 6.7f, 1.0f, 0.95f,
            0xFFC9976B.toInt(), 0xFFE8A33A.toInt(), 0xFF8A5C1E.toInt(), 0xFFFFF24D.toInt(), 0xFF3A2E1E.toInt(),
            0.95f, 0.98f, Headgear.NONE, Weapon.NONE, true,
            listOf(
                SkillDef("Spark Palm", SkillType.PROJECTILE, 25f, 2.2f, 90f, speed = 12f, radius = 0.32f, color = 0xFFFFF24D.toInt(), desc = "A crackling palm thrust that sends a spark flying."),
                SkillDef("Thunder Clap", SkillType.AOE_SELF, 30f, 5f, 125f, radius = 1.7f, effect = ProjEffect.SLOW, effectDuration = 1.5f, color = 0xFFFFD24D.toInt(), anim = "slam", desc = "Clap thunder itself, stunning and shocking all nearby."),
                SkillDef("Nirvana Storm", SkillType.MULTI_RUSH, 100f, 8f, 45f, hits = 7, range = 2.5f, color = 0xFFFFF24D.toInt(), cinematic = true, anim = "dash", desc = "Seven electrified strikes delivered in a state of perfect calm.")
            ),
            "Serene until he isn't. Electric combos that never seem to end."
        ),
        CharacterDef(
            12, "Samurai", "Last blade of the fallen house", Style.BALANCED,
            1000f, 2.8f, 5.4f, 6.4f, 1.1f, 1.0f,
            0xFFD6A98C.toInt(), 0xFF8A1F2E.toInt(), 0xFF2E2430.toInt(), 0xFFE8E0D0.toInt(), 0xFF1A1620.toInt(),
            1.0f, 1.03f, Headgear.TOPKNOT, Weapon.SWORD, false,
            listOf(
                SkillDef("Iai Slash", SkillType.DASH_ATTACK, 25f, 3f, 120f, range = 3.4f, color = 0xFFE8E0D0.toInt(), anim = "dash", desc = "Draw, cut, and sheathe in a single heartbeat."),
                SkillDef("Crescent Cut", SkillType.PROJECTILE, 30f, 4f, 105f, speed = 9f, radius = 0.5f, color = 0xFFFF6B7A.toInt(), desc = "Loose a crescent wave of cutting force from the blade."),
                SkillDef("Thousand Petals", SkillType.MULTI_RUSH, 100f, 8f, 52f, hits = 6, range = 2.8f, color = 0xFFFF8A9C.toInt(), cinematic = true, anim = "dash", desc = "Six iai cuts, each faster than the last, like falling petals.")
            ),
            "Reach and precision. His sword controls a zone nobody else can touch."
        ),
        CharacterDef(
            13, "Street Brawler", "King of the back alleys", Style.RUSHDOWN,
            1100f, 2.7f, 5.2f, 6.2f, 1.15f, 1.15f,
            0xFFB98A68.toInt(), 0xFF3A6E3A.toInt(), 0xFF2A3A2E.toInt(), 0xFFFFD24D.toInt(), 0xFF2A2018.toInt(),
            1.15f, 1.02f, Headgear.NONE, Weapon.GAUNTLETS, false,
            listOf(
                SkillDef("Haymaker", SkillType.DASH_ATTACK, 25f, 3f, 135f, range = 2.4f, color = 0xFFFFD24D.toInt(), knockdown = true, anim = "dash", desc = "A looping overhand that puts people to sleep."),
                SkillDef("Dirty Combo", SkillType.MULTI_RUSH, 30f, 4.5f, 48f, hits = 3, range = 1.8f, color = 0xFFFFA33A.toInt(), anim = "dash", desc = "Headbutt, hook, elbow — no rules in the alley."),
                SkillDef("Concrete Justice", SkillType.MULTI_RUSH, 100f, 8f, 320f, hits = 1, range = 2f, color = 0xFFFFD24D.toInt(), cinematic = true, knockdown = true, anim = "slam", desc = "Grab the foe and introduce them to the pavement. Hard.")
            ),
            "No technique, all violence. Wins fights that shouldn't be winnable."
        ),
        CharacterDef(
            14, "Energy Master", "Keeper of the radiant core", Style.ZONER,
            920f, 2.6f, 5.0f, 6.4f, 1.0f, 0.9f,
            0xFFE0B48E.toInt(), 0xFFF2F2F2.toInt(), 0xFF6E5CA8.toInt(), 0xFFC99CFF.toInt(), 0xFFE8E4F2.toInt(),
            0.92f, 1.06f, Headgear.NONE, Weapon.NONE, true,
            listOf(
                SkillDef("Ki Bolt", SkillType.PROJECTILE, 25f, 2.2f, 95f, speed = 10.5f, radius = 0.36f, color = 0xFFC99CFF.toInt(), desc = "A condensed bolt of pure radiant energy."),
                SkillDef("Radiant Barrier", SkillType.BUFF, 30f, 7f, 0f, buff = BuffKind.SHIELD, buffDuration = 8f, buffPower = 2f, color = 0xFFC99CFF.toInt(), anim = "cast", desc = "A shimmering barrier that fully absorbs the next two hits."),
                SkillDef("Genesis Beam", SkillType.BEAM, 100f, 8f, 330f, range = 10f, radius = 0.7f, color = 0xFFE0C9FF.toInt(), cinematic = true, anim = "beam", desc = "Fire the accumulated light of a lifetime in one devastating beam.")
            ),
            "The definitive zoner. Behind a barrier, at full screen, he is untouchable."
        )
    )

    fun byId(id: Int): CharacterDef = all[id]
}
