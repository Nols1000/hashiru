package com.github.nols1000.hashiru.treadmill

import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillEvent
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TreadmillSessionTest {
    private val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10)
    private val associations = MockAssociationAdapter()

    private fun TestScope.startSession() =
        TreadmillSession(transport, associations, backgroundScope, testScheduler.timeSource).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.collectEvents(session: TreadmillSession) = mutableListOf<TreadmillEvent>().also { events ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.events.toList(events) }
    }

    @Test
    fun pairingStoresTheAssociationAndConnects() = runTest {
        val session = startSession()

        session.pair()

        assertNotNull(session.association.value)
        assertEquals(ConnectionState.Connected, session.connectionState.value)
    }

    @Test
    fun reconnectsWhenTheAssociatedDeviceReappears() = runTest {
        val session = startSession()
        val events = collectEvents(session)
        session.pair()
        transport.simulateConnectionLoss()

        associations.simulatePresence(nearby = false)
        associations.simulatePresence(nearby = true)
        advanceTimeBy(600)

        assertEquals(ConnectionState.Connected, session.connectionState.value)
        assertEquals(
            listOf(TreadmillEvent.DeviceDisappeared, TreadmillEvent.DeviceAppearedNearby),
            events.filter { it is TreadmillEvent.DeviceDisappeared || it is TreadmillEvent.DeviceAppearedNearby },
        )
    }

    @Test
    fun restoredSessionResumesTelemetry() = runTest {
        val session = startSession()
        val events = collectEvents(session)
        session.pair()
        session.dispatch(TreadmillCommand.StartBelt)
        advanceTimeBy(3_000)
        transport.simulateConnectionLoss()
        runCurrent()

        transport.simulateRestore()
        runCurrent()
        assertTrue(TreadmillEvent.SessionRestoredInBackground in events)

        // Telemetry only moves on if pinging and command dispatch resumed on the restored connection.
        session.dispatch(TreadmillCommand.SetSpeed(40))
        advanceTimeBy(2_000)
        assertEquals(4.0, session.telemetry.value.speedKmh)
        assertTrue(session.telemetry.value.isRunning)
    }

    @Test
    fun forgetDisconnectsAndClearsTheAssociation() = runTest {
        val session = startSession()
        session.pair()

        session.forget()

        assertNull(session.association.value)
        assertEquals(ConnectionState.Disconnected, session.connectionState.value)
    }
}
