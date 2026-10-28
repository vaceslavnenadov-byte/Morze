package com.morze.app.dsp

/**
 * Полный конвейер: PCM-отсчёты → частота тона → уровень на этой частоте →
 * состояние ключа → точки/тире → текст. Не зависит от Android и вызывается
 * из потока записи звука.
 */
class MorseAudioProcessor(
    val sampleRate: Int,
    listener: MorseTimingDecoder.Listener,
    initialWpm: Double = 20.0,
) {
    private val frameSize = sampleRate * FRAME_MS / 1000
    private val frame = FloatArray(frameSize)
    private var frameFill = 0
    private val pendingFrames = ArrayDeque<FloatArray>()

    private val tracker = FrequencyTracker(sampleRate)
    val keyDetector = KeyDetector()
    val decoder = MorseTimingDecoder(initialWpm = initialWpm, listener = listener)

    /** Ручная частота тона, Гц; 0 — определять автоматически. */
    @Volatile
    var manualFrequency = 0.0

    var minSnrDb: Double
        get() = keyDetector.minSnrDb
        set(value) {
            keyDetector.minSnrDb = value
        }

    var minLevelDb: Double
        get() = keyDetector.minLevelDb
        set(value) {
            keyDetector.minLevelDb = value
        }

    /** Фиксированная скорость, групп в минуту; 0 — автоопределение. */
    var fixedWpm: Double
        get() = decoder.fixedWpm
        set(value) {
            decoder.fixedWpm = value
        }

    var alphabet: Alphabet
        get() = decoder.alphabet
        set(value) {
            decoder.alphabet = value
        }

    /** Частота, на которой сейчас идёт приём, Гц. */
    val frequency: Double
        get() = if (manualFrequency > 0) manualFrequency else tracker.frequency

    /** Уровень тона в последнем кадре, дБ. */
    var levelDb = Double.NaN
        private set

    /** Сбрасывает состояние; [initialWpm] — скорость, с которой начинается автоподстройка. */
    fun reset(initialWpm: Double = decoder.wpm) {
        frameFill = 0
        pendingFrames.clear()
        tracker.reset()
        keyDetector.reset()
        decoder.reset(initialWpm)
        levelDb = Double.NaN
    }

    fun process(pcm: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            frame[frameFill++] = pcm[i++] / 32768f
            if (frameFill == frameSize) {
                processFrame()
                frameFill = 0
            }
        }
    }

    fun flush() = decoder.flush()

    private fun processFrame() {
        if (manualFrequency <= 0) tracker.process(frame, 0, frameSize)
        val freq = frequency
        if (freq <= 0) {
            // Тон ещё не найден: запоминаем кадры, чтобы потом не потерять начало передачи.
            pendingFrames.addLast(frame.copyOf())
            if (pendingFrames.size > PENDING_FRAMES) pendingFrames.removeFirst()
            return
        }
        while (pendingFrames.isNotEmpty()) decodeFrame(pendingFrames.removeFirst(), freq)
        decodeFrame(frame, freq)
    }

    private fun decodeFrame(samples: FloatArray, freq: Double) {
        levelDb = KeyDetector.toDb(goertzelPower(samples, 0, frameSize, freq, sampleRate))
        decoder.process(keyDetector.process(levelDb), FRAME_MS.toDouble())
    }

    companion object {
        const val FRAME_MS = 10
        private const val PENDING_FRAMES = 40
    }
}
