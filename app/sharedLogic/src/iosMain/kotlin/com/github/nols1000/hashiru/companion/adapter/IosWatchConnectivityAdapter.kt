package com.github.nols1000.hashiru.companion.adapter

import com.github.nols1000.hashiru.companion.codec.WatchMessage
import com.github.nols1000.hashiru.companion.codec.WatchMessageCodec
import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.domain.IncomingCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.companion.port.WatchCompanionPort
import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.toNSData
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKWorkoutActivityTypeWalking
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutSessionLocationTypeIndoor
import platform.HealthKit.startWatchAppWithWorkoutConfiguration
import platform.WatchConnectivity.WCSession
import platform.WatchConnectivity.WCSessionActivationState
import platform.WatchConnectivity.WCSessionActivationStateActivated
import platform.WatchConnectivity.WCSessionDelegateProtocol
import platform.darwin.NSObject

/**
 * Talks to the watchOS app over WatchConnectivity. Commands and heart rate arrive as message data;
 * telemetry goes out as message data while the watch is reachable, and the walk's phase as the application
 * context, which iOS delivers once the watch app runs again.
 *
 * Create it during launch: the session must be activated before iOS hands over messages that woke the app.
 */
@OptIn(ExperimentalForeignApi::class)
class IosWatchConnectivityAdapter : WatchCompanionPort {
    private val session: WCSession? = if (WCSession.isSupported()) WCSession.defaultSession else null
    private val healthStore = HKHealthStore()
    private val delegate = Delegate()

    private val _state = MutableStateFlow(CompanionState.NotPaired)
    override val state: StateFlow<CompanionState> = _state.asStateFlow()

    private val _heartRate = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 16)
    override val heartRate: Flow<HeartRateSample> = _heartRate

    private val _commands = MutableSharedFlow<IncomingCommand>(extraBufferCapacity = 16)
    override val commands: Flow<IncomingCommand> = _commands

    init {
        session?.delegate = delegate
        session?.activateSession()
    }

    override suspend fun pushTelemetry(telemetry: WristTelemetry) {
        val session = session ?: return
        if (!session.reachable) return
        session.sendMessageData(encode(telemetry), replyHandler = null) { error -> log("telemetry", error) }
    }

    override suspend fun pushState(telemetry: WristTelemetry) {
        val session = session ?: return
        if (_state.value == CompanionState.NotPaired || _state.value == CompanionState.AppNotInstalled) return
        session.updateApplicationContext(mapOf(KEY_STATE to encode(telemetry)), null)
    }

    /** Launches the watch app into its workout session; the system shows it on the wrist. */
    override suspend fun requestCompanionStart() {
        if (_state.value == CompanionState.NotPaired || _state.value == CompanionState.AppNotInstalled) return
        val configuration = HKWorkoutConfiguration().apply {
            activityType = HKWorkoutActivityTypeWalking
            locationType = HKWorkoutSessionLocationTypeIndoor
        }
        healthStore.startWatchAppWithWorkoutConfiguration(configuration) { _, error -> log("start watch app", error) }
    }

    private fun refresh() {
        val session = session
        _state.value = when {
            session == null || session.activationState != WCSessionActivationStateActivated || !session.paired -> CompanionState.NotPaired
            !session.watchAppInstalled -> CompanionState.AppNotInstalled
            session.reachable -> CompanionState.Reachable
            else -> CompanionState.Unreachable
        }
    }

    private fun receive(data: NSData, reply: ((NSData?) -> Unit)?) {
        when (val message = WatchMessageCodec.decode(data.toByteArray())) {
            is WatchMessage.HeartRate -> _heartRate.tryEmit(message.sample)
            is WatchMessage.Command -> _commands.tryEmit(
                IncomingCommand(message.command) { ok -> reply?.invoke(WatchMessageCodec.encode(WatchMessage.Ack(ok)).toNSData()) },
            )
            else -> reply?.invoke(null)
        }
    }

    private fun encode(telemetry: WristTelemetry) = WatchMessageCodec.encode(WatchMessage.Telemetry(telemetry)).toNSData()

    private fun log(what: String, error: NSError?) {
        if (error != null) println("$TAG: $what failed: ${error.localizedDescription}")
    }

    private inner class Delegate : NSObject(), WCSessionDelegateProtocol {
        override fun session(session: WCSession, activationDidCompleteWithState: WCSessionActivationState, error: NSError?) {
            log("activation", error)
            refresh()
        }

        override fun sessionDidBecomeInactive(session: WCSession) = refresh()

        /** The user switched watches; activate again for the new one. */
        override fun sessionDidDeactivate(session: WCSession) {
            refresh()
            session.activateSession()
        }

        override fun sessionWatchStateDidChange(session: WCSession) = refresh()

        override fun sessionReachabilityDidChange(session: WCSession) = refresh()

        override fun session(session: WCSession, didReceiveMessageData: NSData) = receive(didReceiveMessageData, null)

        override fun session(session: WCSession, didReceiveMessageData: NSData, replyHandler: (NSData?) -> Unit) =
            receive(didReceiveMessageData, replyHandler)
    }

    private companion object {
        const val TAG = "WatchConnectivity"

        /** Application-context key; the watch app reads the same one. */
        const val KEY_STATE = "s"
    }
}
