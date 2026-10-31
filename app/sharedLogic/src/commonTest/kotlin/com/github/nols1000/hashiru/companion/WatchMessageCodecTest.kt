package com.github.nols1000.hashiru.companion

import com.github.nols1000.hashiru.companion.codec.WatchMessage
import com.github.nols1000.hashiru.companion.codec.WatchMessageCodec
import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.domain.WristNavigation
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The messages the watch shares are checked against fixtures/watch-codec-vectors.json, which the watchOS app's
 * copy (`WatchCodec.swift`, tested by iosAppTests) checks too. Heart rate is only the phone's and tested here.
 */
class WatchMessageCodecTest {
    @Test
    fun sharedVectors() {
        val messages = watchCodecVectors["messages"] as List<*>
        assertTrue(messages.isNotEmpty())
        for (vector in messages) {
            vector as Map<*, *>
            val name = vector["name"] as String
            val bytes = (vector["hex"] as String).hexToByteArray()
            val message = message(vector)
            assertContentEquals(bytes, WatchMessageCodec.encode(message), name)
            assertEquals(message, WatchMessageCodec.decode(bytes), name)
        }
    }

    @Test
    fun sharedRejectedVectors() {
        for (hex in watchCodecVectors["rejected"] as List<*>) {
            assertNull(WatchMessageCodec.decode((hex as String).hexToByteArray()), hex)
        }
    }

    @Test
    fun heartRate() {
        val message = WatchMessage.HeartRate(HeartRateSample(1_700_000_000_000, 112))
        assertEquals("H|2|1700000000000|112", WatchMessageCodec.encode(message).decodeToString())
        assertEquals(message, WatchMessageCodec.decode("H|2|1700000000000|112".encodeToByteArray()))
        for (text in listOf("H|2|x|1", "H|2|1700000000000")) {
            assertNull(WatchMessageCodec.decode(text.encodeToByteArray()), text)
        }
    }

    private fun message(vector: Map<*, *>): WatchMessage {
        (vector["telemetry"] as Map<*, *>?)?.let { t ->
            return WatchMessage.Telemetry(
                WristTelemetry(
                    phase = WorkoutPhase.valueOf(t["phase"] as String),
                    isConnected = t["isConnected"] as Boolean,
                    isRunning = t["isRunning"] as Boolean,
                    speedTenths = t["speedTenths"] as Int,
                    durationSeconds = t["durationSeconds"] as Int,
                    distanceMeters = t["distanceMeters"] as Int,
                    heartRateBpm = t["heartRateBpm"] as Int?,
                    zone = (t["zone"] as String?)?.let(HeartRateZone::valueOf),
                    canResume = t["canResume"] as Boolean,
                    navigation = (t["navigation"] as Map<*, *>?)?.let {
                        WristNavigation(
                            onCourse = it["onCourse"] as Boolean,
                            offCourseMeters = it["offCourseMeters"] as Int,
                            remainingMeters = it["remainingMeters"] as Int,
                            joined = it["joined"] as Boolean,
                            finished = it["finished"] as Boolean,
                        )
                    },
                ),
            )
        }
        (vector["command"] as Map<*, *>?)?.let { c ->
            return WatchMessage.Command(
                when (val kind = c["kind"]) {
                    "SetSpeed" -> WristCommand.SetSpeed(c["tenthsKmh"] as Int)
                    "SetPaused" -> WristCommand.SetPaused(c["paused"] as Boolean)
                    "StopBelt" -> WristCommand.StopBelt
                    else -> error("Unknown command $kind")
                },
            )
        }
        val ack = vector["ack"] as Map<*, *>? ?: error("Unknown message ${vector["name"]}")
        return WatchMessage.Ack(ack["ok"] as Boolean)
    }
}
