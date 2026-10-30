package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.codec.SessionMessage
import com.github.nols1000.hashiru.workout.codec.SessionMessageCodec
import com.github.nols1000.hashiru.workout.domain.HeartRateStats
import com.github.nols1000.hashiru.workout.domain.TrackedTotals
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The reference strings are the watch app's too (`WatchCodec.swift`); a change here needs one there. */
class SessionMessageCodecTest {
    private val examples = listOf(
        "S|1|walk-1|1700000000000|Walking|Indoor|Pad" to SessionMessage.Start("walk-1", 1_700_000_000_000, WorkoutKind.IndoorWalk, WorkoutSource.Pad),
        "N|1|1700000060000|80|140" to SessionMessage.Counted(1_700_000_060_000, 80, 140),
        "E|1|1700000600000|walk-1|1700000700000" to SessionMessage.End(1_700_000_600_000, "walk-1", 1_700_000_700_000),
        "D|1|" to SessionMessage.Discard,
        "T|1|600|480|840|352|611|112|105|131" to SessionMessage.Totals(
            TrackedTotals(600, 480, 840, activeKcal = 35.2, totalKcal = 61.1, heartRate = HeartRateStats(112, 105, 131)),
        ),
        "T|1|600|||||||" to SessionMessage.Totals(TrackedTotals(600)),
        "R|1|0|Not authorized" to SessionMessage.Result(false, "Not authorized"),
    )

    @Test
    fun messagesMatchTheReferenceStrings() {
        for ((text, message) in examples) {
            assertEquals(text, SessionMessageCodec.encode(message).decodeToString())
            assertEquals(message, SessionMessageCodec.decode(text.encodeToByteArray()))
        }
    }

    @Test
    fun unknownInputDecodesToNull() {
        assertNull(SessionMessageCodec.decode("T|2|600".encodeToByteArray()))
        assertNull(SessionMessageCodec.decode("N|1|x|1|2".encodeToByteArray()))
        assertNull(SessionMessageCodec.decode("Q|1|".encodeToByteArray()))
    }
}
