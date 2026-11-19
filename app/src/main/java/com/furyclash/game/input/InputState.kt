package com.furyclash.game.input

/**
 * Per-player input snapshot. Movement/block are level signals; attack buttons
 * are edge presses with a short buffer so inputs during recovery still come out.
 */
class InputState {
    var moveX = 0f
    var moveY = 0f
    var blockHeld = false

    private val age = FloatArray(8) { 99f }

    fun press(btn: Int) { age[btn] = 0f }
    fun update(dt: Float) { for (i in age.indices) age[i] += dt }
    fun fresh(btn: Int) = age[btn] < BUFFER
    fun consume(btn: Int): Boolean {
        if (age[btn] < BUFFER) { age[btn] = 99f; return true }
        return false
    }
    fun clearEdges() { age.fill(99f) }
    fun reset() { moveX = 0f; moveY = 0f; blockHeld = false; clearEdges() }

    companion object {
        const val JUMP = 0
        const val PUNCH = 1
        const val KICK = 2
        const val HEAVY = 3
        const val S1 = 4
        const val S2 = 5
        const val ULT = 6
        const val SWITCH = 7
        const val BUFFER = 0.22f
    }
}
