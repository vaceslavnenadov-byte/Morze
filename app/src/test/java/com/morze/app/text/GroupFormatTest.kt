package com.morze.app.text

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupFormatTest {
    private val format = GroupFormat()

    @Test
    fun splitsIntoGroupsAndLines() {
        val received = "ABCDEFGHIJKLMNOPQRSTUVWXY12345"
        assertEquals(
            "ABCDE FGHIJ KLMNO PQRST UVWXY\n12345",
            format.format(received),
        )
    }

    @Test
    fun ignoresReceivedSpaces() {
        assertEquals("ПРИВЕ ТМИР7 3", format.format("ПРИВЕТ МИР 73 "))
        assertEquals("", format.format("   "))
    }

    @Test
    fun lineLengthFitsFiveGroups() {
        assertEquals(29, format.lineLength)
        val line = format.format("X".repeat(25))
        assertEquals(format.lineLength, line.length)
    }

    @Test
    fun lastGroupLength() {
        // 7 знаков: последняя неполная группа — 2 знака.
        assertEquals(2, format.lastGroupLength("ABCDEFG"))
        // 10 знаков: последняя полная группа — 5 знаков, плюс пробел и разрыв слова.
        assertEquals(7, format.lastGroupLength("ABC DEFG HIJ "))
        assertEquals(0, format.lastGroupLength(""))
    }
}
