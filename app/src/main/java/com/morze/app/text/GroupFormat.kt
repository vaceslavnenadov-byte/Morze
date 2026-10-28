package com.morze.app.text

/**
 * Оформление принятого текста группами, как в радиограмме: знаки идут подряд
 * (паузы между словами не учитываются), режутся на группы по [groupSize]
 * и выводятся по [groupsPerLine] групп в строке.
 */
class GroupFormat(
    val groupSize: Int = 5,
    val groupsPerLine: Int = 5,
) {
    /** Число знаков в полной строке вместе с пробелами между группами. */
    val lineLength: Int get() = groupSize * groupsPerLine + groupsPerLine - 1

    fun format(text: CharSequence): String {
        val out = StringBuilder()
        var count = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            if (count > 0) {
                when {
                    count % (groupSize * groupsPerLine) == 0 -> out.append('\n')
                    count % groupSize == 0 -> out.append(' ')
                }
            }
            out.append(ch)
            count++
        }
        return out.toString()
    }

    /**
     * Сколько символов нужно отрезать с конца [text], чтобы убрать последнюю
     * (возможно, неполную) группу вместе с пробелами после неё.
     */
    fun lastGroupLength(text: CharSequence): Int {
        var chars = 0
        for (ch in text) if (!ch.isWhitespace()) chars++
        if (chars == 0) return text.length
        var toRemove = if (chars % groupSize == 0) groupSize else chars % groupSize
        var index = text.length
        while (index > 0 && toRemove > 0) {
            index--
            if (!text[index].isWhitespace()) toRemove--
        }
        return text.length - index
    }
}
