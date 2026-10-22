package com.example.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LectureSourceFormatterTest {

    @Test
    fun blankTranscriptIsReturnedUnchanged() {
        val input = "  \n "
        assertEquals(input, LectureSourceFormatter.withSourceAnchors(input))
    }

    @Test
    fun separateParagraphsReceiveStableSourceAnchors() {
        val input = "مقدمه بحث.\n\nدلیل استاد.\n\nنتیجه بحث."
        val output = LectureSourceFormatter.withSourceAnchors(input)

        assertTrue(output.contains("[[قطعه منبع P001]]"))
        assertTrue(output.contains("[[قطعه منبع P002]]"))
        assertTrue(output.contains("[[قطعه منبع P003]]"))
        assertTrue(output.contains("مقدمه بحث."))
        assertTrue(output.contains("دلیل استاد."))
        assertTrue(output.contains("نتیجه بحث."))
        assertFalse(output.contains("[[قطعه منبع P004]]"))
    }

    @Test
    fun longParagraphIsSplitWithoutDroppingItsBeginningOrEnding() {
        val input = (1..260).joinToString(" ") { "واژه$it" }
        val output = LectureSourceFormatter.withSourceAnchors(input)

        assertTrue(output.contains("[[قطعه منبع P001]]"))
        assertTrue(output.contains("[[قطعه منبع P002]]"))
        assertTrue(output.contains("واژه1"))
        assertTrue(output.contains("واژه260"))
    }
}
