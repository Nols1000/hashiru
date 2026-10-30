package com.github.nols1000.hashiru.treadmill

import com.github.nols1000.hashiru.treadmill.codec.WalkingPadPacketCodec
import com.github.nols1000.hashiru.treadmill.domain.BeltState
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TelemetrySnapshot
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillEvent
import com.github.nols1000.hashiru.treadmill.engine.WalkingPadEngine
import com.github.nols1000.hashiru.treadmill.port.TreadmillTransportPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WalkingPadEngineTest {

    private class FakeTransport(private val clock: () -> Long, private val repliesToPings: Boolean = false) :
        TreadmillTransportPort {
        override val connectionState = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        override val restoredSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val sent = mutableListOf<ByteArray>()
        val sentAt = mutableListOf<Long>()

        override suspend fun connect(address: String?) {
            connectionState.value = ConnectionState.Connected
        }

        override fun disconnect() {
            connectionState.value = ConnectionState.Disconnected
        }

        override suspend fun send(bytes: ByteArray) {
            sent += bytes
            sentAt += clock()
            if (repliesToPings && bytes.contentEquals(WalkingPadPacketCodec.encodePing())) {
                incomingBytes.emit(WalkingPadPacketCodec.encodeStatus(0, 0, WalkingPadPacketCodec.MODE_STANDBY))
            }
        }
    }

    private fun TestScope.fakeTransport(repliesToPings: Boolean = false) = FakeTransport({ currentTime }, repliesToPings)

    private fun TestScope.startEngine(transport: FakeTransport) =
        WalkingPadEngine(transport, backgroundScope, timeSource = testScheduler.timeSource).also {
            it.start()
            runCurrent()
        }

    private fun FakeTransport.pingTimes() =
        sentAt.filterIndexed { i, _ -> sent[i].contentEquals(WalkingPadPacketCodec.encodePing()) }

    @Test
    fun pingsAgainAfterEachReplyOnlyWhileConnected() = runTest {
        val transport = fakeTransport(repliesToPings = true)
        startEngine(transport)

        advanceTimeBy(2_000)
        assertEquals(emptyList(), transport.pingTimes())

        transport.connectionState.value = ConnectionState.Connected
        advanceTimeBy(1_600)
        assertEquals(listOf(2_000L, 2_750L, 3_500L), transport.pingTimes())

        transport.connectionState.value = ConnectionState.Disconnected
        advanceTimeBy(3_000)
        assertEquals(3, transport.pingTimes().size)
    }

    @Test
    fun pingsAgainAfterTimeoutWhenThePadStaysSilent() = runTest {
        val transport = fakeTransport()
        startEngine(transport)

        transport.connectionState.value = ConnectionState.Connected
        advanceTimeBy(3_600)

        // 1 s reply timeout + 750 ms poll interval between pings.
        assertEquals(listOf(0L, 1_750L, 3_500L), transport.pingTimes())
    }

    @Test
    fun startBeltWritesModeThenStartSpacedApart() = runTest {
        val transport = fakeTransport()
        val engine = startEngine(transport)

        engine.dispatch(TreadmillCommand.StartBelt)

        assertEquals(2, transport.sent.size)
        assertContentEquals(WalkingPadPacketCodec.encodeSwitchMode(WalkingPadPacketCodec.MODE_MANUAL), transport.sent[0])
        assertContentEquals(WalkingPadPacketCodec.encodeStartBelt(), transport.sent[1])
        assertEquals(250L, transport.sentAt[1] - transport.sentAt[0])
    }

    @Test
    fun speedFloodIsConflatedToTheLatestValue() = runTest {
        val transport = fakeTransport()
        val engine = startEngine(transport)

        // 20 speed changes within 500 ms, as from rapid stepper taps.
        repeat(20) { i ->
            backgroundScope.launch { engine.dispatch(TreadmillCommand.SetSpeed(10 + i)) }
            delay(25)
        }
        advanceTimeBy(1_000)

        val speeds = transport.sent.map { WalkingPadPacketCodec.decodeSetSpeed(it) }
        assertEquals(listOf(10, 19, 29), speeds)
        assertTrue(transport.sentAt.zipWithNext { a, b -> b - a }.all { it >= 250 })
    }

    @Test
    fun stopIsNeverConflatedAway() = runTest {
        val transport = fakeTransport()
        val engine = startEngine(transport)

        backgroundScope.launch { engine.dispatch(TreadmillCommand.SetSpeed(30)) }
        backgroundScope.launch { engine.dispatch(TreadmillCommand.SetSpeed(40)) }
        backgroundScope.launch { engine.dispatch(TreadmillCommand.StopBelt) }
        advanceTimeBy(1_000)

        assertEquals(listOf(30, 40, 0), transport.sent.map { WalkingPadPacketCodec.decodeSetSpeed(it) })
    }

    @Test
    fun decodesIncomingStatusIntoTelemetryAndBeltEvents() = runTest {
        val transport = fakeTransport()
        val engine = startEngine(transport)
        val events = mutableListOf<TreadmillEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(events) }

        transport.incomingBytes.emit(WalkingPadPacketCodec.encodeStatus(1, 25, 1))
        transport.incomingBytes.emit(WalkingPadPacketCodec.encodeStatus(1, 30, 1, timeSeconds = 90, distanceMeters = 80, steps = 110))
        transport.incomingBytes.emit(byteArrayOf(0x00)) // garbage is ignored
        runCurrent()

        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.BeltStateChanged(BeltState.Running)), events)
        assertEquals(
            TelemetrySnapshot(speedKmh = 3.0, isRunning = true, elapsedSeconds = 90, distanceMeters = 80, steps = 110),
            engine.telemetry.value,
        )
    }

    @Test
    fun restoredSessionIsReportedAsEvent() = runTest {
        val transport = fakeTransport()
        val engine = startEngine(transport)
        val events = mutableListOf<TreadmillEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(events) }

        transport.restoredSessions.emit(Unit)
        runCurrent()

        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.SessionRestoredInBackground), events)
    }
}
