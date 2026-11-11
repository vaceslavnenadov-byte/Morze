package com.morze.app.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Мощность сигнала на частоте [freq] в блоке отсчётов (алгоритм Гёрцеля). */
fun goertzelPower(samples: FloatArray, offset: Int, length: Int, freq: Double, sampleRate: Int): Double {
    val coeff = 2.0 * cos(2.0 * PI * freq / sampleRate)
    var s1 = 0.0
    var s2 = 0.0
    for (i in offset until offset + length) {
        val s0 = samples[i] + coeff * s1 - s2
        s2 = s1
        s1 = s0
    }
    val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
    return power / (length.toDouble() * length)
}

/**
 * Ищет частоту тона Морзе: самый сильный устойчивый пик спектра в заданном диапазоне.
 * Новая частота принимается, только если пик держится несколько анализов подряд.
 */
class FrequencyTracker(
    private val sampleRate: Int,
    private val minFreq: Double = 300.0,
    private val maxFreq: Double = 2000.0,
) {
    private val fftSize = Integer.highestOneBit(sampleRate / 8).coerceAtLeast(512)
    private val buffer = FloatArray(fftSize)
    private var filled = 0
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    private var candidate = 0.0
    private var candidateHits = 0

    /** Текущая оценка частоты тона, Гц; 0 — тон ещё не найден. */
    var frequency = 0.0
        private set

    fun reset() {
        filled = 0
        candidate = 0.0
        candidateHits = 0
        frequency = 0.0
    }

    fun process(samples: FloatArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(end - i, fftSize - filled)
            System.arraycopy(samples, i, buffer, filled, n)
            filled += n
            i += n
            if (filled == fftSize) {
                analyze()
                filled = 0
            }
        }
    }

    private fun analyze() {
        for (k in 0 until fftSize) {
            re[k] = (buffer[k] * window[k]).toDouble()
            im[k] = 0.0
        }
        fft(re, im)

        val binHz = sampleRate.toDouble() / fftSize
        val lo = (minFreq / binHz).toInt().coerceAtLeast(1)
        val hi = (maxFreq / binHz).toInt().coerceAtMost(fftSize / 2 - 2)
        val mags = DoubleArray(hi - lo + 1) { re[lo + it] * re[lo + it] + im[lo + it] * im[lo + it] }
        var peak = 0
        for (k in mags.indices) if (mags[k] > mags[peak]) peak = k
        val median = mags.sortedArray()[mags.size / 2]
        // Пик должен заметно возвышаться над шумом, иначе в этом окне тона нет.
        if (mags[peak] <= median * PEAK_TO_MEDIAN || mags[peak] < MIN_PEAK_POWER) return

        // Параболическая интерполяция для точности лучше одного бина.
        val bin = lo + peak
        val a = re[bin - 1] * re[bin - 1] + im[bin - 1] * im[bin - 1]
        val b = mags[peak]
        val c = re[bin + 1] * re[bin + 1] + im[bin + 1] * im[bin + 1]
        val denom = a - 2 * b + c
        val delta = if (denom != 0.0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
        val peakFreq = (bin + delta) * binHz

        if (frequency > 0 && abs(peakFreq - frequency) < TOLERANCE_HZ) {
            frequency += (peakFreq - frequency) * 0.3
            candidateHits = 0
            return
        }
        if (candidate > 0 && abs(peakFreq - candidate) < TOLERANCE_HZ) {
            candidateHits++
        } else {
            candidate = peakFreq
            candidateHits = 1
        }
        if (frequency == 0.0 || candidateHits >= HITS_TO_SWITCH) {
            frequency = candidate
            candidateHits = 0
        }
    }

    companion object {
        private const val PEAK_TO_MEDIAN = 30.0
        private const val MIN_PEAK_POWER = 1e-9
        private const val TOLERANCE_HZ = 40.0
        private const val HITS_TO_SWITCH = 4

        /** Радикс-2 БПФ на месте. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]
                        val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr
                        im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr
                        im[i + k + len / 2] = ui - vi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

/**
 * Решает, звучит ли тон, по уровню в дБ: отслеживает уровень шума и пиковый уровень
 * сигнала и сравнивает текущий уровень с порогом посередине (с гистерезисом).
 */
class KeyDetector(
    /** Минимальное превышение сигнала над шумом, дБ, чтобы вообще считать его тоном. */
    @Volatile var minSnrDb: Double = 10.0,
    /**
     * Порог громкости, дБ: посылка не начинается, пока тон тише порога, и заканчивается,
     * когда тон опускается заметно ниже него (запас [LEVEL_HYSTERESIS_DB] — чтобы
     * колебания громкости возле порога не рвали длинную посылку).
     */
    @Volatile var minLevelDb: Double = Double.NEGATIVE_INFINITY,
) {
    var noiseDb = Double.NaN
        private set
    var peakDb = Double.NaN
        private set
    var keyDown = false
        private set

    private var pending = false
    private var pendingFrames = 0
    private var downFrames = 0

    /** Огибающая текущей посылки: быстро растёт вместе с тоном и медленно спадает. */
    private var markDb = Double.NaN

    fun reset() {
        markDb = Double.NaN
        downFrames = 0
        noiseDb = Double.NaN
        peakDb = Double.NaN
        keyDown = false
        pending = false
        pendingFrames = 0
    }

    /** Возвращает подтверждённое состояние ключа после кадра с уровнем [levelDb]. */
    fun process(levelDb: Double): Boolean {
        if (noiseDb.isNaN()) {
            noiseDb = levelDb
            peakDb = levelDb
        }
        // Шум — среднее уровня в паузах; пик — средний уровень тона.
        val isSignal = levelDb > noiseDb + minSnrDb
        noiseDb += (levelDb - noiseDb) * when {
            isSignal || keyDown -> 0.002
            levelDb < noiseDb -> 0.1
            else -> 0.05
        }
        peakDb += (levelDb - peakDb) * when {
            !isSignal -> 0.001
            levelDb > peakDb -> 0.5
            else -> 0.02
        }
        if (peakDb < noiseDb) peakDb = noiseDb

        // Начало посылки: тон заметно выше шума и не намного тише недавних посылок —
        // иначе за новую посылку принимался бы затухающий «хвост» эха предыдущей.
        // Конец посылки: уровень резко упал относительно её собственной огибающей.
        // Сравнение с огибающей, а не с шумом, даёт быстрый конец при громком сигнале
        // (эхо не съедает паузу) и терпит плавное проседание громкости длинного тона.
        val span = peakDb - noiseDb
        val onDb = maxOf(span * ON_FRACTION, minSnrDb)
        markDb = if (!keyDown || markDb.isNaN() || levelDb > markDb) levelDb else markDb - MARK_DECAY_DB
        val raw = if (keyDown) {
            levelDb > noiseDb + minOf(onDb, HOLD_MIN_DB) &&
                levelDb > markDb - RELEASE_DROP_DB
        } else {
            span >= minSnrDb && levelDb >= minLevelDb &&
                levelDb > noiseDb + onDb && levelDb > peakDb - ON_DROP_DB
        }

        // Смена состояния засчитывается, только если держится два кадра подряд.
        if (raw == keyDown) {
            pendingFrames = 0
        } else if (raw == pending && pendingFrames > 0) {
            pendingFrames++
        } else {
            pending = raw
            pendingFrames = 1
        }
        if (pendingFrames >= DEBOUNCE_FRAMES) {
            keyDown = raw
            pendingFrames = 0
        }

        // Страховка: если «тон» держится слишком долго, это выросший шум, а не посылка.
        downFrames = if (keyDown) downFrames + 1 else 0
        if (downFrames > MAX_DOWN_FRAMES) {
            noiseDb = levelDb
            peakDb = levelDb
            keyDown = false
            downFrames = 0
        }
        return keyDown
    }

    companion object {
        const val DEBOUNCE_FRAMES = 2
        private const val ON_FRACTION = 0.5
        private const val RELEASE_DROP_DB = 12.0
        private const val ON_DROP_DB = 9.0
        private const val MARK_DECAY_DB = 0.3
        private const val HOLD_MIN_DB = 11.0
        private const val MAX_DOWN_FRAMES = 400

        fun toDb(power: Double): Double = 10.0 * log10(power + 1e-12)
    }
}

