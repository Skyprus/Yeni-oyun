package com.skyprus.kirgec

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ses efektleri dosya gerektirmeden sentezlenir (gürültü + yüksek frekanslı "çın" sesleri),
 * WAV olarak önbelleğe yazılır ve SoundPool ile düşük gecikmeyle çalınır.
 */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sounds = HashMap<String, MutableList<Int>>()

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        Thread {
            fun add(name: String, variants: Int, gen: (Random) -> FloatArray) {
                repeat(variants) { i ->
                    val f = File(dir, "${name}_$i.wav")
                    writeWav(f, gen(Random(name.hashCode() * 31 + i)))
                    val id = pool.load(f.path, 1)
                    synchronized(sounds) { sounds.getOrPut(name) { mutableListOf() }.add(id) }
                }
            }
            add("shatter_glass", 3) { synthShatter(it, Material.GLASS) }
            add("shatter_ceramic", 3) { synthShatter(it, Material.CERAMIC) }
            add("shatter_electronic", 2) { synthShatter(it, Material.ELECTRONIC) }
            add("hit", 2) { synthHit(it) }
            add("miss", 1) { synthMiss() }
            add("throw", 1) { synthWhoosh(it) }
        }.start()
    }

    fun shatter(m: Material) = play("shatter_" + m.name.lowercase())
    fun hit() = play("hit")
    fun miss() = play("miss")
    fun throwSound() = play("throw", 0.6f)

    private fun play(name: String, volume: Float = 1f) {
        val id = synchronized(sounds) { sounds[name]?.randomOrNull() } ?: return
        pool.play(id, volume, volume, 1, 0, 0.92f + Random.nextFloat() * 0.16f)
    }

    fun release() = pool.release()

    // ---------- Sentez ----------
    private companion object {
        const val SR = 44100

        fun buf(seconds: Float) = FloatArray((seconds * SR).toInt())

        /** Filtrelenmiş beyaz gürültü, üstel sönümlü. type: 'h' yüksek geçiren, 'l' alçak geçiren, 'b' bant. */
        fun noise(out: FloatArray, r: Random, start: Float, dur: Float, freq: Float, type: Char, gain: Float) {
            val s0 = (start * SR).toInt()
            val n = minOf((dur * SR).toInt(), out.size - s0)
            val dt = 1f / SR
            val rcLow = 1f / (2f * PI.toFloat() * (if (type == 'b') freq * 1.4f else freq))
            val rcHigh = 1f / (2f * PI.toFloat() * (if (type == 'b') freq * 0.7f else freq))
            val aLow = dt / (rcLow + dt)
            val aHigh = rcHigh / (rcHigh + dt)
            val k = ln(1000f) / dur
            var lp = 0f; var hp = 0f; var prev = 0f
            for (i in 0 until n) {
                val x = r.nextFloat() * 2f - 1f
                var y = x
                if (type == 'l' || type == 'b') { lp += aLow * (y - lp); y = lp }
                if (type == 'h' || type == 'b') { hp = aHigh * (hp + y - prev); prev = y; y = hp }
                out[s0 + i] += y * gain * exp(-k * i / SR)
            }
        }

        fun ping(out: FloatArray, start: Float, freq: Float, dur: Float, gain: Float) {
            val s0 = (start * SR).toInt()
            val n = minOf((dur * SR).toInt(), out.size - s0)
            val k = ln(1000f) / dur
            for (i in 0 until n) {
                val t = i.toFloat() / SR
                out[s0 + i] += sin(2f * PI.toFloat() * freq * t) * gain * exp(-k * t)
            }
        }

        fun synthShatter(r: Random, m: Material): FloatArray {
            val out = buf(1.0f)
            when (m) {
                Material.ELECTRONIC -> {
                    noise(out, r, 0f, 0.5f, 900f, 'l', 0.9f)
                    noise(out, r, 0f, 0.25f, 4000f, 'h', 0.4f)
                    repeat(4) { ping(out, r.nextFloat() * 0.15f, 1200f + r.nextFloat() * 2500f, 0.2f, 0.08f) }
                }
                else -> {
                    val bright = m == Material.GLASS
                    noise(out, r, 0f, if (bright) 0.45f else 0.35f, if (bright) 3500f else 1800f, 'h', 0.9f)
                    noise(out, r, 0f, 0.15f, 500f, 'l', 0.7f)
                    repeat(if (bright) 9 else 5) {
                        ping(out, r.nextFloat() * 0.25f, (if (bright) 2500f else 1500f) + r.nextFloat() * 4000f,
                            0.15f + r.nextFloat() * 0.5f, 0.07f)
                    }
                }
            }
            return out
        }

        fun synthHit(r: Random): FloatArray {
            val out = buf(0.3f)
            ping(out, 0f, 2200f + r.nextFloat() * 800f, 0.25f, 0.25f)
            noise(out, r, 0f, 0.08f, 2000f, 'b', 0.6f)
            return out
        }

        fun synthMiss(): FloatArray {
            val out = buf(0.22f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                val f = 160f * exp(-t * 6.5f)
                phase += 2f * PI.toFloat() * f / SR
                out[i] = sin(phase) * 0.8f * exp(-t * 30f)
            }
            return out
        }

        fun synthWhoosh(r: Random): FloatArray {
            val out = buf(0.2f)
            noise(out, r, 0f, 0.18f, 800f, 'b', 0.6f)
            return out
        }

        fun writeWav(file: File, samples: FloatArray) {
            val peak = samples.maxOf { abs(it) }.coerceAtLeast(1e-4f)
            val norm = 0.9f / peak
            val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) data.putShort((s * norm * Short.MAX_VALUE).toInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SR); putInt(SR * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data.capacity())
            }
            FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
        }
    }
}
