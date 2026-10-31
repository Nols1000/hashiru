package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.codec.WorkoutCodec
import com.github.nols1000.hashiru.workout.domain.BodyWeight
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateStats
import com.github.nols1000.hashiru.workout.domain.HeartRateSummary
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WeightSource
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutPause
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSample
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.engine.ActivePause
import com.github.nols1000.hashiru.workout.engine.HeartAccumulator
import com.github.nols1000.hashiru.workout.engine.WorkoutState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutCodecTest {
    private val samples = listOf(
        WorkoutSample(1_000, 61_000, 48, 84, 1.68, 2.905),
        WorkoutSample(
            61_000, 90_500, 24, 41, 0.84, 1.4525,
            HeartRateSummary(104, 98, 112, usedForEnergy = true, series = listOf(HeartRateSample(61_500, 98), HeartRateSample(66_500, 112))),
        ),
    )

    private val record = WorkoutRecord(
        id = "6f1c2c1e-4c84-4f5e-9d0a-3f8a1d9b7e21",
        startMs = 1_000,
        endMs = 120_000,
        activeSeconds = 89,
        distanceMeters = 72,
        steps = 125,
        activeKcal = 2.52,
        totalKcal = 4.3575,
        weight = BodyWeight(72.5, WeightSource.Health),
        pauses = listOf(WorkoutPause(90_500, 100_000)),
        samples = samples,
        syncState = SyncState.Failed("Network | down; retry, later"),
    )

    @Test
    fun recordsRoundTrip() {
        val decoded = WorkoutCodec.decodeRecord(WorkoutCodec.encodeRecord(record))

        assertEquals(record.copy(syncState = SyncState.Failed("Network  down retry later")), decoded)
    }

    @Test
    fun aWalkTheStoreCouldNotDeleteRoundTrips() {
        val kept = record.copy(syncState = SyncState.DeleteFailed("Store | busy; later"))

        assertEquals(kept.copy(syncState = SyncState.DeleteFailed("Store  busy later")), WorkoutCodec.decodeRecord(WorkoutCodec.encodeRecord(kept)))
    }

    @Test
    fun recordsSavedBeforeDeleteFailedStillDecode() {
        // Records written before the state existed carry only the names it sits beside; each reads as it did.
        val failed = WorkoutCodec.encodeRecord(record.copy(syncState = SyncState.Failed("Rate limited")))
        val saved = WorkoutCodec.encodeRecord(record.copy(syncState = SyncState.Saved)).replaceFirst("7|", "4|").removeSuffix("||")

        assertEquals(SyncState.Failed("Rate limited"), WorkoutCodec.decodeRecord(failed)?.syncState)
        assertEquals(SyncState.Saved, WorkoutCodec.decodeRecord(saved)?.syncState)
    }

    @Test
    fun recordListsRoundTrip() {
        val records = listOf(record.copy(syncState = SyncState.Saved), record.copy(id = "b", pauses = emptyList(), samples = emptyList(), syncState = SyncState.Pending), record.copy(id = "c", syncState = SyncState.Busy))

        assertEquals(records, WorkoutCodec.decodeRecords(WorkoutCodec.encodeRecords(records)))
    }

    @Test
    fun walksInProgressRoundTrip() {
        val state = WorkoutState.InProgress(
            id = "walk",
            startMs = 1_000,
            weight = BodyWeight.Default,
            closed = PadCounters(60, 50, 84),
            segmentBase = PadCounters(5, 4, 7),
            segmentLast = PadCounters(30, 25, 42),
            windowStartMs = 61_000,
            windowStartTotals = PadCounters(60, 50, 84),
            samples = samples,
            pauses = listOf(WorkoutPause(90_500, 100_000)),
            pause = ActivePause(PauseReason.Disconnected, 110_000),
            profile = UserProfile(41, Sex.Female),
            heart = HeartAccumulator(312, 3, 101, 107, 3_000, 108_000, listOf(HeartRateSample(106_000, 101))),
        )

        assertEquals(state, WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(state)))
        assertEquals(state.copy(pause = null), WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(state.copy(pause = null))))
        val ending = state.copy(pause = null, endingSinceMs = 120_000)
        assertEquals(ending, WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(ending)))
        val empty = state.copy(profile = UserProfile(), heart = HeartAccumulator())
        assertEquals(empty, WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(empty)))
    }

    @Test
    fun theKindAndSourceRoundTrip() {
        val run = record.copy(syncState = SyncState.Saved, kind = WorkoutKind.OutdoorRun, source = WorkoutSource.Phone)
        assertEquals(run, WorkoutCodec.decodeRecord(WorkoutCodec.encodeRecord(run)))

        val state = WorkoutState.InProgress(
            id = "run", startMs = 1_000, weight = BodyWeight.Default, closed = PadCounters.Zero, segmentBase = PadCounters.Zero,
            segmentLast = PadCounters(30, 80, 70), windowStartMs = 1_000, windowStartTotals = PadCounters.Zero, samples = emptyList(),
            pauses = emptyList(), pause = null, kind = WorkoutKind.IndoorRun, source = WorkoutSource.Phone,
        )
        assertEquals(state, WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(state)))
    }

    @Test
    fun theStoresHeartRateRoundTrips() {
        val measured = record.copy(syncState = SyncState.Saved, heartRate = HeartRateStats(latestBpm = null, avgBpm = 104, maxBpm = 131))

        assertEquals(measured, WorkoutCodec.decodeRecord(WorkoutCodec.encodeRecord(measured)))
    }

    @Test
    fun versionFourRecordsStillDecode() {
        val v4 = WorkoutCodec.encodeRecord(record.copy(syncState = SyncState.Saved)).replaceFirst("7|", "4|").removeSuffix("||")

        assertEquals(record.copy(syncState = SyncState.Saved), WorkoutCodec.decodeRecord(v4))
    }

    private val route = listOf(
        RoutePoint(1_000, 35.6852, 139.7101, 40.5, 4.0),
        RoutePoint(2_000, 35.68525, 139.71013, null, 12.5),
    )

    @Test
    fun theRouteRoundTrips() {
        val run = record.copy(syncState = SyncState.Saved, kind = WorkoutKind.OutdoorRun, source = WorkoutSource.Phone, route = route)
        assertEquals(run, WorkoutCodec.decodeRecord(WorkoutCodec.encodeRecord(run)))

        val state = WorkoutState.InProgress(
            id = "run", startMs = 1_000, weight = BodyWeight.Default, closed = PadCounters.Zero, segmentBase = PadCounters.Zero,
            segmentLast = PadCounters(30, 80, 70), windowStartMs = 1_000, windowStartTotals = PadCounters.Zero, samples = emptyList(),
            pauses = emptyList(), pause = null, kind = WorkoutKind.OutdoorRun, source = WorkoutSource.Phone, route = route,
        )
        assertEquals(state, WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(state)))
    }

    @Test
    fun versionFiveTextDecodesWithoutARoute() {
        val saved = record.copy(syncState = SyncState.Saved)
        val v5 = WorkoutCodec.encodeRecord(saved).replaceFirst("7|", "5|").removeSuffix("|")
        assertEquals(saved, WorkoutCodec.decodeRecord(v5))

        val state = WorkoutState.InProgress(
            id = "walk", startMs = 1_000, weight = BodyWeight.Default, closed = PadCounters.Zero, segmentBase = PadCounters.Zero,
            segmentLast = PadCounters(30, 80, 70), windowStartMs = 1_000, windowStartTotals = PadCounters.Zero, samples = emptyList(),
            pauses = emptyList(), pause = null,
        )
        val v5InProgress = WorkoutCodec.encodeInProgress(state).replaceFirst("7|", "5|").removeSuffix("||")
        assertEquals(state, WorkoutCodec.decodeInProgress(v5InProgress))
    }

    @Test
    fun theCourseOfAWalkInProgressRoundTrips() {
        val state = WorkoutState.InProgress(
            id = "run", startMs = 1_000, weight = BodyWeight.Default, closed = PadCounters.Zero, segmentBase = PadCounters.Zero,
            segmentLast = PadCounters(30, 80, 70), windowStartMs = 1_000, windowStartTotals = PadCounters.Zero, samples = emptyList(),
            pauses = emptyList(), pause = null, kind = WorkoutKind.OutdoorRun, source = WorkoutSource.Phone, route = route, courseId = "course-1",
        )
        assertEquals(state, WorkoutCodec.decodeInProgress(WorkoutCodec.encodeInProgress(state)))

        val v6 = WorkoutCodec.encodeInProgress(state).replaceFirst("7|", "6|").removeSuffix("|course-1")
        assertEquals(state.copy(courseId = null), WorkoutCodec.decodeInProgress(v6))
    }

    @Test
    fun versionTwoTextDecodesAsAnIndoorWalkOnThePad() {
        val record = WorkoutCodec.decodeRecord("2|a|1000|120000|89|72|125|2.52|4.3575|72.5|Health|Saved||90500,100000|1000,61000,48,84,1.68,2.905")
        assertEquals(WorkoutKind.IndoorWalk, record?.kind)
        assertEquals(WorkoutSource.Pad, record?.source)

        val walk = WorkoutCodec.decodeInProgress("2|walk|1000|70.0|Default|60,50,84|5,4,7|30,25,42|61000|60,50,84|||90500,100000||41|Female|0,0,0,0,0,|")
        assertEquals(WorkoutKind.IndoorWalk, walk?.kind)
        assertEquals(WorkoutSource.Pad, walk?.source)
    }

    @Test
    fun versionOneTextStillDecodes() {
        val record = WorkoutCodec.decodeRecord("1|a|1000|120000|89|72|125|2.52|4.3575|72.5|Health|Saved||90500,100000|1000,61000,48,84,1.68,2.905")
        assertEquals(listOf(WorkoutSample(1_000, 61_000, 48, 84, 1.68, 2.905)), record?.samples)

        val walk = WorkoutCodec.decodeInProgress("1|walk|1000|70.0|Default|60,50,84|5,4,7|30,25,42|61000|60,50,84|||90500,100000|")
        assertEquals(UserProfile(), walk?.profile)
        assertEquals(HeartAccumulator(), walk?.heart)
    }

    @Test
    fun unknownInputDecodesToNull() {
        assertNull(WorkoutCodec.decodeRecord("9|future|format"))
        assertNull(WorkoutCodec.decodeInProgress("garbage"))
    }
}
