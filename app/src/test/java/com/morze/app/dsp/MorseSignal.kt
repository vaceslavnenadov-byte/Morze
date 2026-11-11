package com.morze.app.dsp

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Генерирует тестовые сигналы Морзе: длительности посылок и PCM-звук. */
object MorseSignal {

    /** Пары (ключ нажат, длительность мс) для текста [text]. */
    fun timings(text: String, alphabet: Alphabet, wpm: Double, jitter: Double = 0.0, seed: Int = 1): List<Pair<Boolean, Double>> {
        val unit = 1200.0 / wpm
        val random = Random(seed)
        fun d(units: Int) = units * unit * (1.0 + (random.nextDouble() * 2 - 1) * jitter)
        val result = mutableListOf<Pair<Boolean, Double>>()
        val codes = MorseCode.encode(text, alphabet)
        for ((index, code) in codes.withIndex()) {
            if (code == " ") continue
            for ((k, element) in code.withIndex()) {
                result += true to d(if (element == '.') 1 else 3)
                if (k < code.length - 1) result += false to d(1)
            }
            val next = codes.getOrNull(index + 1)
            result += false to when (next) {
                null -> d(10)
                " " -> d(7)
                else -> d(3)
            }
        }
        return result
    }

    /** 16-битный PCM с тоном [freq] и белым шумом амплитуды [noise] (доля полной шкалы). */
    fun pcm(
        timings: List<Pair<Boolean, Double>>,
        sampleRate: Int,
        freq: Double,
        amplitude: Double = 0.3,
        noise: Double = 0.0,
        leadMs: Double = 300.0,
        seed: Int = 2,
        /** Множитель амплитуды в зависимости от времени (мс) с начала посылки. */
        shape: (Double) -> Double = { 1.0 },
    ): ShortArray {
        val random = Random(seed)
        val all = listOf(false to leadMs) + timings
        val total = all.sumOf { it.second } * sampleRate / 1000
        val out = ShortArray(total.toInt() + 1)
        val rampSamples = sampleRate * 4 / 1000
        var pos = 0
        for ((down, ms) in all) {
            val len = (ms * sampleRate / 1000).toInt()
            for (i in 0 until len) {
                if (pos >= out.size) break
                var v = (random.nextDouble() * 2 - 1) * noise
                if (down) {
                    val ramp = min(1.0, min(i, len - i).toDouble() / rampSamples)
                    v += amplitude * shape(i * 1000.0 / sampleRate) * ramp * sin(2 * PI * freq * pos / sampleRate)
                }
                out[pos++] = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            }
        }
        return out
    }

    /** Эхо помещения: к прямому звуку добавляются затухающие отражения. */
    fun reverb(pcm: ShortArray, sampleRate: Int, rt60Ms: Double, gain: Double, seed: Int = 1): ShortArray {
        val random = Random(seed)
        val taps = 150
        val span = rt60Ms * sampleRate / 1000
        val delays = IntArray(taps) { (random.nextDouble() * span).toInt() + 200 }
        val gains = DoubleArray(taps) {
            val sign = if (random.nextBoolean()) 1 else -1
            sign * gain * 4 / Math.sqrt(taps.toDouble()) * Math.pow(10.0, -3.0 * delays[it] / span)
        }
        val out = DoubleArray(pcm.size)
        for (i in pcm.indices) {
            val v = pcm[i].toDouble()
            out[i] += v
            for (k in 0 until taps) {
                val j = i + delays[k]
                if (j < out.size) out[j] += v * gains[k]
            }
        }
        return ShortArray(pcm.size) { out[it].coerceIn(-32767.0, 32767.0).toInt().toShort() }
    }
}

/** Собирает результат декодирования в строку. */
class TextCollector : MorseTimingDecoder.Listener {
    val text = StringBuilder()
    override fun onCodeChanged(code: String) {}
    override fun onCharacter(code: String, text: String) {
        this.text.append(text)
    }
    override fun onWordSpace() {
        text.append(' ')
    }
    override fun toString() = text.toString().trim()
}
