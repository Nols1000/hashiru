package com.github.nols1000.hashiru.companion.engine

import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.companion.port.WatchCompanionPort
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.engine.WorkoutRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * App-scoped bridge between the phone's walk and the watch app. Like [WorkoutRecorder] it outlives every
 * screen. The phone stays the only owner of the treadmill and the walk; the watch is a heart-rate sensor
 * and a remote.
 *
 * - Heart rate from the watch goes into the walk; [heartRate] holds the latest reading until it goes stale.
 * - Live metrics go to the watch at most once per [TELEMETRY_INTERVAL_MS], and only while it is reachable
 *   and there is something to show; phase changes go out at once over the durable path.
 * - When a walk starts, the watch is asked to start its workout session so its sensor runs.
 * - Commands from the wrist are carried out straight away and answered.
 */
class WatchRelay internal constructor(
    private val session: TreadmillSession,
    private val workouts: WorkoutRecorder,
    private val port: WatchCompanionPort,
    private val scope: CoroutineScope,
) {
    /** Creates the relay on the main thread for the rest of the process; keep a single instance. */
    constructor(session: TreadmillSession, workouts: WorkoutRecorder, port: WatchCompanionPort) :
        this(session, workouts, port, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    val companion: StateFlow<CompanionState> get() = port.state

    private val _heartRate = MutableStateFlow<HeartRateSample?>(null)

    /** The latest plausible reading, or null once none arrived for [STALE_MS]. */
    val heartRate: StateFlow<HeartRateSample?> = _heartRate.asStateFlow()

    /** What the watch shows, kept current while the relay runs. */
    val telemetry: StateFlow<WristTelemetry> =
        combine(session.connectionState, session.telemetry, workouts.status, heartRate) { connection, pad, walk, heart ->
            val connected = connection == ConnectionState.Connected
            val bpm = heart?.bpm
            WristTelemetry(
                phase = walk.phase,
                isConnected = connected,
                isRunning = connected && pad.isRunning,
                speedTenths = if (connected) (pad.speedKmh * 10).roundToInt() else 0,
                durationSeconds = walk.totals.seconds,
                distanceMeters = walk.totals.meters,
                heartRateBpm = bpm,
                zone = bpm?.let { HeartRateZone.of(it, walk.profile.ageYears) },
                canResume = walk.phase == WorkoutPhase.Paused && walk.pauseReason != PauseReason.Disconnected,
            )
        }.stateIn(scope, SharingStarted.Eagerly, WristTelemetry())

    private var started = false

    fun start() {
        if (started) return
        started = true
        workouts.start()

        scope.launch {
            port.heartRate.collect { sample ->
                if (sample.bpm !in WorkoutRules.MIN_HEART_RATE_BPM..WorkoutRules.MAX_HEART_RATE_BPM) return@collect
                _heartRate.value = sample
                workouts.recordHeartRate(sample)
            }
        }
        scope.launch {
            // Measured from arrival, so a watch clock that is off does not matter.
            _heartRate.collectLatest {
                if (it == null) return@collectLatest
                delay(STALE_MS)
                _heartRate.value = null
            }
        }
        scope.launch {
            var previous = workouts.status.value.phase
            workouts.status.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (previous == WorkoutPhase.Idle && phase == WorkoutPhase.Active) report { port.requestCompanionStart() }
                previous = phase
            }
        }
        scope.launch {
            telemetry.distinctUntilChangedBy { Triple(it.phase, it.isConnected, it.canResume) }.collect { report { port.pushState(it) } }
        }
        scope.launch {
            combine(port.state, telemetry) { state, t ->
                t.takeIf { state == CompanionState.Reachable && (t.phase != WorkoutPhase.Idle || t.isRunning) }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .conflate()
                .collect {
                    report { port.pushTelemetry(it) }
                    delay(TELEMETRY_INTERVAL_MS)
                }
        }
        scope.launch {
            port.commands.collect { incoming -> launch { incoming.reply(carryOut(incoming.command)) } }
        }
    }

    /** Returns whether [command] was carried out. */
    private suspend fun carryOut(command: WristCommand): Boolean = report {
        val connected = session.connectionState.value == ConnectionState.Connected
        val phase = workouts.status.value.phase
        when (command) {
            WristCommand.StopBelt -> {
                session.dispatch(TreadmillCommand.StopBelt)
                true
            }
            is WristCommand.SetSpeed -> {
                if (!connected || !session.telemetry.value.isRunning) return@report false
                val tenths = command.tenthsKmh.coerceIn(TreadmillCommand.SetSpeed.MIN_TENTHS, TreadmillCommand.SetSpeed.MAX_TENTHS)
                session.dispatch(TreadmillCommand.SetSpeed(tenths))
                true
            }
            is WristCommand.SetPaused -> when {
                command.paused -> {
                    workouts.pause()
                    true
                }
                phase == WorkoutPhase.Idle -> {
                    if (!connected) return@report false
                    session.dispatch(TreadmillCommand.StartBelt)
                    true
                }
                else -> {
                    workouts.resume()
                    true
                }
            }
        }
    } ?: false

    /** Runs [block], logging instead of throwing; the relay must keep running whatever the link does. */
    private suspend fun <T> report(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("WatchRelay: ${e.message}")
        null
    }

    companion object {
        const val TELEMETRY_INTERVAL_MS = 1_000L
        const val STALE_MS = 10_000L
    }
}
