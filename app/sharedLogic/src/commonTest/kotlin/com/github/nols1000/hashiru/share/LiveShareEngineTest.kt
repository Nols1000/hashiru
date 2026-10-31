package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.MockShareRelayAdapter
import com.github.nols1000.hashiru.share.codec.ShareMessageCodec
import com.github.nols1000.hashiru.share.domain.LiveShareStatus
import com.github.nols1000.hashiru.share.domain.ShareMessage
import com.github.nols1000.hashiru.share.domain.SharePhase
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveShareEngineTest {
    private val epoch = 1_700_000_000_000L
    private val workouts = MutableStateFlow(WorkoutStatus())
    private val relay = MockShareRelayAdapter()
    private val cipher = FakeShareCipher()
    private val storage = InMemoryKeyValueStore()
    private var battery: Int? = 80

    private fun TestScope.engine(): LiveShareEngine {
        val engine = LiveShareEngine(workouts, relay, cipher, { battery }, storage, "https://share.test", backgroundScope, { epoch + testScheduler.currentTime })
        engine.start()
        runCurrent()
        return engine
    }

    private fun walking(points: Int, kind: WorkoutKind = WorkoutKind.OutdoorWalk, phase: WorkoutPhase = WorkoutPhase.Active, bpm: Int? = 120) =
        WorkoutStatus(
            phase = phase,
            kind = kind,
            totals = PadCounters(points * 5, points * 7, points * 9),
            heartRateBpm = bpm,
            route = List(points) { RoutePoint(epoch + it * 5_000L, 52.5 + it * 6e-5, 13.4) },
        )

    private val publishes get() = relay.requests.filterIsInstance<RelayRequest.Publish>()

    private fun RelayRequest.Publish.message() = assertNotNull(ShareMessageCodec.decode(FakeShareCipher.plaintext(frame)))

    @Test
    fun aShareHandsOutKeysInTheFragmentOnly() = runTest {
        workouts.value = walking(3)
        val engine = engine()
        engine.share()
        runCurrent()

        val live = assertIs<LiveShareStatus.Live>(engine.status.value)
        val open = assertIs<RelayRequest.Open>(relay.requests.first())
        // Nothing about the share is in the path, which the viewer's host would see.
        assertTrue(live.link.startsWith("https://share.test/#v1.${open.shareId}."))
        // The relay gets a presence key, never the key frames are sealed with.
        val fragmentKey = live.link.substringAfter('#').split('.')[2]
        assertTrue(fragmentKey !in open.presenceKey.hex())
        val snapshot = assertIs<ShareMessage.Snapshot>(publishes.single().message())
        assertEquals(3, snapshot.route.size)
    }

    @Test
    fun nothingIsSentWhileNoOneWatches() = runTest {
        workouts.value = walking(1)
        val engine = engine()
        engine.share()
        runCurrent()
        for (i in 2..60) {
            workouts.value = walking(i)
            advanceTimeBy(5_000)
        }
        workouts.value = walking(60, phase = WorkoutPhase.Paused)
        runCurrent()

        assertEquals(1, publishes.size)
        assertEquals(1, relay.requests.count { it is RelayRequest.AwaitViewers })
    }

    @Test
    fun aViewerGetsASnapshotThenUpdatesWithNewPositionsOnly() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        engine.share()
        runCurrent()
        val id = relay.requests.first().shareId

        workouts.value = walking(4)
        relay.arrive(id)
        runCurrent()
        assertEquals(4, assertIs<ShareMessage.Snapshot>(publishes.last().message()).route.size)
        assertEquals(1, assertIs<LiveShareStatus.Live>(engine.status.value).watchers)

        workouts.value = walking(7)
        advanceTimeBy(LiveShareEngine.WATCHED_MS + 1)
        val update = assertIs<ShareMessage.Update>(publishes.last().message())
        assertEquals(3, update.route.size)
        assertEquals(epoch + 4 * 5_000L, update.route.first().atMs)
        assertNull(update.state.heartRateBpm)
    }

    @Test
    fun positionsRecordedOfflineFollowOnceBack() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        relay.watchers = 1
        engine.share()
        runCurrent()

        relay.reachable = false
        workouts.value = walking(5)
        advanceTimeBy(LiveShareEngine.WATCHED_MS + 1)
        relay.reachable = true
        runCurrent()

        val update = assertIs<ShareMessage.Update>(publishes.last().message())
        assertEquals(3, update.route.size)
        assertEquals(false, assertIs<LiveShareStatus.Live>(engine.status.value).offline)
    }

    @Test
    fun pausingReachesViewersAtOnce() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        relay.watchers = 1
        engine.share()
        runCurrent()
        val before = publishes.size

        workouts.value = walking(2, phase = WorkoutPhase.Paused)
        runCurrent()

        assertEquals(before + 1, publishes.size)
        assertEquals(SharePhase.Paused, publishes.last().message().state.phase)
    }

    @Test
    fun heartRateIsSharedOnlyWhenChosen() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        engine.share()
        runCurrent()
        assertNull(publishes.last().message().state.heartRateBpm)
        engine.stopSharing()

        engine.setShareHeartRateByDefault(true)
        engine.share()
        runCurrent()
        assertEquals(120, publishes.last().message().state.heartRateBpm)
        engine.stopSharing()

        engine.share(includeHeartRate = false)
        runCurrent()
        assertNull(publishes.last().message().state.heartRateBpm)
    }

    @Test
    fun theShareEndsWithTheWorkout() = runTest {
        workouts.value = walking(3)
        val engine = engine()
        engine.share()
        runCurrent()
        val id = relay.requests.first().shareId

        workouts.value = WorkoutStatus()
        runCurrent()

        val final = assertIs<ShareMessage.Snapshot>(publishes.last().message())
        assertEquals(SharePhase.Ended, final.state.phase)
        assertEquals(3, final.route.size)
        assertNull(final.state.heartRateBpm)
        assertTrue(id in relay.closed)
        assertIs<LiveShareStatus.Ended>(engine.status.value)
        assertTrue(cipher.secrets.isEmpty())
        assertNull(storage.getString("share.active"))
    }

    @Test
    fun aShareStartedBeforeTheWorkoutWaitsForIt() = runTest {
        val engine = engine()
        engine.share()
        runCurrent()
        assertEquals(SharePhase.Waiting, publishes.single().message().state.phase)

        workouts.value = walking(1)
        runCurrent()
        assertIs<LiveShareStatus.Live>(engine.status.value)
    }

    @Test
    fun aShareComesBackAfterProcessDeathWithoutRepeatingCounters() = runTest {
        workouts.value = walking(2)
        val first = engine()
        relay.watchers = 1
        first.share()
        runCurrent()
        val link = assertIs<LiveShareStatus.Live>(first.status.value).link
        val lastSeq = publishes.last().seq

        // A fresh relay adapter, as the old one went with the process.
        val restarted = MockShareRelayAdapter(watchers = 1)
        val second = LiveShareEngine(workouts, restarted, cipher, { battery }, storage, "https://share.test", backgroundScope, { epoch })
        second.start()
        runCurrent()

        assertEquals(link, assertIs<LiveShareStatus.Live>(second.status.value).link)
        val republished = restarted.requests.filterIsInstance<RelayRequest.Publish>()
        assertTrue(republished.first().seq > lastSeq)
        assertIs<ShareMessage.Snapshot>(republished.first().message())
    }

    @Test
    fun aRelayThatForgotTheShareGetsItBack() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        engine.share()
        runCurrent()
        val id = relay.requests.first().shareId

        relay.forget(id)
        runCurrent()

        assertEquals(2, relay.requests.count { it is RelayRequest.Open })
        assertIs<ShareMessage.Snapshot>(relay.frames.getValue(id).values.single().message())
    }

    @Test
    fun viewersAreStillNoticedAfterTheRelayForgotTheShare() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        engine.share()
        runCurrent()
        val id = relay.requests.first().shareId

        relay.forget(id)
        runCurrent()
        val before = publishes.size
        relay.arrive(id)
        runCurrent()

        assertEquals(before + 1, publishes.size)
        assertIs<ShareMessage.Snapshot>(publishes.last().message())
        assertEquals(1, assertIs<LiveShareStatus.Live>(engine.status.value).watchers)
    }

    @Test
    fun aViewerWhoLeftBeforeTheSnapshotDoesNotHideTheNext() = runTest {
        workouts.value = walking(2)
        val engine = engine()
        engine.share()
        runCurrent()
        val id = relay.requests.first().shareId

        // Gone again by the time the relay confirms the snapshot made for them.
        relay.arrive(id)
        relay.leave(id)
        runCurrent()
        assertEquals(0, assertIs<LiveShareStatus.Live>(engine.status.value).watchers)

        val before = publishes.size
        relay.arrive(id)
        runCurrent()
        assertEquals(before + 1, publishes.size)
        assertEquals(1, assertIs<LiveShareStatus.Live>(engine.status.value).watchers)
    }

    @Test
    fun indoorWorkoutsShareStatsAtAGentlerPace() = runTest {
        workouts.value = WorkoutStatus(phase = WorkoutPhase.Active, kind = WorkoutKind.IndoorWalk, totals = PadCounters(60, 80, 100))
        val engine = engine()
        relay.watchers = 1
        engine.share()
        runCurrent()
        val before = publishes.size

        advanceTimeBy(LiveShareEngine.WATCHED_MS + 1)
        assertEquals(before, publishes.size)
        advanceTimeBy(LiveShareEngine.WATCHED_INDOOR_MS - LiveShareEngine.WATCHED_MS)
        assertEquals(before + 1, publishes.size)
        assertEquals(80, publishes.last().message().state.totals.meters)
        assertTrue(publishes.last().message().route.isEmpty())
        assertEquals(80, publishes.last().message().state.batteryPercent)
    }
}
