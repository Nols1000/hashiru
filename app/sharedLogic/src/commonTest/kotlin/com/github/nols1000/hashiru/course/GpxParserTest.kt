package com.github.nols1000.hashiru.course

import com.github.nols1000.hashiru.course.codec.GpxParser
import com.github.nols1000.hashiru.course.domain.CoursePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxParserTest {
    @Test
    fun aTrackJoinsItsSegmentsInOrder() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Strava" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><time>2026-10-30T07:00:00Z</time></metadata>
              <trk>
                <name>Morning &amp; Evening Run</name>
                <trkseg>
                  <trkpt lat="35.6852" lon="139.7101"><ele>40.2</ele><time>2026-10-30T07:00:00Z</time></trkpt>
                  <trkpt lat="35.6853" lon="139.7102"><ele>40.6</ele></trkpt>
                </trkseg>
                <!-- a pause <trkpt lat="0" lon="0"/> -->
                <trkseg><trkpt lat="35.6854" lon="139.7103"/></trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val course = GpxParser.parse(gpx)!!
        assertEquals("Morning & Evening Run", course.name)
        assertEquals(
            listOf(CoursePoint(35.6852, 139.7101, 40.2), CoursePoint(35.6853, 139.7102, 40.6), CoursePoint(35.6854, 139.7103)),
            course.points,
        )
    }

    @Test
    fun aRouteServesWhereThereIsNoTrack() {
        val gpx = """<gpx><wpt lat="1" lon="1"><name>Cafe</name></wpt><rte><name><![CDATA[Canal <path>]]></name>""" +
            """<rtept lat='52.37' lon='4.89'/><rtept lat='52.38' lon='4.90'></rtept></rte></gpx>"""

        val course = GpxParser.parse(gpx)!!
        assertEquals("Canal <path>", course.name)
        assertEquals(listOf(CoursePoint(52.37, 4.89), CoursePoint(52.38, 4.90)), course.points)
    }

    @Test
    fun prefixedTagsAndOddValuesAreHandled() {
        val gpx = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/0"><g:trk><g:trkseg>""" +
            """<g:trkpt lon=" 4.89 " lat="52.37"><g:ele>n/a</g:ele></g:trkpt><g:trkpt lat="95" lon="4.9"/>""" +
            """</g:trkseg></g:trk></g:gpx>"""

        val course = GpxParser.parse(gpx)!!
        assertNull(course.name)
        assertEquals(listOf(CoursePoint(52.37, 4.89)), course.points)
    }

    @Test
    fun otherXmlIsNotGpx() {
        assertNull(GpxParser.parse("""<kml><Placemark/></kml>"""))
        assertTrue(GpxParser.parse("<gpx version=\"1.1\"></gpx>")!!.points.isEmpty())
    }
}
