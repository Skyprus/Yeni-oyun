package com.skyprus.odagolf

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
import kotlin.math.min
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
            add("swing", 2) { synthSwing(it) }
            add("bat", 2) { synthBat(it) }
            add("clank", 2) { synthClank(it) }
            add("sling", 2) { synthSling(it) }
            add("bounce", 2) { synthBounce(it) }
            add("shatter_vehicle", 2) { synthVehicle(it) }
            add("ignite", 1) { synthIgnite(it) }
            add("fire", 1) { synthFireLoop(it) }
            add("explosion", 2) { synthExplosion(it) }
            add("fall", 1) { synthFall() }
            add("impact", 2) { synthImpact(it) }
            add("putt", 2) { synthPutt(it) }
            add("cup", 1) { synthCup(it) }
        }.start()
    }

    fun shatter(m: Material) = play("shatter_" + m.name.lowercase())
    fun hit() = play("hit")
    fun miss() = play("miss")
    fun throwSound() = play("throw", 0.6f)
    fun swing() = play("swing", 0.7f)
    /** Yakın dövüş aletinin çarpma sesi (kırılma sesinin üstüne de çalınır). */
    fun sling() = play("sling", 0.9f)
    fun ignite() = play("ignite")
    fun explosion() = play("explosion")
    fun fall() = play("fall", 0.8f)
    fun impact() = play("impact")
    /** Döngüde çalan yangın çıtırtısı; dönen kimlikle [stop] edilir. */
    fun fireLoop(): Int {
        val id = synchronized(sounds) { sounds["fire"]?.firstOrNull() } ?: return 0
        return pool.play(id, 0.55f, 0.55f, 1, -1, 1f)
    }
    fun stop(stream: Int) { if (stream != 0) pool.stop(stream) }
    fun bounce() = play("bounce", 0.7f)
    /** Mini golf: topa vuruş ve topun deliğe düşmesi. */
    fun putt() = play("putt", 0.8f)
    fun cup() = play("cup")
    fun melee(w: Weapon) = play(if (w == Weapon.WRENCH) "clank" else "bat")

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

        fun synthSwing(r: Random): FloatArray {
            val out = buf(0.3f)
            // Yükselip alçalan bant geçiren gürültü: havayı yaran alet
            val n = out.size
            var lp = 0f; var hp = 0f; var prev = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val f = 400f + 1800f * sin(PI.toFloat() * t)
                val dt = 1f / SR
                val aL = dt / (1f / (2f * PI.toFloat() * f * 1.5f) + dt)
                val rcH = 1f / (2f * PI.toFloat() * f * 0.6f)
                val aH = rcH / (rcH + dt)
                val x = r.nextFloat() * 2f - 1f
                lp += aL * (x - lp)
                hp = aH * (hp + lp - prev); prev = lp
                out[i] = hp * sin(PI.toFloat() * t)
            }
            return out
        }

        /** Tahta sopa: kısa, tok bir darbe. */
        fun synthBat(r: Random): FloatArray {
            val out = buf(0.35f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                phase += 2f * PI.toFloat() * (180f * exp(-t * 8f) + 70f) / SR
                out[i] += sin(phase) * exp(-t * 25f)
            }
            noise(out, r, 0f, 0.06f, 1500f, 'l', 0.9f)
            ping(out, 0f, 420f + r.nextFloat() * 60f, 0.12f, 0.25f)
            return out
        }

        /** İngiliz anahtarı: uyumsuz kısmi tonlarla metalik çınlama. */
        fun synthClank(r: Random): FloatArray {
            val out = buf(0.8f)
            val base = 480f + r.nextFloat() * 80f
            for ((mul, g) in listOf(1f to 0.35f, 2.76f to 0.25f, 5.4f to 0.18f, 8.93f to 0.12f)) {
                ping(out, 0f, base * mul, 0.7f / (1f + mul * 0.15f), g)
            }
            noise(out, r, 0f, 0.04f, 3000f, 'h', 0.8f)
            return out
        }

        /** Sapan lastiğinin bırakılması: titreşen düşük "tıng" ve hava sesi. */
        fun synthSling(r: Random): FloatArray {
            val out = buf(0.35f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                val f = 140f + 60f * exp(-t * 20f) + 8f * sin(2f * PI.toFloat() * 28f * t)
                phase += 2f * PI.toFloat() * f / SR
                out[i] += sin(phase) * 0.6f * exp(-t * 14f)
            }
            noise(out, r, 0f, 0.12f, 1200f, 'b', 0.5f)
            return out
        }

        /** Topun sekmesi: kısa, lastikli "pof". */
        fun synthBounce(r: Random): FloatArray {
            val out = buf(0.18f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                phase += 2f * PI.toFloat() * (260f * exp(-t * 12f) + 90f) / SR
                out[i] += sin(phase) * exp(-t * 30f)
            }
            noise(out, r, 0f, 0.03f, 900f, 'l', 0.4f)
            return out
        }

        /** Sopanın topa vuruşu: kısa, tok "tık". */
        fun synthPutt(r: Random): FloatArray {
            val out = buf(0.12f)
            ping(out, 0f, 1650f, 0.05f, 0.5f)
            ping(out, 0f, 720f, 0.08f, 0.35f)
            noise(out, r, 0f, 0.02f, 2500f, 'h', 0.5f)
            return out
        }

        /** Top deliğe düşer: kabın dibine çarpıp birkaç kez tıngırdar. */
        fun synthCup(r: Random): FloatArray {
            val out = buf(0.7f)
            val hits = floatArrayOf(0f, 0.16f, 0.27f, 0.34f)
            for ((i, t) in hits.withIndex()) {
                val g = 0.8f / (1 + i)
                ping(out, t, 980f + i * 40f, 0.25f, g)
                ping(out, t, 2350f, 0.12f, g * 0.4f)
                noise(out, r, t, 0.03f, 1800f, 'b', g * 0.5f)
            }
            return out
        }

        /** Araç: ezilen metal + kırılan camlar. */
        fun synthVehicle(r: Random): FloatArray {
            val out = buf(1.1f)
            noise(out, r, 0f, 0.7f, 700f, 'l', 1.0f)
            noise(out, r, 0.02f, 0.4f, 3000f, 'h', 0.5f)
            for ((mul, g) in listOf(1f to 0.2f, 2.3f to 0.15f, 3.9f to 0.1f)) ping(out, 0.01f, 260f * mul, 0.5f, g)
            repeat(8) { ping(out, r.nextFloat() * 0.3f, 2500f + r.nextFloat() * 4000f, 0.2f + r.nextFloat() * 0.4f, 0.06f) }
            return out
        }

        /** Molotofun tutuşması: yükselen "vuuf". */
        fun synthIgnite(r: Random): FloatArray {
            val out = buf(0.8f)
            var lp = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                val a = (2f * PI.toFloat() * (300f + 1500f * t) / SR).coerceAtMost(1f)
                lp += a * (r.nextFloat() * 2f - 1f - lp)
                out[i] = lp * min(1f, t * 12f) * exp(-t * 3.5f)
            }
            return out
        }

        /** Yangın çıtırtısı (döngüye uygun, 2 sn). */
        fun synthFireLoop(r: Random): FloatArray {
            val out = buf(2f)
            noise(out, r, 0f, 2f, 500f, 'l', 0.0f)
            var lp = 0f
            for (i in out.indices) {
                lp += 0.02f * (r.nextFloat() * 2f - 1f - lp)
                out[i] += lp * 3f
            }
            repeat(70) {
                val s0 = r.nextFloat() * 1.95f
                noise(out, r, s0, 0.012f + r.nextFloat() * 0.02f, 2500f, 'h', 0.5f + r.nextFloat())
            }
            return out
        }

        /** Patlama: derin gümbürtü. */
        fun synthExplosion(r: Random): FloatArray {
            val out = buf(2.2f)
            noise(out, r, 0f, 2.0f, 160f, 'l', 2.5f)
            noise(out, r, 0f, 0.5f, 1500f, 'l', 0.8f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                phase += 2f * PI.toFloat() * (55f * exp(-t * 1.5f) + 30f) / SR
                out[i] += sin(phase) * 0.9f * exp(-t * 2.2f)
            }
            return out
        }

        /** Düşen kayanın ıslığı (inen ton). */
        fun synthFall(): FloatArray {
            val out = buf(0.9f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                phase += 2f * PI.toFloat() * (1300f - 1000f * t / 0.9f) / SR
                out[i] = sin(phase) * 0.35f * min(1f, t * 5f)
            }
            return out
        }

        /** Kayanın yere/nesneye çarpması: ağır, uzun gümleme. */
        fun synthImpact(r: Random): FloatArray {
            val out = buf(1.4f)
            var phase = 0f
            for (i in out.indices) {
                val t = i.toFloat() / SR
                phase += 2f * PI.toFloat() * (70f * exp(-t * 4f) + 38f) / SR
                out[i] += sin(phase) * exp(-t * 3f)
            }
            noise(out, r, 0f, 1.2f, 400f, 'l', 1.6f)
            noise(out, r, 0f, 0.15f, 2000f, 'b', 0.5f)
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
