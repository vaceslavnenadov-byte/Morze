package com.morze.app.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Превращает последовательность «нажато/отпущено» в точки, тире, буквы и пробелы.
 *
 * Скорость передачи определяется автоматически: длительности последних посылок
 * делятся на два кластера (точки и тире) по логарифму длительности, порог между
 * ними — среднее геометрическое центров кластеров.
 */
class MorseTimingDecoder(
    initialWpm: Double = 20.0,
    @Volatile var alphabet: Alphabet = Alphabet.CYRILLIC,
    private val listener: Listener,
) {
    interface Listener {
        /** Код текущего (ещё не завершённого) символа изменился. */
        fun onCodeChanged(code: String)

        /** Распознан символ; [text] — "*" для неизвестного кода. */
        fun onCharacter(code: String, text: String)

        /** Пауза между словами. */
        fun onWordSpace()

        /** Принят код ошибки — стоит стереть последнее слово. */
        fun onErrorCode() {}
    }

    /** Длительность точки, мс. */
    var unitMs: Double = 1200.0 / initialWpm
        private set

    val wpm: Double get() = 1200.0 / unitMs

    private var dotDashThresholdMs = 2.0 * unitMs
    private val recentMarks = ArrayDeque<Double>()
    private val code = StringBuilder()

    private var keyDown = false
    private var runMs = 0.0
    private var charEmitted = true
    private var wordEmitted = true

    /** Подаёт очередной кадр длительностью [frameMs] с состоянием ключа [down]. */
    fun process(down: Boolean, frameMs: Double) {
        if (down != keyDown) {
            // Пауза внутри знака ничего не завершает; конец знака ловит checkSilence().
            if (keyDown) onMarkEnd(runMs)
            keyDown = down
            runMs = 0.0
        }
        runMs += frameMs
        if (!keyDown) checkSilence()
    }

    /** Принудительно завершает текущий символ (например, при остановке записи). */
    fun flush() {
        if (keyDown) {
            onMarkEnd(runMs)
            keyDown = false
            runMs = 0.0
        }
        emitCharacter()
    }

    fun reset(initialWpm: Double = wpm) {
        unitMs = 1200.0 / initialWpm
        dotDashThresholdMs = 2.0 * unitMs
        recentMarks.clear()
        code.clear()
        keyDown = false
        runMs = 0.0
        charEmitted = true
        wordEmitted = true
        listener.onCodeChanged("")
    }

    private fun onMarkEnd(durationMs: Double) {
        // Очень длинный тон — это настройка/несущая, а не элемент знака;
        // слишком короткий — щелчок или всплеск шума.
        if (durationMs > MAX_MARK_MS) return
        if (durationMs < minOf(GLITCH_UNITS * unitMs, GLITCH_MAX_MS)) return
        learn(durationMs)
        code.append(if (durationMs < dotDashThresholdMs) '.' else '-')
        if (code.length > MAX_CODE_LENGTH) code.clear()
        charEmitted = false
        wordEmitted = false
        listener.onCodeChanged(code.toString())
    }

    private fun checkSilence() {
        if (!charEmitted && runMs >= CHAR_GAP_UNITS * unitMs) emitCharacter()
        if (!wordEmitted && charEmitted && runMs >= WORD_GAP_UNITS * unitMs) {
            wordEmitted = true
            listener.onWordSpace()
        }
    }

    private fun emitCharacter() {
        charEmitted = true
        if (code.isEmpty()) return
        val c = code.toString()
        code.clear()
        listener.onCodeChanged("")
        if (c == MorseCode.ERROR_CODE) {
            listener.onErrorCode()
            return
        }
        listener.onCharacter(c, MorseCode.decode(c, alphabet) ?: UNKNOWN)
    }

    private fun learn(durationMs: Double) {
        recentMarks.addLast(durationMs)
        if (recentMarks.size > HISTORY_SIZE) recentMarks.removeFirst()
        if (recentMarks.size < 4) return

        val logs = recentMarks.map { ln(it) }.sorted()
        var bestSplit = -1
        var bestCost = Double.MAX_VALUE
        for (split in 1 until logs.size) {
            val cost = variance(logs, 0, split) * split +
                variance(logs, split, logs.size) * (logs.size - split)
            if (cost < bestCost) {
                bestCost = cost
                bestSplit = split
            }
        }
        val dotMs = exp(logs.subList(0, bestSplit).average())
        val dashMs = exp(logs.subList(bestSplit, logs.size).average())

        if (dashMs / dotMs >= MIN_DASH_DOT_RATIO) {
            // В истории есть и точки, и тире.
            dotDashThresholdMs = sqrt(dotMs * dashMs)
            unitMs = (dotMs + dashMs / 3.0) / 2.0
        } else {
            // Все посылки одного типа: решаем, точки это или тире, по прежнему порогу.
            val mean = exp(logs.average())
            unitMs = if (mean < dotDashThresholdMs) mean else mean / 3.0
            dotDashThresholdMs = 2.0 * unitMs
        }
    }

    private fun variance(values: List<Double>, from: Int, to: Int): Double {
        val n = to - from
        if (n <= 1) return 0.0
        var mean = 0.0
        for (i in from until to) mean += values[i]
        mean /= n
        var sum = 0.0
        for (i in from until to) sum += (values[i] - mean) * (values[i] - mean)
        return sum / n
    }

    companion object {
        const val UNKNOWN = "*"
        private const val HISTORY_SIZE = 24
        private const val MIN_DASH_DOT_RATIO = 2.0
        private const val CHAR_GAP_UNITS = 2.0
        private const val WORD_GAP_UNITS = 5.0
        private const val MAX_MARK_MS = 2000.0
        private const val GLITCH_UNITS = 0.4
        private const val GLITCH_MAX_MS = 25.0
        private const val MAX_CODE_LENGTH = 10
    }
}
