package com.morze.app.dsp

/** Алфавит, в котором интерпретируются коды Морзе. */
enum class Alphabet { LATIN, CYRILLIC }

/** Таблицы международной и русской азбуки Морзе. */
object MorseCode {

    private val digits = mapOf(
        "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3", "....-" to "4",
        "....." to "5", "-...." to "6", "--..." to "7", "---.." to "8", "----." to "9",
    )

    private val commonPunctuation = mapOf(
        "..--.." to "?", "-..-." to "/", "-...-" to "=", "-....-" to "-",
        "---..." to ":", "-.-.-." to ";", ".--.-." to "@", ".-..-." to "\"",
        ".----." to "'", "-.--." to "(", "-.--.-" to ")", ".-.-." to "+",
    )

    private val latin = mapOf(
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E",
        "..-." to "F", "--." to "G", "...." to "H", ".." to "I", ".---" to "J",
        "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O",
        ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
        "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y",
        "--.." to "Z",
        ".-.-.-" to ".", "--..--" to ",", "-.-.--" to "!",
    ) + digits + commonPunctuation

    private val cyrillic = mapOf(
        ".-" to "А", "-..." to "Б", ".--" to "В", "--." to "Г", "-.." to "Д",
        "." to "Е", "...-" to "Ж", "--.." to "З", ".." to "И", ".---" to "Й",
        "-.-" to "К", ".-.." to "Л", "--" to "М", "-." to "Н", "---" to "О",
        ".--." to "П", ".-." to "Р", "..." to "С", "-" to "Т", "..-" to "У",
        "..-." to "Ф", "...." to "Х", "-.-." to "Ц", "---." to "Ч", "----" to "Ш",
        "--.-" to "Щ", "--.--" to "Ъ", "-.--" to "Ы", "-..-" to "Ь", "..-.." to "Э",
        "..--" to "Ю", ".-.-" to "Я",
        "......" to ".", ".-.-.-" to ",", "--..--" to "!",
    ) + digits + commonPunctuation

    /** Код «ошибка» (восемь точек): передающий просит стереть последнее слово. */
    const val ERROR_CODE = "........"

    /** Возвращает символ для кода или null, если код неизвестен. */
    fun decode(code: String, alphabet: Alphabet): String? = when (alphabet) {
        Alphabet.LATIN -> latin[code]
        Alphabet.CYRILLIC -> cyrillic[code]
    }

    /** Обратное преобразование — используется в тестах и для генерации сигналов. */
    fun encode(text: String, alphabet: Alphabet): List<String> {
        val table = when (alphabet) {
            Alphabet.LATIN -> latin
            Alphabet.CYRILLIC -> cyrillic
        }
        val reverse = table.entries.associate { (code, symbol) -> symbol to code }
        return text.uppercase().map { ch ->
            if (ch == ' ') " " else reverse[ch.toString()]
                ?: throw IllegalArgumentException("Нет кода для символа '$ch'")
        }
    }
}
