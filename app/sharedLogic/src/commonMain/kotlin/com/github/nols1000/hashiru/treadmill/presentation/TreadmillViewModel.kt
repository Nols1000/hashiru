package com.github.nols1000.hashiru.treadmill.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.nols1000.hashiru.log.log
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.PairingCancelledException
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.domain.TreadmillException
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class TreadmillUiState(
    val isConnected: Boolean = false,
    val speedKmh: Double = 0.0,
    val isRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val error: TreadmillError? = null,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val steps: Int = 0,
    val isPaired: Boolean = false,
    /** False on OS versions without system pairing; the UI then offers a plain scan-and-connect. */
    val canPair: Boolean = true,
) {
    private val speedTenths: Int get() = (speedKmh * 10).roundToInt()
    val canIncreaseSpeed: Boolean get() = isRunning && speedTenths < TreadmillViewModel.MAX_SPEED_TENTHS
    val canDecreaseSpeed: Boolean get() = isRunning && speedTenths > TreadmillViewModel.MIN_SPEED_TENTHS
}

/** The state before anything is known; for Swift, which cannot use the constructor's default arguments. */
fun initialTreadmillUiState(): TreadmillUiState = TreadmillUiState()

/**
 * Presents a [TreadmillSession]: the connection, the belt and its speed, and what last went wrong with them. The
 * session outlives this ViewModel, so clearing it leaves the connection up; it is started by whoever built it.
 */
class TreadmillViewModel(private val session: TreadmillSession) : ViewModel() {
    private val error = MutableStateFlow<TreadmillError?>(null)

    /** Last speed requested via the stepper, so rapid taps build on each other before telemetry catches up. */
    private var requestedTenths: Int? = null

    val uiState: StateFlow<TreadmillUiState> =
        combine(session.connectionState, session.telemetry, session.association, error) { connection, telemetry, association, error ->
            val connected = connection == ConnectionState.Connected
            TreadmillUiState(
                isConnected = connected,
                speedKmh = if (connected) telemetry.speedKmh else 0.0,
                isRunning = connected && telemetry.isRunning,
                isConnecting = connection == ConnectionState.Connecting,
                error = error,
                elapsedSeconds = if (connected) telemetry.elapsedSeconds else 0,
                distanceMeters = if (connected) telemetry.distanceMeters else 0,
                steps = if (connected) telemetry.steps else 0,
                isPaired = association != null,
                canPair = session.canPair,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TreadmillUiState())

    init {
        viewModelScope.launch {
            session.telemetry.collect {
                val tenths = (it.speedKmh * 10).roundToInt()
                if (!it.isRunning || tenths == requestedTenths) requestedTenths = null
                // Stopped another way, by the pad's button or remote: the failed stop no longer matters.
                if (!it.isRunning && error.value == TreadmillError.CommandFailed(TreadmillCommand.StopBelt)) error.value = null
            }
        }
    }

    fun connect() {
        if (session.connectionState.value != ConnectionState.Disconnected) return
        error.value = null
        launchReporting({ TreadmillError.ConnectionFailed() }) { session.connect() }
    }

    /** Shows the system device picker and connects to the chosen treadmill. Dismissing the picker is not an error. */
    fun pair() {
        error.value = null
        launchReporting({ TreadmillError.ConnectionFailed() }) {
            try {
                session.pair()
            } catch (_: PairingCancelledException) {
            }
        }
    }

    /** Disconnects and removes the stored association. */
    fun forget() {
        requestedTenths = null
        launchReporting({ TreadmillError.ConnectionFailed() }) { session.forget() }
    }

    fun disconnect() {
        session.disconnect()
    }

    fun toggleBelt() {
        val command = if (uiState.value.isRunning) TreadmillCommand.StopBelt else TreadmillCommand.StartBelt
        requestedTenths = null
        dispatch(command)
    }

    fun stopBelt() {
        requestedTenths = null
        dispatch(TreadmillCommand.StopBelt)
    }

    /** Raises the speed to the next [SPEED_STEP_TENTHS] step, up to [MAX_SPEED_TENTHS]. */
    fun increaseSpeed() {
        if (!uiState.value.isRunning) return
        val base = requestedTenths ?: currentTenths()
        stepTo((base / SPEED_STEP_TENTHS + 1) * SPEED_STEP_TENTHS)
    }

    /** Lowers the speed to the previous [SPEED_STEP_TENTHS] step, down to [MIN_SPEED_TENTHS]. */
    fun decreaseSpeed() {
        if (!uiState.value.isRunning) return
        val base = requestedTenths ?: currentTenths()
        stepTo((base - 1) / SPEED_STEP_TENTHS * SPEED_STEP_TENTHS)
    }

    fun dismissError() {
        error.value = null
    }

    /** Swift-friendly observation of [uiState]; call `close()` on the result to stop. */
    fun watch(onChange: (TreadmillUiState) -> Unit): AutoCloseable {
        val job = viewModelScope.launch { uiState.collect { onChange(it) } }
        return AutoCloseable { job.cancel() }
    }

    private fun currentTenths() = (uiState.value.speedKmh * 10).roundToInt()

    private fun stepTo(tenths: Int) {
        val target = tenths.coerceIn(MIN_SPEED_TENTHS, MAX_SPEED_TENTHS)
        if (target == (requestedTenths ?: currentTenths())) return
        requestedTenths = target
        dispatch(TreadmillCommand.SetSpeed(target))
    }

    private fun dispatch(command: TreadmillCommand) {
        launchReporting({ TreadmillError.CommandFailed(command) }) { session.dispatch(command) }
    }

    /** Runs [block], showing a [TreadmillException]'s error, or [fallback] for anything else. The message is only logged. */
    private fun launchReporting(fallback: () -> TreadmillError, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("TreadmillViewModel", "${e.message}")
                error.value = (e as? TreadmillException)?.error?.takeUnless { it is TreadmillError.CommandFailed } ?: fallback()
            }
        }
    }

    companion object {
        const val MIN_SPEED_TENTHS = TreadmillCommand.SetSpeed.MIN_TENTHS
        const val MAX_SPEED_TENTHS = TreadmillCommand.SetSpeed.MAX_TENTHS
        const val SPEED_STEP_TENTHS = 5
    }
}
