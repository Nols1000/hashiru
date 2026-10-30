package com.github.nols1000.hashiru.workout.engine

import com.github.nols1000.hashiru.treadmill.domain.TelemetrySnapshot
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.workout.domain.BodyWeight
import com.github.nols1000.hashiru.workout.domain.DiscardReason
import com.github.nols1000.hashiru.workout.domain.EnergyModel
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateSummary
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutPause
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSample
import com.github.nols1000.hashiru.workout.domain.WorkoutSource

object WorkoutRules {
    /** Length of one health sample. Short windows let the health store pick one step source per minute. */
    const val WINDOW_MS = 60_000L

    /** A phone workout that the health store has not confirmed by then is not started. */
    const val START_TIMEOUT_MS = 10_000L

    /** A belt that stays stopped this long ends the walk. */
    const val PAUSE_TIMEOUT_MS = 5 * 60_000L

    /** A walk the user ended while the belt ran ends anyway if the belt has not stopped by then. */
    const val STOP_TIMEOUT_MS = 30_000L

    /** A lost connection ends the walk after this long; it is longer because the pad may still be running. */
    const val DISCONNECT_TIMEOUT_MS = 10 * 60_000L

    /** Shorter walks are dropped instead of saved. */
    const val MIN_ACTIVE_SECONDS = 60
    const val MIN_DISTANCE_METERS = 50

    /** Heart rates outside this range are sensor glitches. */
    const val MIN_HEART_RATE_BPM = 30
    const val MAX_HEART_RATE_BPM = 230

    /** A heart-rate reading vouches for at most this long before it; longer gaps count as dropouts. */
    const val HEART_RATE_MAX_GAP_MS = 5_000L

    /** Share of a window that heart rate must cover for the window's energy to come from it. */
    const val HEART_RATE_MIN_COVERAGE = 0.7
}

sealed interface WorkoutInput {
    /** A pad status report; only fed while connected, so a stale snapshot from before a disconnect never arrives. */
    data class Telemetry(val snapshot: TelemetrySnapshot) : WorkoutInput
    data object ConnectionLost : WorkoutInput

    /** The user starts a workout that the phone tracks. */
    data class UserStart(val kind: WorkoutKind) : WorkoutInput

    /** The health store tracks the workout requested as [id]. */
    data class TrackingStarted(val id: String) : WorkoutInput
    data class TrackingRejected(val id: String) : WorkoutInput

    /** The phone's counters for the workout [id], without pauses. */
    data class PhoneCounters(val id: String, val counters: PadCounters) : WorkoutInput
    data class TrackingLost(val id: String) : WorkoutInput
    data object Tick : WorkoutInput

    /** A reading from the watch; counted while walking only. */
    data class HeartRate(val sample: HeartRateSample) : WorkoutInput
    data object UserPause : WorkoutInput
    data object UserResume : WorkoutInput
    data object UserEnd : WorkoutInput
    data object UserDiscard : WorkoutInput
}

sealed interface WorkoutEffect {
    data class Send(val command: TreadmillCommand) : WorkoutEffect

    /** Something the health store should know about. */
    data class Notify(val event: WorkoutEvent) : WorkoutEffect

    /** The state changed in a way worth surviving process death. */
    data object Checkpoint : WorkoutEffect
    data class Completed(val record: WorkoutRecord) : WorkoutEffect
    data class Discarded(val reason: DiscardReason) : WorkoutEffect
}

data class ActivePause(val reason: PauseReason, val sinceMs: Long)

/** Heart rate collected in the current window. [coveredMs] is how much of the window the readings vouch for. */
data class HeartAccumulator(
    val sumBpm: Long = 0,
    val count: Int = 0,
    val minBpm: Int = 0,
    val maxBpm: Int = 0,
    val coveredMs: Long = 0,
    /** Time of the last reading, or null before the first one in this window. */
    val lastMs: Long? = null,
    val series: List<HeartRateSample> = emptyList(),
) {
    val avgBpm: Int get() = if (count == 0) 0 else (sumBpm / count).toInt()

    /** Adds [sample], which vouches for the time since the previous reading or the window start [sinceMs]. */
    fun add(sample: HeartRateSample, sinceMs: Long): HeartAccumulator {
        val gap = (sample.epochMs - (lastMs ?: sinceMs)).coerceIn(0, WorkoutRules.HEART_RATE_MAX_GAP_MS)
        val keep = series.isEmpty() || sample.epochMs - series.last().epochMs >= HeartRateSummary.SERIES_INTERVAL_MS
        return HeartAccumulator(
            sumBpm = sumBpm + sample.bpm,
            count = count + 1,
            minBpm = if (count == 0) sample.bpm else minOf(minBpm, sample.bpm),
            maxBpm = if (count == 0) sample.bpm else maxOf(maxBpm, sample.bpm),
            coveredMs = coveredMs + gap,
            lastMs = maxOf(sample.epochMs, lastMs ?: Long.MIN_VALUE),
            series = if (keep) series + sample else series,
        )
    }
}

/** A phone workout asked of the health store at [requestedMs], waiting for it to start tracking. */
data class PendingStart(val id: String, val kind: WorkoutKind, val requestedMs: Long)

sealed interface WorkoutState {
    /** [lastFinal] holds the pad counters at the end of the previous pad walk, see [InProgress.segmentBase]. */
    data class Idle(val lastFinal: PadCounters? = null, val pendingStart: PendingStart? = null) : WorkoutState

    /**
     * A workout being recorded. Its [source] is the source of truth for time, distance and steps. The pad resets
     * its counters when the belt restarts, so the workout is a sum of segments: [closed] plus the current
     * segment's [segmentLast] minus [segmentBase]. The phone's counters only grow, so its workouts have a
     * single segment. Times are epoch milliseconds.
     */
    data class InProgress(
        val id: String,
        val startMs: Long,
        val weight: BodyWeight,
        /** Age and sex at the start of the walk, for heart-rate energy. */
        val profile: UserProfile = UserProfile(),
        val closed: PadCounters,
        /** Raw counters that count as zero in the current segment: those of an earlier walk the pad kept counting. */
        val segmentBase: PadCounters,
        val segmentLast: PadCounters,
        val windowStartMs: Long,
        val windowStartTotals: PadCounters,
        val samples: List<WorkoutSample>,
        val pauses: List<WorkoutPause>,
        /** Null while walking. */
        val pause: ActivePause?,
        val heart: HeartAccumulator = HeartAccumulator(),
        val kind: WorkoutKind = WorkoutKind.IndoorWalk,
        val source: WorkoutSource = WorkoutSource.Pad,
        /** When the user ended this pad walk while the belt ran; it ends once the belt has run down. */
        val endingSinceMs: Long? = null,
    ) : WorkoutState {
        val totals: PadCounters get() = closed + (segmentLast - segmentBase)
        val usesPad: Boolean get() = source == WorkoutSource.Pad
    }
}

class WorkoutContext(val nowMs: Long, val weight: BodyWeight, val profile: UserProfile = UserProfile(), val newId: () -> String)

data class Transition(val state: WorkoutState, val effects: List<WorkoutEffect> = emptyList())

/**
 * Turns telemetry, the health store's answers and user actions into workouts. Pure, so every rule is unit-testable.
 *
 * - A pad walk starts when the belt runs with a positive speed, back-dated by the pad's elapsed time.
 * - A phone workout starts when the user starts it and the health store confirms that it tracks it, within
 *   [WorkoutRules.START_TIMEOUT_MS].
 * - A pad walk pauses when the belt stops, the user pauses, or the connection drops, and resumes when the belt
 *   runs. A phone workout pauses and resumes on the user's request, or pauses when tracking is lost.
 * - Heart rate counts while active; a window that it covers well enough takes its energy from it.
 * - A workout ends on the user's request, after [WorkoutRules.DISCONNECT_TIMEOUT_MS] disconnected or without
 *   tracking, or, for a pad walk, after [WorkoutRules.PAUSE_TIMEOUT_MS] stopped. Workouts under the minimums are
 *   discarded.
 *   A pad walk ended while the belt runs keeps recording until the belt has run down, so the run-down does not
 *   start a new walk.
 *
 * A dropped connection leaves the current window open: if the pad kept counting through the gap, the walk
 * continues as if nothing happened, otherwise the gap becomes a pause. Tracking that comes back after it was
 * lost continues the same way, since the phone keeps counting meanwhile.
 */
object WorkoutStateMachine {
    fun reduce(state: WorkoutState, input: WorkoutInput, context: WorkoutContext): Transition = when (state) {
        is WorkoutState.Idle -> reduceIdle(state, input, context)
        is WorkoutState.InProgress -> reduceInProgress(state, input, context.nowMs)
    }

    private fun reduceIdle(state: WorkoutState.Idle, input: WorkoutInput, context: WorkoutContext): Transition {
        val pending = state.pendingStart
        val now = context.nowMs
        return when (input) {
            is WorkoutInput.UserStart -> {
                val id = context.newId()
                val effects = buildList {
                    pending?.let { add(notify(WorkoutEvent.Discarded(it.id, DiscardReason.UserDiscarded))) }
                    add(notify(WorkoutEvent.StartRequested(id, now, input.kind, WorkoutSource.Phone)))
                }
                Transition(state.copy(pendingStart = PendingStart(id, input.kind, now)), effects)
            }

            is WorkoutInput.TrackingStarted ->
                if (pending?.id == input.id) {
                    val workout = newWorkout(pending.id, now, pending.kind, WorkoutSource.Phone, PadCounters.Zero, PadCounters.Zero, context)
                    Transition(workout, listOf(WorkoutEffect.Checkpoint))
                } else {
                    // Tracking a start that was given up on meanwhile.
                    Transition(state, listOf(notify(WorkoutEvent.Discarded(input.id, DiscardReason.UserDiscarded))))
                }

            is WorkoutInput.TrackingRejected ->
                Transition(if (pending?.id == input.id) state.copy(pendingStart = null) else state)

            WorkoutInput.Tick ->
                if (pending != null && now - pending.requestedMs >= WorkoutRules.START_TIMEOUT_MS) {
                    Transition(state.copy(pendingStart = null), listOf(notify(WorkoutEvent.Discarded(pending.id, DiscardReason.UserDiscarded))))
                } else {
                    Transition(state)
                }

            is WorkoutInput.Telemetry -> {
                val snapshot = input.snapshot
                if (!snapshot.isRunning || snapshot.speedKmh <= 0.0) return Transition(state)
                val raw = snapshot.counters()
                // Counters that continue the previous walk's belong to it; anything else is this walk's.
                val base = state.lastFinal?.takeIf { raw.continues(it) } ?: PadCounters.Zero
                val startMs = now - (raw - base).seconds * 1000L
                val walk = newWorkout(context.newId(), startMs, WorkoutKind.IndoorWalk, WorkoutSource.Pad, base, raw, context)
                val effects = buildList {
                    // The belt beat the phone to it.
                    pending?.let { add(notify(WorkoutEvent.Discarded(it.id, DiscardReason.UserDiscarded))) }
                    add(notify(WorkoutEvent.StartRequested(walk.id, startMs, walk.kind, WorkoutSource.Pad)))
                    add(WorkoutEffect.Checkpoint)
                }
                Transition(walk, effects)
            }

            else -> Transition(state)
        }
    }

    private fun newWorkout(
        id: String,
        startMs: Long,
        kind: WorkoutKind,
        source: WorkoutSource,
        base: PadCounters,
        raw: PadCounters,
        context: WorkoutContext,
    ) = WorkoutState.InProgress(
        id = id,
        startMs = startMs,
        weight = context.weight,
        profile = context.profile,
        closed = PadCounters.Zero,
        segmentBase = base,
        segmentLast = raw,
        windowStartMs = startMs,
        windowStartTotals = PadCounters.Zero,
        samples = emptyList(),
        pauses = emptyList(),
        pause = null,
        kind = kind,
        source = source,
    )

    private fun reduceInProgress(state: WorkoutState.InProgress, input: WorkoutInput, now: Long): Transition {
        val pause = state.pause
        val ending = state.endingSinceMs
        return when (input) {
            is WorkoutInput.Telemetry -> when {
                !state.usesPad -> Transition(state)
                ending != null -> onEndingTelemetry(state, input.snapshot, now)
                pause == null -> onActiveTelemetry(state, input.snapshot, now)
                else -> onPausedTelemetry(state, pause, input.snapshot, now)
            }

            is WorkoutInput.PhoneCounters -> when {
                state.usesPad || input.id != state.id -> Transition(state)
                pause == null -> closeWindowIfDue(state.withCounters(input.counters), now)
                // Tracking is back; the phone counted on meanwhile, so the workout continues as if nothing happened.
                pause.reason == PauseReason.Disconnected -> closeWindowIfDue(state.withCounters(input.counters).copy(pause = null), now)
                else -> Transition(state.withCounters(input.counters))
            }

            is WorkoutInput.TrackingLost ->
                if (!state.usesPad && input.id == state.id && pause == null) {
                    val paused = state.closeWindow(now, state.totals).copy(pause = ActivePause(PauseReason.Disconnected, now))
                    Transition(paused, listOf(WorkoutEffect.Checkpoint))
                } else {
                    Transition(state)
                }

            is WorkoutInput.UserStart, is WorkoutInput.TrackingRejected -> Transition(state)

            // A start that lost out to this workout.
            is WorkoutInput.TrackingStarted ->
                if (input.id == state.id) {
                    Transition(state)
                } else {
                    Transition(state, listOf(notify(WorkoutEvent.Discarded(input.id, DiscardReason.UserDiscarded))))
                }

            WorkoutInput.ConnectionLost -> when {
                ending != null -> finalize(state.closeWindow(now, state.totals), now)
                state.usesPad && pause == null ->
                    Transition(state.copy(pause = ActivePause(PauseReason.Disconnected, now)), listOf(WorkoutEffect.Checkpoint))
                else -> Transition(state)
            }

            WorkoutInput.Tick -> when {
                ending != null && now - ending >= WorkoutRules.STOP_TIMEOUT_MS -> finalize(state.closeWindow(now, state.totals), now)
                pause == null -> closeWindowIfDue(state, now)
                pause.reason == PauseReason.Disconnected && now - pause.sinceMs >= WorkoutRules.DISCONNECT_TIMEOUT_MS ->
                    finalize(state.closeWindow(pause.sinceMs, state.totals), pause.sinceMs)
                state.usesPad && pause.reason != PauseReason.Disconnected && now - pause.sinceMs >= WorkoutRules.PAUSE_TIMEOUT_MS ->
                    finalize(state, pause.sinceMs)
                else -> Transition(state)
            }

            is WorkoutInput.HeartRate -> {
                val bpm = input.sample.bpm
                if (pause == null && bpm in WorkoutRules.MIN_HEART_RATE_BPM..WorkoutRules.MAX_HEART_RATE_BPM) {
                    Transition(state.copy(heart = state.heart.add(input.sample, state.windowStartMs)))
                } else {
                    Transition(state)
                }
            }

            WorkoutInput.UserPause ->
                if (pause == null && ending == null) {
                    val paused = state.closeWindow(now, state.totals).copy(pause = ActivePause(PauseReason.UserRequested, now))
                    val effects = if (state.usesPad) {
                        listOf(WorkoutEffect.Send(TreadmillCommand.StopBelt), WorkoutEffect.Checkpoint)
                    } else {
                        listOf(notify(WorkoutEvent.Paused(state.id, now)), WorkoutEffect.Checkpoint)
                    }
                    Transition(paused, effects)
                } else {
                    Transition(state)
                }

            WorkoutInput.UserResume -> when {
                pause == null || pause.reason == PauseReason.Disconnected -> Transition(state)
                // The walk resumes once the belt reports running.
                state.usesPad -> Transition(state, listOf(WorkoutEffect.Send(TreadmillCommand.StartBelt)))
                else -> {
                    val resumed = state.copy(
                        pause = null,
                        pauses = state.pauses + WorkoutPause(pause.sinceMs, now),
                        windowStartMs = now,
                        windowStartTotals = state.totals,
                    )
                    Transition(resumed, listOf(notify(WorkoutEvent.Resumed(state.id, now)), WorkoutEffect.Checkpoint))
                }
            }

            WorkoutInput.UserEnd -> when {
                // Ending again does not wait for the belt.
                ending != null -> finalize(state.closeWindow(now, state.totals), now)
                // The belt takes a moment to run down; the walk ends once it reports stopped.
                pause == null && state.usesPad ->
                    Transition(state.copy(endingSinceMs = now), listOf(WorkoutEffect.Send(TreadmillCommand.StopBelt)))
                pause == null -> finalize(state.closeWindow(now, state.totals), now)
                pause.reason == PauseReason.Disconnected -> finalize(state.closeWindow(pause.sinceMs, state.totals), pause.sinceMs)
                else -> finalize(state, pause.sinceMs)
            }

            WorkoutInput.UserDiscard -> {
                val effects = buildList {
                    if (pause == null && ending == null && state.usesPad) add(WorkoutEffect.Send(TreadmillCommand.StopBelt))
                    add(notify(WorkoutEvent.Discarded(state.id, DiscardReason.UserDiscarded)))
                    add(WorkoutEffect.Discarded(DiscardReason.UserDiscarded))
                }
                Transition(state.idle(), effects)
            }
        }
    }

    /** A pad walk the user ended runs on until the belt has run down, then ends. */
    private fun onEndingTelemetry(state: WorkoutState.InProgress, snapshot: TelemetrySnapshot, now: Long): Transition {
        val updated = state.withCounters(snapshot.counters())
        if (snapshot.isRunning && snapshot.speedKmh > 0.0) return closeWindowIfDue(updated, now)
        return finalize(updated.closeWindow(now, updated.totals), now)
    }

    private fun onActiveTelemetry(state: WorkoutState.InProgress, snapshot: TelemetrySnapshot, now: Long): Transition {
        val updated = state.withCounters(snapshot.counters())
        if (snapshot.isRunning) return closeWindowIfDue(updated, now)
        val paused = updated.closeWindow(now, updated.totals).copy(pause = ActivePause(PauseReason.BeltStopped, now))
        return Transition(paused, listOf(WorkoutEffect.Checkpoint))
    }

    private fun onPausedTelemetry(
        state: WorkoutState.InProgress,
        pause: ActivePause,
        snapshot: TelemetrySnapshot,
        now: Long,
    ): Transition {
        val before = state.totals
        val updated = state.withCounters(snapshot.counters())
        val after = updated.totals

        if (pause.reason != PauseReason.Disconnected) {
            if (!snapshot.isRunning) return Transition(updated)
            val resumed = updated.copy(
                pause = null,
                pauses = updated.pauses + WorkoutPause(pause.sinceMs, now),
                windowStartMs = now,
                windowStartTotals = before,
            )
            return Transition(resumed, listOf(WorkoutEffect.Checkpoint))
        }

        // First report after a reconnect: work out what the pad did during the gap.
        val advancedSeconds = after.seconds - before.seconds
        val gapSeconds = (now - pause.sinceMs) / 1000
        val keptCounting = snapshot.counters().continues(state.segmentLast)
        return when {
            snapshot.isRunning && keptCounting && advancedSeconds * 2 >= gapSeconds ->
                closeWindowIfDue(updated.copy(pause = null), now)

            snapshot.isRunning -> {
                val resumed = updated.closeWindow(pause.sinceMs, before).copy(
                    pause = null,
                    pauses = updated.pauses + WorkoutPause(pause.sinceMs, now),
                    windowStartMs = now,
                    windowStartTotals = before,
                )
                Transition(resumed, listOf(WorkoutEffect.Checkpoint))
            }

            else -> {
                // The belt stopped sometime during the gap; attribute what it counted to the time right after the drop.
                val stoppedAt = minOf(now, pause.sinceMs + advancedSeconds.coerceAtLeast(0) * 1000L)
                val paused = updated.closeWindow(stoppedAt, after).copy(pause = ActivePause(PauseReason.BeltStopped, stoppedAt))
                Transition(paused, listOf(WorkoutEffect.Checkpoint))
            }
        }
    }

    private fun closeWindowIfDue(state: WorkoutState.InProgress, now: Long): Transition =
        if (now - state.windowStartMs >= WorkoutRules.WINDOW_MS) {
            Transition(state.closeWindow(now, state.totals), listOf(WorkoutEffect.Checkpoint))
        } else {
            Transition(state)
        }

    /**
     * Ends the workout at [endMs]; the caller has closed the last window. A trailing pause is not recorded. A
     * workout that is saved reaches the health store through the recorder, which holds back what may not be saved.
     */
    private fun finalize(state: WorkoutState.InProgress, endMs: Long): Transition {
        val totals = state.totals
        val idle = state.idle()
        if (totals.seconds < WorkoutRules.MIN_ACTIVE_SECONDS || totals.meters < WorkoutRules.MIN_DISTANCE_METERS) {
            return Transition(
                idle,
                listOf(notify(WorkoutEvent.Discarded(state.id, DiscardReason.TooShort)), WorkoutEffect.Discarded(DiscardReason.TooShort)),
            )
        }
        val record = WorkoutRecord(
            id = state.id,
            startMs = state.startMs,
            endMs = endMs,
            activeSeconds = totals.seconds,
            distanceMeters = totals.meters,
            steps = totals.steps,
            activeKcal = state.samples.sumOf { it.activeKcal },
            totalKcal = state.samples.sumOf { it.totalKcal },
            weight = state.weight,
            pauses = state.pauses,
            samples = state.samples,
            kind = state.kind,
            source = state.source,
        )
        return Transition(idle, listOf(WorkoutEffect.Completed(record)))
    }

    /** Idle after this workout; only the pad's counters can carry over into the next walk. */
    private fun WorkoutState.InProgress.idle() = WorkoutState.Idle(lastFinal = segmentLast.takeIf { usesPad })

    private fun notify(event: WorkoutEvent) = WorkoutEffect.Notify(event)

    /** Applies a counter reading; counters that went down mean the pad reset them and a new segment began. */
    private fun WorkoutState.InProgress.withCounters(raw: PadCounters): WorkoutState.InProgress =
        if (raw.continues(segmentLast)) {
            copy(segmentLast = raw)
        } else {
            copy(closed = closed + (segmentLast - segmentBase), segmentBase = PadCounters.Zero, segmentLast = raw)
        }

    /**
     * Turns everything since the window start into a sample ending at [endMs], unless nothing was counted.
     * Energy comes from heart rate when it covered enough of the window's active time and the profile allows it.
     */
    private fun WorkoutState.InProgress.closeWindow(endMs: Long, endTotals: PadCounters): WorkoutState.InProgress {
        val delta = endTotals - windowStartTotals
        if (delta.seconds <= 0 && delta.meters <= 0 && delta.steps <= 0) {
            return copy(windowStartMs = endMs, windowStartTotals = endTotals, heart = HeartAccumulator())
        }
        val meters = delta.meters.coerceAtLeast(0)
        val seconds = delta.seconds.coerceAtLeast(0)
        val fromHeart = heart.takeIf { it.count > 0 && it.coveredMs >= WorkoutRules.HEART_RATE_MIN_COVERAGE * seconds * 1000 }
            ?.let { EnergyModel.heartRate(it.avgBpm, seconds, weight.kg, profile) }
        val sample = WorkoutSample(
            startMs = windowStartMs,
            endMs = maxOf(endMs, windowStartMs + 1),
            distanceMeters = meters,
            steps = delta.steps.coerceAtLeast(0),
            activeKcal = fromHeart?.activeKcal ?: EnergyModel.activeKcal(meters, weight.kg, kind.activity),
            totalKcal = fromHeart?.totalKcal ?: EnergyModel.totalKcal(meters, seconds, weight.kg, kind.activity),
            heartRate = heart.takeIf { it.count > 0 }?.let {
                HeartRateSummary(it.avgBpm, it.minBpm, it.maxBpm, usedForEnergy = fromHeart != null, series = it.series)
            },
        )
        return copy(samples = samples + sample, windowStartMs = endMs, windowStartTotals = endTotals, heart = HeartAccumulator())
    }

    private fun TelemetrySnapshot.counters() = PadCounters(elapsedSeconds, distanceMeters, steps)
}
