package com.furyclash.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * All audio is synthesized at startup (no asset files): impact thuds, blocks,
 * elemental skills, KO boom, UI blips and a looping synthwave backing track.
 */
class Sfx(context: Context) {
    private val rate = 22050
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
        .build()
    private val ids = HashMap<String, Int>()
    private var music: MediaPlayer? = null
    var enabled = true
    var musicEnabled = true
        set(v) {
            field = v
            if (v) startMusic() else stopMusic()
        }

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        val defs = LinkedHashMap<String, FloatArray>()
        defs["hit"] = mix(
            tone(180f, 70f, 0.10f, 0.8f, Wave.SINE),
            noise(0.07f, 0.5f, 0.5f))
        defs["heavy"] = mix(
            tone(130f, 42f, 0.20f, 1.0f, Wave.SINE),
            noise(0.13f, 0.35f, 0.7f))
        defs["block"] = mix(
            tone(820f, 600f, 0.06f, 0.4f, Wave.SQUARE),
            noise(0.04f, 0.8f, 0.3f))
        defs["parry"] = mix(
            tone(1200f, 1900f, 0.13f, 0.5f, Wave.SINE),
            tone(1800f, 2400f, 0.10f, 0.25f, Wave.SINE))
        defs["jump"] = noise(0.14f, 0.6f, 0.25f)
        defs["shot"] = tone(520f, 180f, 0.16f, 0.5f, Wave.SAW)
        defs["fire"] = mix(tone(300f, 90f, 0.28f, 0.5f, Wave.SAW), noise(0.25f, 0.3f, 0.4f))
        defs["ice"] = mix(tone(1600f, 2500f, 0.13f, 0.35f, Wave.SINE), noise(0.06f, 0.9f, 0.2f))
        defs["lightning"] = crackle(0.22f, 0.6f)
        defs["beam"] = mix(tone(90f, 420f, 0.5f, 0.5f, Wave.SAW), noise(0.45f, 0.4f, 0.3f))
        defs["teleport"] = tone(900f, 180f, 0.14f, 0.4f, Wave.SINE)
        defs["dash"] = noise(0.13f, 0.55f, 0.35f)
        defs["spin"] = noise(0.22f, 0.5f, 0.35f)
        defs["slam"] = mix(tone(95f, 28f, 0.32f, 0.9f, Wave.SINE), noise(0.16f, 0.3f, 0.6f))
        defs["buff"] = arpeggio(floatArrayOf(520f, 660f, 880f), 0.07f, 0.35f)
        defs["rush"] = mix(tone(200f, 90f, 0.08f, 0.6f, Wave.SINE), noise(0.06f, 0.5f, 0.4f))
        defs["ko"] = mix(tone(160f, 26f, 0.65f, 1.0f, Wave.SINE), noise(0.35f, 0.25f, 0.8f))
        defs["switch"] = tone(420f, 840f, 0.11f, 0.35f, Wave.SINE)
        defs["ui"] = tone(700f, 700f, 0.045f, 0.3f, Wave.SINE)
        defs["fight"] = mix(tone(220f, 220f, 0.4f, 0.4f, Wave.SAW), tone(330f, 330f, 0.4f, 0.3f, Wave.SAW))
        defs["roundwin"] = arpeggio(floatArrayOf(440f, 550f, 660f, 880f), 0.09f, 0.4f)
        defs["win"] = arpeggio(floatArrayOf(440f, 550f, 660f, 880f, 1100f), 0.11f, 0.45f)
        defs["ultimate"] = mix(tone(70f, 620f, 0.6f, 0.55f, Wave.SAW), noise(0.55f, 0.35f, 0.35f))

        for ((name, samples) in defs) {
            val f = File(dir, "$name.wav")
            if (!f.exists() || f.length() != (44 + samples.size * 2).toLong()) writeWav(f, samples)
            ids[name] = pool.load(f.absolutePath, 1)
        }

        val musicFile = File(dir, "music.wav")
        if (!musicFile.exists()) writeWav(musicFile, buildMusic())
        music = MediaPlayer().apply {
            setDataSource(musicFile.absolutePath)
            isLooping = true
            setVolume(0.22f, 0.22f)
            prepare()
        }
    }

    fun play(name: String) {
        if (!enabled) return
        ids[name]?.let { pool.play(it, 0.8f, 0.8f, 1, 0, 1f) }
    }

    fun startMusic() {
        if (musicEnabled) music?.let { if (!it.isPlaying) it.start() }
    }

    fun stopMusic() {
        music?.let { if (it.isPlaying) it.pause() }
    }

    fun release() {
        pool.release()
        music?.release()
        music = null
    }

    // ---- synthesis ----
    private enum class Wave { SINE, SQUARE, SAW }

    private fun tone(f0: Float, f1: Float, dur: Float, amp: Float, wave: Wave): FloatArray {
        val n = (rate * dur).toInt()
        val out = FloatArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val f = f0 + (f1 - f0) * t
            phase += 2f * PI.toFloat() * f / rate
            val s = when (wave) {
                Wave.SINE -> sin(phase)
                Wave.SQUARE -> if (sin(phase) > 0f) 1f else -1f
                Wave.SAW -> (phase / (2f * PI.toFloat()) % 1f) * 2f - 1f
            }
            val env = min(1f, t * 40f) * exp(-3.5f * t)
            out[i] = s * amp * env
        }
        return out
    }

    private fun noise(dur: Float, lp: Float, amp: Float): FloatArray {
        val n = (rate * dur).toInt()
        val out = FloatArray(n)
        var last = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val white = Random.nextFloat() * 2f - 1f
            last = last + lp * (white - last)
            out[i] = last * amp * exp(-5f * t) * min(1f, t * 80f)
        }
        return out
    }

    private fun crackle(dur: Float, amp: Float): FloatArray {
        val n = (rate * dur).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / n
            out[i] = (Random.nextFloat() * 2f - 1f) * amp * exp(-4f * t) *
                (if (Random.nextFloat() < 0.4f) 1f else 0.15f)
        }
        return out
    }

    private fun arpeggio(freqs: FloatArray, noteDur: Float, amp: Float): FloatArray {
        val parts = freqs.map { tone(it, it, noteDur, amp, Wave.SINE) }
        val n = parts.sumOf { it.size }
        val out = FloatArray(n)
        var o = 0
        for (part in parts) { System.arraycopy(part, 0, out, o, part.size); o += part.size }
        return out
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val n = parts.maxOf { it.size }
        val out = FloatArray(n)
        for (part in parts) for (i in part.indices) out[i] += part[i]
        for (i in 0 until n) out[i] = max(-1f, min(1f, out[i]))
        return out
    }

    private fun buildMusic(): FloatArray {
        // 12s synthwave loop: Am F C G, arpeggio + bass + pad
        val bpm = 100f
        val beat = 60f / bpm
        val total = (rate * beat * 16f).toInt()
        val out = FloatArray(total)
        val chords = floatArrayOf(
            220f, 261.6f, 329.6f,   // Am
            174.6f, 220f, 261.6f,   // F
            196f, 261.6f, 329.6f,   // C (G bass feel)
            196f, 246.9f, 293.7f    // G
        )
        val bass = floatArrayOf(110f, 87.3f, 130.8f, 98f)
        for (bar in 0 until 4) {
            val barStart = (bar * 4 * beat * rate).toInt()
            // pad
            for (k in 0 until 3) {
                val f = chords[bar * 3 + k]
                val start = barStart
                val len = (4 * beat * rate).toInt()
                for (i in 0 until len) {
                    val idx = start + i
                    if (idx >= total) break
                    val t = i.toFloat() / rate
                    val env = min(1f, t * 2f) * min(1f, (4 * beat - t) * 2f)
                    out[idx] += 0.05f * sin(2f * PI.toFloat() * f * t) * env
                }
            }
            // bass + arpeggio eighth notes
            for (e in 0 until 8) {
                val f = if (e % 2 == 0) bass[bar] else chords[bar * 3 + (e / 2) % 3] * 2f
                val start = barStart + (e * beat / 2f * rate).toInt()
                val len = (beat / 2f * rate).toInt()
                var phase = 0f
                for (i in 0 until len) {
                    val idx = start + i
                    if (idx >= total) break
                    val t = i.toFloat() / len
                    phase += 2f * PI.toFloat() * f / rate
                    val env = min(1f, t * 30f) * exp(-3f * t)
                    val s = if (e % 2 == 0) sin(phase) else (phase / (2f * PI.toFloat()) % 1f) * 2f - 1f
                    out[idx] += (if (e % 2 == 0) 0.12f else 0.05f) * s * env
                }
            }
        }
        for (i in out.indices) out[i] = max(-1f, min(1f, out[i]))
        return out
    }

    private fun writeWav(f: File, samples: FloatArray) {
        val dataLen = samples.size * 2
        val buf = ByteArray(44 + dataLen)
        fun put32(o: Int, v: Int) {
            buf[o] = (v and 0xFF).toByte(); buf[o + 1] = ((v shr 8) and 0xFF).toByte()
            buf[o + 2] = ((v shr 16) and 0xFF).toByte(); buf[o + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun put16(o: Int, v: Int) {
            buf[o] = (v and 0xFF).toByte(); buf[o + 1] = ((v shr 8) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(buf, 0)
        put32(4, 36 + dataLen)
        "WAVE".toByteArray().copyInto(buf, 8)
        "fmt ".toByteArray().copyInto(buf, 12)
        put32(16, 16); put16(20, 1); put16(22, 1)
        put32(24, rate); put32(28, rate * 2); put16(32, 2); put16(34, 16)
        "data".toByteArray().copyInto(buf, 36)
        put32(40, dataLen)
        for (i in samples.indices) {
            val v = (max(-1f, min(1f, samples[i])) * 32767f).toInt()
            put16(44 + i * 2, v)
        }
        FileOutputStream(f).use { it.write(buf) }
    }
}
