package com.github.nols1000.hashiru.course

import com.github.nols1000.hashiru.course.codec.FitParser
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FitParserTest {
    private fun parsed(bytes: ByteArray) = assertIs<FitParser.Result.Parsed>(FitParser.parse(bytes)).course

    @Test
    fun aCourseFileGivesItsNameAndPositions() {
        val fit = FitWriter().apply {
            defineCourse()
            course("Park loop")
            defineRecord()
            record(35.6852, 139.7101, 40.0)
            record(35.6862, 139.7111, 42.4)
        }.build()

        val course = parsed(fit)
        assertEquals("Park loop", course.name)
        assertEquals(2, course.points.size)
        assertEquals(35.6852, course.points[0].lat, 1e-6)
        assertEquals(139.7111, course.points[1].lon, 1e-6)
        assertEquals(42.4, course.points[1].ele!!, 0.2)
    }

    @Test
    fun anActivityWithCompressedTimestampsAndDeveloperFieldsReads() {
        val fit = FitWriter().apply {
            defineRecord(local = 2, developerBytes = 3)
            record(-33.8688, 151.2093, null, local = 2, developerBytes = 3)
            record(-33.8698, 151.2103, null, local = 2, developerBytes = 3, compressed = true)
        }.build()

        val course = parsed(fit)
        assertNull(course.name)
        assertEquals(listOf(-33.8688, -33.8698), course.points.map { round(it.lat * 1e4) / 1e4 })
        assertNull(course.points[0].ele)
    }

    @Test
    fun aBigEndianDefinitionReads() {
        val fit = FitWriter(bigEndian = true).apply {
            defineRecord()
            record(51.5007, -0.1246, 12.0)
            record(51.5017, -0.1256, 13.0)
        }.build()

        assertEquals(-0.1256, parsed(fit).points[1].lon, 1e-6)
    }

    @Test
    fun aBrokenChecksumIsCorrupt() {
        val fit = FitWriter().apply {
            defineRecord()
            record(51.5007, -0.1246, 12.0)
        }.build()
        fit[20] = (fit[20] + 1).toByte()

        assertEquals(FitParser.Result.Corrupt, FitParser.parse(fit))
    }

    @Test
    fun otherBytesAreNotFit() {
        assertEquals(FitParser.Result.NotFit, FitParser.parse("<gpx></gpx>".encodeToByteArray()))
    }
}
