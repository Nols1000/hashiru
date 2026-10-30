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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The strings here are the ones in the codec's KDoc, which the watchOS app's copy follows. */
class WatchMessageCodecTest {
    private fun assertWire(text: String, message: WatchMessage) {
        assertEquals(text, WatchMessageCodec.encode(message).decodeToString())
        assertEquals(message, WatchMessageCodec.decode(text.encodeToByteArray()))
    }

    @Test
    fun telemetry() {
        assertWire(
            "T|2|Active|1|1|35|754|612|112|Aerobic|0|1|12|4380|1|0",
            WatchMessage.Telemetry(
                WristTelemetry(
                    WorkoutPhase.Active, true, true, 35, 754, 612, 112, HeartRateZone.Aerobic, false,
                    WristNavigation(onCourse = true, offCourseMeters = 12, remainingMeters = 4_380, joined = true, finished = false),
                ),
            ),
        )
        assertWire("T|2|Idle|0|0|0|0|0|||0|||||", WatchMessage.Telemetry(WristTelemetry()))
    }

    @Test
    fun commands() {
        assertWire("C|2|SetSpeed|35", WatchMessage.Command(WristCommand.SetSpeed(35)))
        assertWire("C|2|SetPaused|1", WatchMessage.Command(WristCommand.SetPaused(true)))
        assertWire("C|2|StopBelt|", WatchMessage.Command(WristCommand.StopBelt))
    }

    @Test
    fun heartRateAndAck() {
        assertWire("H|2|1700000000000|112", WatchMessage.HeartRate(HeartRateSample(1_700_000_000_000, 112)))
        assertWire("A|2|1", WatchMessage.Ack(true))
    }

    @Test
    fun unknownInputDecodesToNull() {
        for (text in listOf("", "T|3|Active", "C|2|Jump|1", "H|2|x|1", "T|2|Active|1|1|35", "T|1|Active|1|1|35|754|612|112|Aerobic|0", "A|2|yes")) {
            assertNull(WatchMessageCodec.decode(text.encodeToByteArray()), text)
        }
    }
}
