package com.github.nols1000.hashiru.companion.adapter

import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.domain.IncomingCommand
import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.companion.port.WatchCompanionPort
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Simulated watch for running the app without one. While a walk is active it reports a heart rate that
 * follows the belt speed; [send] plays a wrist command and returns nothing, recording the reply in [replies].
 * [pushedTelemetry], [pushedStates] and [startRequests] record what the phone sent.
 */
@OptIn(ExperimentalTime::class)
class MockWatchCompanionAdapter(
    initialState: CompanionState = CompanionState.Reachable,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val simulateHeartRate: Boolean = true,
) : WatchCompanionPort {
    override val state = MutableStateFlow(initialState)

    private val _commands = MutableSharedFlow<IncomingCommand>(extraBufferCapacity = 16)
    override val commands: Flow<IncomingCommand> = _commands

    private val _heartRate = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 16)
    override val heartRate: Flow<HeartRateSample> = if (!simulateHeartRate) _heartRate else flow {
        while (true) {
            delay(1_000)
            val last = pushedStates.lastOrNull()
            if (last?.phase == WorkoutPhase.Active) {
                val speed = pushedTelemetry.lastOrNull()?.speedTenths ?: 0
                emit(HeartRateSample(nowMs(), 72 + speed + (nowMs() / 1_000 % 5).toInt()))
            }
        }
    }

    val pushedTelemetry = mutableListOf<WristTelemetry>()
    val pushedStates = mutableListOf<WristTelemetry>()
    val replies = mutableListOf<Pair<WristCommand, Boolean>>()
    var startRequests = 0
        private set

    fun send(command: WristCommand) {
        _commands.tryEmit(IncomingCommand(command) { ok -> replies += command to ok })
    }

    /** Emits [sample] as if the watch measured it; only with simulateHeartRate off. */
    fun measure(sample: HeartRateSample) {
        _heartRate.tryEmit(sample)
    }

    override suspend fun pushTelemetry(telemetry: WristTelemetry) {
        pushedTelemetry += telemetry
    }

    override suspend fun pushState(telemetry: WristTelemetry) {
        pushedStates += telemetry
    }

    override suspend fun requestCompanionStart() {
        startRequests++
    }
}
