package com.github.nols1000.hashiru.share.codec

import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.share.domain.ShareMessage
import com.github.nols1000.hashiru.share.domain.ShareNavigation
import com.github.nols1000.hashiru.share.domain.SharePhase
import com.github.nols1000.hashiru.share.domain.ShareState
import com.github.nols1000.hashiru.share.domain.SharedCourse
import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import kotlin.math.roundToLong

/**
 * Compact binary plaintext of a [ShareMessage], before it is sealed into an SFrame. The web viewer has its own
 * copy of this format (`server/src/main/resources/viewer/codec.js`); keep both in step.
 *
 * ```
 * message   version (1) · type (1 snapshot, 2 update) · [snapshot: kind · course] · state · route
 * kind      activity (0 walking, 1 running) · location (0 indoor, 1 outdoor)
 * course    0, or 1 · name · distance m · count · points as zigzag deltas of 1e-7 degrees, lat then lon
 * state     at ms · phase (0 waiting, 1 active, 2 paused, 3 ended) · seconds · metres · steps · bpm or 0 ·
 *           battery % + 1 or 0 · seconds since last moved + 1 or 0 · navigation
 * navigation flags (bit 0 present, 1 on course, 2 finished, 3 joined) · [progress · remaining · off course]
 * route     count · first point absolute, then zigzag deltas: ms, lat, lon (1e-7 degrees)
 * ```
 *
 * Numbers are LEB128 varints. Altitude and accuracy are left out; viewers draw a line, not a profile. The message
 * is padded with zeros to a multiple of [PAD_BYTES], which decoding ignores, so its size gives little away.
 * Decoding returns null for anything it does not understand, including a newer version.
 */
object ShareMessageCodec {
    private const val VERSION = 1
    private const val SNAPSHOT = 1
    private const val UPDATE = 2
    private const val DEGREE_UNITS = 1e7
    const val PAD_BYTES = 256

    fun encode(message: ShareMessage): ByteArray {
        val w = ByteWriter()
        w.byte(VERSION)
        when (message) {
            is ShareMessage.Snapshot -> {
                w.byte(SNAPSHOT)
                w.byte(message.kind.activity.ordinal)
                w.byte(message.kind.location.ordinal)
                val course = message.course
                if (course == null) {
                    w.byte(0)
                } else {
                    w.byte(1)
                    w.text(course.name)
                    w.varint(course.distanceMeters.coerceAtLeast(0))
                    w.varint(course.points.size)
                    var lat = 0L
                    var lon = 0L
                    for (p in course.points) {
                        val pLat = units(p.lat)
                        val pLon = units(p.lon)
                        w.zigzag(pLat - lat)
                        w.zigzag(pLon - lon)
                        lat = pLat
                        lon = pLon
                    }
                }
            }
            is ShareMessage.Update -> w.byte(UPDATE)
        }
        writeState(w, message.state)
        writeRoute(w, message.route)
        val padded = (w.size + PAD_BYTES - 1) / PAD_BYTES * PAD_BYTES
        return w.toByteArray().copyOf(padded)
    }

    fun decode(bytes: ByteArray): ShareMessage? = runCatching {
        val r = ByteReader(bytes)
        if (r.byte() != VERSION) return null
        when (r.byte()) {
            SNAPSHOT -> {
                val kind = WorkoutKind(Activity.entries[r.byte()], Location.entries[r.byte()])
                val course = when (r.byte()) {
                    0 -> null
                    1 -> {
                        val name = r.text()
                        val distance = r.int()
                        var lat = 0L
                        var lon = 0L
                        val points = List(r.int()) {
                            lat += r.zigzag()
                            lon += r.zigzag()
                            CoursePoint(lat / DEGREE_UNITS, lon / DEGREE_UNITS)
                        }
                        SharedCourse(name, distance, points)
                    }
                    else -> return null
                }
                ShareMessage.Snapshot(kind, course, readState(r), readRoute(r))
            }
            UPDATE -> ShareMessage.Update(readState(r), readRoute(r))
            else -> null
        }
    }.getOrNull()

    private fun writeState(w: ByteWriter, s: ShareState) {
        w.varint(s.atMs)
        w.byte(s.phase.ordinal)
        w.varint(s.totals.seconds.coerceAtLeast(0))
        w.varint(s.totals.meters.coerceAtLeast(0))
        w.varint(s.totals.steps.coerceAtLeast(0))
        w.varint(s.heartRateBpm?.coerceAtLeast(1) ?: 0)
        w.varint(s.batteryPercent?.coerceIn(0, 100)?.plus(1) ?: 0)
        w.varint(s.lastMovedMs?.let { ((s.atMs - it) / 1000).coerceAtLeast(0) + 1 } ?: 0)
        val n = s.navigation
        if (n == null) {
            w.byte(0)
        } else {
            w.byte(1 or flag(n.onCourse, 1) or flag(n.finished, 2) or flag(n.joined, 3))
            w.varint(n.progressMeters.coerceAtLeast(0))
            w.varint(n.remainingMeters.coerceAtLeast(0))
            w.varint(n.offCourseMeters.coerceAtLeast(0))
        }
    }

    private fun readState(r: ByteReader): ShareState {
        val atMs = r.varint()
        val phase = SharePhase.entries[r.byte()]
        val totals = PadCounters(r.int(), r.int(), r.int())
        val bpm = r.int().takeIf { it > 0 }
        val battery = r.int().takeIf { it > 0 }?.minus(1)
        val lastMoved = r.varint().takeIf { it > 0 }?.let { atMs - (it - 1) * 1000 }
        val flags = r.byte()
        val navigation = if (flags and 1 == 0) {
            null
        } else {
            ShareNavigation(
                progressMeters = r.int(),
                remainingMeters = r.int(),
                offCourseMeters = r.int(),
                onCourse = flags and (1 shl 1) != 0,
                finished = flags and (1 shl 2) != 0,
                joined = flags and (1 shl 3) != 0,
            )
        }
        return ShareState(atMs, phase, totals, bpm, battery, lastMoved, navigation)
    }

    private fun writeRoute(w: ByteWriter, route: List<RoutePoint>) {
        w.varint(route.size)
        var at = 0L
        var lat = 0L
        var lon = 0L
        for ((i, p) in route.withIndex()) {
            val pLat = units(p.latitude)
            val pLon = units(p.longitude)
            if (i == 0) w.varint(p.atMs) else w.zigzag(p.atMs - at)
            w.zigzag(pLat - lat)
            w.zigzag(pLon - lon)
            at = p.atMs
            lat = pLat
            lon = pLon
        }
    }

    private fun readRoute(r: ByteReader): List<RoutePoint> {
        var at = 0L
        var lat = 0L
        var lon = 0L
        return List(r.int()) { i ->
            at = if (i == 0) r.varint() else at + r.zigzag()
            lat += r.zigzag()
            lon += r.zigzag()
            RoutePoint(at, lat / DEGREE_UNITS, lon / DEGREE_UNITS)
        }
    }

    private fun units(degrees: Double): Long = (degrees * DEGREE_UNITS).roundToLong()

    private fun flag(value: Boolean, bit: Int) = if (value) 1 shl bit else 0
}
