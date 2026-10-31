package com.github.nols1000.hashiru

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NSDataBytesTest {
    @Test
    fun anEmptyArrayBecomesEmptyData() {
        assertEquals(0uL, ByteArray(0).toNSData().length)
        assertContentEquals(ByteArray(0), ByteArray(0).toNSData().toByteArray())
    }

    @Test
    fun bytesSurviveTheRoundTrip() {
        val bytes = byteArrayOf(0, -1, 127, -128, 42)
        assertContentEquals(bytes, bytes.toNSData().toByteArray())
    }
}
