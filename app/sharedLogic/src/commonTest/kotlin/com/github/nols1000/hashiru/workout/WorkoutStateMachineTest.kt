package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.treadmill.domain.TelemetrySnapshot
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.workout.domain.BodyWeight
import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.DiscardReason
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.EnergyModel
import com.github.nols1000.hashiru.workout.domain.WeightSource
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.engine.WorkoutContext
import com.github.nols1000.hashiru.workout.engine.WorkoutEffect
import com.github.nols1000.hashiru.workout.engine.WorkoutInput
import com.github.nols1000.hashiru.workout.engine.WorkoutRules
import com.github.nols1000.hashiru.workout.engine.WorkoutState
import com.github.nols1000.hashiru.workout.engine.WorkoutStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutStateMachineTest {
    private val start = 1_700_000_000_000L
    private var now = start
    private var state: WorkoutState = WorkoutState.Idle()
    private val effects = mutableListOf<WorkoutEffect>()
    private var profile = UserProfile(ageYears = 40, sex = Sex.Male)

    private fun feed(input: WorkoutInput) {
        val transition = WorkoutStateMachine.reduce(state, input, WorkoutContext(now, BodyWeight(70.0, WeightSource.Manual), profile) { "walk-1" })
        state = transition.state
        effects += transition.effects
    }

    private fun running(c: PadCounters) = WorkoutInput.Telemetry(TelemetrySnapshot(3.0, true, c.seconds, c.meters, c.steps))
    private fun stopped(c: PadCounters) = WorkoutInput.Telemetry(TelemetrySnapshot(0.0, false, c.seconds, c.meters, c.steps))

    /** The pad's counters at 3 km/h after [seconds] of walking: 0.8 m and 1.4 steps per second. */
    private fun at(seconds: Int) = PadCounters(seconds, seconds * 8 / 10, seconds * 14 / 10)

    /** Walks one report per second from pad time [from] to [to], advancing the clock with it. */
    private fun walk(from: Int, to: Int) {
        for (s in from..to) {
            if (s > from) now += 1000
            feed(running(at(s)))
        }
    }

    /** Like [walk], with a heart-rate reading every [everySeconds] seconds. */
    private fun walkWithHeart(from: Int, to: Int, bpm: Int, everySeconds: Int = 1) {
        for (s in from..to) {
            if (s > from) now += 1000
            feed(running(at(s)))
            if ((s - from) % everySeconds == 0) feed(WorkoutInput.HeartRate(HeartRateSample(now, bpm)))
        }
    }

    private val walkState get() = assertIs<WorkoutState.InProgress>(state)
    private val completed get() = effects.filterIsInstance<WorkoutEffect.Completed>().single().record

    @Test
    fun ignoresTelemetryWhileTheBeltIsIdle() {
        feed(stopped(PadCounters.Zero))
        feed(WorkoutInput.Telemetry(TelemetrySnapshot(0.0, true)))

        assertIs<WorkoutState.Idle>(state)
    }

    @Test
    fun startsWhenTheBeltRunsAndBackdatesToThePadsElapsedTime() {
        feed(running(at(30)))

        assertEquals(start - 30_000, walkState.startMs)
        assertEquals(at(30), walkState.totals)
        assertTrue(WorkoutEffect.Checkpoint in effects)
    }

    @Test
    fun closesOneSamplePerMinuteOfWalking() {
        walk(1, 181)

        val samples = walkState.samples
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.endMs - it.startMs == WorkoutRules.WINDOW_MS })
        assertEquals(at(180).meters, samples.sumOf { it.distanceMeters })
        assertEquals(at(180).steps, samples.sumOf { it.steps })
    }

    @Test
    fun beltStopPausesAndARestartAddsASegment() {
        walk(0, 120)
        feed(stopped(at(120)))
        assertEquals(PauseReason.BeltStopped, walkState.pause?.reason)
        val pausedAt = now

        now += 30_000
        // The pad resets its counters when the belt starts again.
        walk(1, 60)
        assertNull(walkState.pause)
        feed(WorkoutInput.UserEnd)
        feed(stopped(at(60)))

        val record = completed
        assertEquals(180, record.activeSeconds)
        assertEquals(at(120).meters + at(60).meters, record.distanceMeters)
        assertEquals(at(120).steps + at(60).steps, record.steps)
        assertEquals(listOf(pausedAt to pausedAt + 30_000), record.pauses.map { it.startMs to it.endMs })
        assertEquals(record.distanceMeters, record.samples.sumOf { it.distanceMeters })
        assertTrue(WorkoutEffect.Send(TreadmillCommand.StopBelt) in effects)
    }

    @Test
    fun endingRecordsTheRunDownAndEndsWhenTheBeltStops() {
        walk(0, 120)
        feed(WorkoutInput.UserEnd)
        assertEquals(listOf(TreadmillCommand.StopBelt), effects.filterIsInstance<WorkoutEffect.Send>().map { it.command })

        // The belt slows down over a few seconds and still reports running.
        walk(121, 123)
        assertIs<WorkoutState.InProgress>(state)
        feed(stopped(at(123)))

        assertEquals(now, completed.endMs)
        assertEquals(at(123).meters, completed.distanceMeters)
        // The run-down does not start another walk.
        feed(stopped(at(123)))
        assertIs<WorkoutState.Idle>(state)
    }

    @Test
    fun endingStopsWaitingForTheBeltAfterTheTimeout() {
        walk(0, 120)
        feed(WorkoutInput.UserEnd)
        val endedAt = now

        now += WorkoutRules.STOP_TIMEOUT_MS - 1000
        feed(WorkoutInput.Tick)
        assertIs<WorkoutState.InProgress>(state)

        now += 1000
        feed(WorkoutInput.Tick)
        assertEquals(endedAt + WorkoutRules.STOP_TIMEOUT_MS, completed.endMs)
    }

    @Test
    fun endingAgainOrLosingTheConnectionEndsWithoutWaiting() {
        walk(0, 120)
        feed(WorkoutInput.UserEnd)
        feed(WorkoutInput.UserEnd)
        assertIs<WorkoutState.Idle>(state)

        effects.clear()
        state = WorkoutState.Idle()
        walk(0, 120)
        feed(WorkoutInput.UserEnd)
        feed(WorkoutInput.ConnectionLost)
        assertIs<WorkoutState.Idle>(state)
        assertEquals(1, effects.filterIsInstance<WorkoutEffect.Completed>().size)
    }

    @Test
    fun aStoppedBeltEndsTheWalkAfterTheTimeoutAtTheMomentItStopped() {
        walk(0, 120)
        feed(stopped(at(120)))
        val stoppedAt = now

        now += WorkoutRules.PAUSE_TIMEOUT_MS - 1000
        feed(WorkoutInput.Tick)
        assertIs<WorkoutState.InProgress>(state)

        now += 1000
        feed(WorkoutInput.Tick)

        assertEquals(stoppedAt, completed.endMs)
        assertTrue(completed.pauses.isEmpty())
        assertEquals(at(120), assertIs<WorkoutState.Idle>(state).lastFinal)
    }

    @Test
    fun aDisconnectIsSeamlessWhenThePadKeptCounting() {
        walk(0, 90)
        feed(WorkoutInput.ConnectionLost)
        assertEquals(PauseReason.Disconnected, walkState.pause?.reason)

        now += 60_000
        feed(running(at(150)))

        assertNull(walkState.pause)
        assertTrue(walkState.pauses.isEmpty())
        assertEquals(at(150), walkState.totals)
    }

    @Test
    fun aDisconnectWithoutCountingBecomesAPause() {
        walk(0, 90)
        feed(WorkoutInput.ConnectionLost)
        val lostAt = now

        now += 120_000
        feed(running(at(1)))

        assertNull(walkState.pause)
        assertEquals(listOf(lostAt to now), walkState.pauses.map { it.startMs to it.endMs })
        assertEquals(at(90) + at(1), walkState.totals)
    }

    @Test
    fun aDisconnectEndsTheWalkOnlyAfterTheLongerTimeout() {
        walk(0, 120)
        feed(WorkoutInput.ConnectionLost)
        val lostAt = now

        now += WorkoutRules.PAUSE_TIMEOUT_MS
        feed(WorkoutInput.Tick)
        assertIs<WorkoutState.InProgress>(state)

        now = lostAt + WorkoutRules.DISCONNECT_TIMEOUT_MS
        feed(WorkoutInput.Tick)

        assertEquals(lostAt, completed.endMs)
        assertEquals(at(120).meters, completed.samples.sumOf { it.distanceMeters })
    }

    @Test
    fun shortWalksAreDiscarded() {
        walk(0, 30)
        feed(WorkoutInput.UserEnd)
        feed(stopped(at(30)))

        assertEquals(listOf(DiscardReason.TooShort), effects.filterIsInstance<WorkoutEffect.Discarded>().map { it.reason })
        assertIs<WorkoutState.Idle>(state)
    }

    @Test
    fun userPauseStopsTheBeltAndUserResumeStartsIt() {
        walk(0, 70)
        feed(WorkoutInput.UserPause)
        assertEquals(PauseReason.UserRequested, walkState.pause?.reason)
        // The belt's own stop report while paused changes nothing.
        feed(stopped(at(70)))
        assertEquals(PauseReason.UserRequested, walkState.pause?.reason)

        feed(WorkoutInput.UserResume)

        assertEquals(
            listOf(TreadmillCommand.StopBelt, TreadmillCommand.StartBelt),
            effects.filterIsInstance<WorkoutEffect.Send>().map { it.command },
        )
    }

    @Test
    fun countersThatContinueAFinishedWalkStartTheNextOneFromThere() {
        walk(0, 120)
        feed(stopped(at(120)))
        now += WorkoutRules.PAUSE_TIMEOUT_MS
        feed(WorkoutInput.Tick)
        effects.clear()

        // The pad was started again from its remote without resetting.
        now += 1000
        feed(running(at(150)))

        assertEquals(at(150) - at(120), walkState.totals)
        assertEquals(now - 30_000, walkState.startMs)
    }

    @Test
    fun heartRateThatCoversTheWindowDrivesItsEnergy() {
        walkWithHeart(1, 60, bpm = 110)

        val sample = walkState.samples.single()
        val expected = assertNotNull(EnergyModel.heartRate(110, 60, 70.0, profile))
        assertEquals(expected.activeKcal, sample.activeKcal, 1e-9)
        assertEquals(expected.totalKcal, sample.totalKcal, 1e-9)
        assertEquals(true, sample.heartRate?.usedForEnergy)
        assertEquals(110, sample.heartRate?.avgBpm)
        assertEquals(12, sample.heartRate?.series?.size)
    }

    @Test
    fun sparseHeartRateKeepsTheWalkingEquation() {
        walkWithHeart(1, 60, bpm = 110, everySeconds = 10)

        val sample = walkState.samples.single()
        assertEquals(EnergyModel.activeKcal(sample.distanceMeters, 70.0), sample.activeKcal, 1e-9)
        assertFalse(assertNotNull(sample.heartRate).usedForEnergy)
    }

    @Test
    fun anUnknownProfileKeepsTheWalkingEquation() {
        profile = UserProfile(ageYears = 40)
        walkWithHeart(1, 60, bpm = 110)

        val sample = walkState.samples.single()
        assertEquals(EnergyModel.activeKcal(sample.distanceMeters, 70.0), sample.activeKcal, 1e-9)
        assertFalse(assertNotNull(sample.heartRate).usedForEnergy)
    }

    @Test
    fun heartRateIsIgnoredWhilePausedAndWhenImplausible() {
        walk(1, 10)
        feed(WorkoutInput.HeartRate(HeartRateSample(now, 250)))
        assertEquals(0, walkState.heart.count)

        feed(WorkoutInput.UserPause)
        feed(WorkoutInput.HeartRate(HeartRateSample(now, 100)))
        assertEquals(0, walkState.heart.count)
    }

    @Test
    fun aRecordsSamplesAddUpToItsTotals() {
        walk(0, 200)
        feed(WorkoutInput.UserEnd)
        feed(stopped(at(200)))

        val record: WorkoutRecord = completed
        assertEquals(record.distanceMeters, record.samples.sumOf { it.distanceMeters })
        assertEquals(record.steps, record.samples.sumOf { it.steps })
        assertEquals(record.activeKcal, record.samples.sumOf { it.activeKcal }, 1e-9)
        assertEquals(4, record.samples.size)
    }

    // Phone workouts

    private val notified get() = effects.filterIsInstance<WorkoutEffect.Notify>().map { it.event }

    /** Starts a phone workout of [kind] and has the health store confirm it. */
    private fun startPhone(kind: WorkoutKind = WorkoutKind.IndoorWalk) {
        feed(WorkoutInput.UserStart(kind))
        feed(WorkoutInput.TrackingStarted("walk-1"))
    }

    /** Reports the phone's counters once a second from [from] to [to] seconds of activity, advancing the clock. */
    private fun count(from: Int, to: Int, metersPerSecond: Int = 1) {
        for (s in from..to) {
            if (s > from) now += 1000
            feed(WorkoutInput.PhoneCounters("walk-1", PadCounters(s, s * metersPerSecond, s * 2)))
        }
    }

    @Test
    fun aPhoneWorkoutWaitsForTheHealthStore() {
        feed(WorkoutInput.UserStart(WorkoutKind.IndoorWalk))

        assertIs<WorkoutState.Idle>(state)
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.StartRequested("walk-1", start, WorkoutKind.IndoorWalk, WorkoutSource.Phone)), notified)

        now += 2_000
        feed(WorkoutInput.TrackingStarted("walk-1"))
        assertEquals(start + 2_000, walkState.startMs)
        assertEquals(WorkoutSource.Phone, walkState.source)
    }

    @Test
    fun aRejectedOrUnansweredStartLeavesItIdle() {
        feed(WorkoutInput.UserStart(WorkoutKind.IndoorWalk))
        feed(WorkoutInput.TrackingRejected("walk-1"))
        assertNull(assertIs<WorkoutState.Idle>(state).pendingStart)

        feed(WorkoutInput.UserStart(WorkoutKind.IndoorWalk))
        now += WorkoutRules.START_TIMEOUT_MS
        feed(WorkoutInput.Tick)
        assertNull(assertIs<WorkoutState.Idle>(state).pendingStart)
        assertEquals(WorkoutEvent.Discarded("walk-1", DiscardReason.UserDiscarded), notified.last())

        // Too late: the store is told to drop it.
        effects.clear()
        feed(WorkoutInput.TrackingStarted("walk-1"))
        assertIs<WorkoutState.Idle>(state)
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.Discarded("walk-1", DiscardReason.UserDiscarded)), notified)
    }

    @Test
    fun aPhoneWorkoutIgnoresThePad() {
        startPhone()
        count(0, 10)
        feed(running(at(500)))
        feed(WorkoutInput.ConnectionLost)

        assertNull(walkState.pause)
        assertEquals(PadCounters(10, 10, 20), walkState.totals)
    }

    @Test
    fun aPhoneWorkoutPausesAndResumesWithoutTheBelt() {
        startPhone()
        count(0, 30)
        feed(WorkoutInput.UserPause)
        now += WorkoutRules.PAUSE_TIMEOUT_MS * 2
        feed(WorkoutInput.Tick)
        assertEquals(PauseReason.UserRequested, walkState.pause?.reason)

        feed(WorkoutInput.UserResume)
        assertNull(walkState.pause)
        assertEquals(1, walkState.pauses.size)
        assertTrue(effects.none { it is WorkoutEffect.Send })
        assertEquals(listOf(WorkoutEvent.Paused::class, WorkoutEvent.Resumed::class), notified.drop(1).map { it::class })
    }

    @Test
    fun lostTrackingPausesAndEndsAfterTheDisconnectTimeout() {
        startPhone()
        count(0, 90)
        feed(WorkoutInput.TrackingLost("walk-1"))
        assertEquals(PauseReason.Disconnected, walkState.pause?.reason)

        now += WorkoutRules.DISCONNECT_TIMEOUT_MS
        feed(WorkoutInput.Tick)
        assertEquals(90, completed.activeSeconds)
        assertEquals(WorkoutSource.Phone, completed.source)
    }

    @Test
    fun trackingThatComesBackContinuesTheWorkout() {
        startPhone()
        count(0, 30)
        feed(WorkoutInput.TrackingLost("walk-1"))
        now += 5_000
        feed(WorkoutInput.PhoneCounters("walk-1", PadCounters(35, 35, 70)))

        assertNull(walkState.pause)
        assertTrue(walkState.pauses.isEmpty())
        assertEquals(PadCounters(35, 35, 70), walkState.totals)
    }

    @Test
    fun aRunUsesTheRunningEnergyModel() {
        startPhone(WorkoutKind.IndoorRun)
        count(0, 120, metersPerSecond = 3)
        feed(WorkoutInput.UserEnd)

        val record = completed
        assertEquals(WorkoutKind.IndoorRun, record.kind)
        assertEquals(EnergyModel.activeKcal(record.distanceMeters, 70.0, Activity.Running), record.activeKcal, 1e-9)
        assertTrue(effects.none { it is WorkoutEffect.Send })
    }

    @Test
    fun aShortPhoneWorkoutIsDiscarded() {
        startPhone()
        count(0, 20)
        feed(WorkoutInput.UserEnd)

        assertEquals(WorkoutEvent.Discarded("walk-1", DiscardReason.TooShort), notified.last())
        assertTrue(effects.none { it is WorkoutEffect.Completed })
    }

    @Test
    fun aPadWalkTellsTheHealthStoreItStarted() {
        feed(running(at(30)))

        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.StartRequested("walk-1", start - 30_000, WorkoutKind.IndoorWalk, WorkoutSource.Pad)), notified)
    }
}
