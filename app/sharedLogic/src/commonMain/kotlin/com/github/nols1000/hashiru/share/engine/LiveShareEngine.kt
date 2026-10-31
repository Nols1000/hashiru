package com.github.nols1000.hashiru.share.engine

import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CourseGeometry
import com.github.nols1000.hashiru.share.codec.Base64Url
import com.github.nols1000.hashiru.share.codec.Hkdf
import com.github.nols1000.hashiru.share.codec.SFrame
import com.github.nols1000.hashiru.share.codec.SFrameSealer
import com.github.nols1000.hashiru.share.codec.ShareLink
import com.github.nols1000.hashiru.share.codec.ShareMessageCodec
import com.github.nols1000.hashiru.share.domain.LiveShareStatus
import com.github.nols1000.hashiru.share.domain.ShareMessage
import com.github.nols1000.hashiru.share.domain.ShareNavigation
import com.github.nols1000.hashiru.share.domain.SharePhase
import com.github.nols1000.hashiru.share.domain.ShareState
import com.github.nols1000.hashiru.share.domain.SharedCourse
import com.github.nols1000.hashiru.share.port.BatteryPort
import com.github.nols1000.hashiru.share.port.RelayEvent
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.share.port.ShareCipherPort
import com.github.nols1000.hashiru.share.port.ShareRelayPort
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.engine.WorkoutStatus
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * App-scoped live sharing of the workout in progress through an end-to-end encrypted link. The link's fragment
 * holds the key the frames are sealed with (SFrame) and the public key they are signed with, so the relay passes
 * them on without being able to read or forge them.
 *
 * To spare the battery it publishes only while someone has the link open: every [WATCHED_MS] outdoors, every
 * [WATCHED_INDOOR_MS] indoors, and at once when the workout pauses, resumes, ends or strays from its course. While no
 * one watches it sends nothing; the relay keeps the last snapshot and the adapter reports when a viewer arrives.
 * Each update carries the route since the last one the relay confirmed, so a lost update does no harm.
 *
 * The share survives process death. The frame counter, which the relay orders frames by, is reserved on disk
 * before use; as SFrame takes it for the nonce, each run of the process also seals under a key id of its own, so
 * a lost write can never repeat a nonce under one key. It ends with the workout, keeping the link readable for
 * viewers until the relay forgets it.
 */
class LiveShareEngine internal constructor(
    private val workouts: StateFlow<WorkoutStatus>,
    private val relay: ShareRelayPort,
    private val cipher: ShareCipherPort,
    private val battery: BatteryPort,
    private val storage: KeyValueStorePort,
    /** Where viewers open links, such as `https://share.example.org`. */
    private val viewerUrl: String,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    /** Creates the engine on the main thread for the rest of the process; keep a single instance. */
    @OptIn(ExperimentalTime::class)
    constructor(
        recorder: WorkoutRecorder,
        relay: ShareRelayPort,
        cipher: ShareCipherPort,
        battery: BatteryPort,
        storage: KeyValueStorePort,
        viewerUrl: String,
    ) : this(
        recorder.status,
        relay,
        cipher,
        battery,
        storage,
        viewerUrl,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        { Clock.System.now().toEpochMilliseconds() },
    )

    private val _status = MutableStateFlow<LiveShareStatus>(LiveShareStatus.Off)
    val status: StateFlow<LiveShareStatus> = _status.asStateFlow()

    private val _shareHeartRateByDefault = MutableStateFlow(storage.getString(KEY_HEART_RATE) == "1")

    /** Whether new shares include heart rate; each share can differ. */
    val shareHeartRateByDefault: StateFlow<Boolean> = _shareHeartRateByDefault.asStateFlow()

    private var share: ActiveShare? = null
    private var started = false

    /** The workout as last seen in progress, for the final snapshot. */
    private var lastActive: WorkoutStatus? = null

    fun start() {
        if (started) return
        started = true
        // Listening before anything is sent, so no answer is missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { relay.events.collect(::onRelayEvent) }
        restore()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { workouts.collect(::onWorkout) }
    }

    /** Starts sharing the workout in progress, or the next one; [includeHeartRate] overrides the default for this share. */
    fun share(includeHeartRate: Boolean = _shareHeartRateByDefault.value) {
        if (share != null) return
        val id = Base64Url.encode(cipher.randomBytes(ID_BYTES))
        val alias = "$ALIAS_PREFIX$id"
        val baseKey = cipher.randomBytes(SFrame.KEY_BYTES)
        cipher.putSecret(alias, baseKey)
        val signingKey = cipher.createSigningKey(alias)
        val active = ActiveShare(
            id = id,
            writeToken = cipher.randomBytes(TOKEN_BYTES),
            alias = alias,
            signingKey = signingKey,
            baseKey = baseKey,
            includeHeartRate = includeHeartRate,
            reservedSeq = 0,
            nextSeq = 0,
            sawWorkout = workouts.value.phase != WorkoutPhase.Idle,
        )
        active.moment = phaseOf(workouts.value, active) to workouts.value.navigation?.onCourse
        share = active
        checkpoint(active)
        open(active)
        publishStatus()
    }

    /** Ends the share before the workout ends; viewers see that sharing stopped. */
    fun stopSharing() {
        val active = share ?: return
        finish(active, workouts.value)
        _status.value = LiveShareStatus.Off
    }

    /** Hides an ended share. */
    fun dismissEnded() {
        if (_status.value is LiveShareStatus.Ended) _status.value = LiveShareStatus.Off
    }

    fun setShareHeartRateByDefault(include: Boolean) {
        storage.putString(KEY_HEART_RATE, if (include) "1" else null)
        _shareHeartRateByDefault.value = include
    }

    private fun restore() {
        val active = storage.getString(KEY_ACTIVE)?.let(::decodeCheckpoint) ?: run {
            storage.putString(KEY_ACTIVE, null)
            return
        }
        share = active
        // Counters up to the reservation may have been used before the process died.
        active.nextSeq = active.reservedSeq
        checkpoint(active)
        open(active)
        publishStatus()
    }

    private fun open(active: ActiveShare) {
        val presence = Hkdf(cipher).let { it.expand(it.extract(ByteArray(0), active.baseKey), PRESENCE_LABEL, PRESENCE_BYTES) }
        relay.handle(RelayRequest.Open(active.id, active.writeToken, presence))
    }

    private fun onWorkout(status: WorkoutStatus) {
        val inProgress = status.phase != WorkoutPhase.Idle
        if (inProgress) lastActive = status
        val active = share ?: return
        if (inProgress && !active.sawWorkout) {
            active.sawWorkout = true
            checkpoint(active)
        }
        if (!inProgress && active.sawWorkout && !status.starting) {
            finish(active, status)
            _status.value = LiveShareStatus.Ended(active.link)
            return
        }
        if (status.totals.meters - active.anchorMeters >= MOVED_METERS || active.lastMovedMs == null && status.totals.meters > 0) {
            active.anchorMeters = status.totals.meters
            active.lastMovedMs = nowMs()
        }
        // Pauses, resumes and leaving or rejoining the course reach viewers at once.
        val moment = phaseOf(status, active) to status.navigation?.onCourse
        if (moment != active.moment) {
            val first = active.moment == null
            active.moment = moment
            if (!first && active.watchers > 0) publish(active, snapshot = false)
        }
    }

    private fun onRelayEvent(event: RelayEvent) {
        val active = share ?: return
        when (event) {
            is RelayEvent.Opened -> if (event.shareId == active.id) publish(active, snapshot = true)
            is RelayEvent.Acked -> if (event.shareId == active.id) {
                active.pending.remove(event.seq)?.let { active.ackedRoute = maxOf(active.ackedRoute, it) }
                active.pending.keys.removeAll { it < event.seq }
                watchers(active, event.watchers)
            }
            is RelayEvent.Watchers -> if (event.shareId == active.id) watchers(active, event.count)
            RelayEvent.Unreachable -> {
                active.offline = true
                publishStatus()
            }
            RelayEvent.Reachable -> {
                active.offline = false
                publishStatus()
                if (active.watchers > 0) publish(active, snapshot = false)
            }
            is RelayEvent.Gone -> if (event.shareId == active.id) {
                active.ackedRoute = 0
                active.pending.clear()
                // The relay forgot the viewers the adapter was waiting for along with the share.
                active.awaiting = false
                open(active)
            }
            is RelayEvent.Refused -> if (event.shareId == active.id) {
                clear(active)
                _status.value = LiveShareStatus.Failed
            }
        }
    }

    private fun watchers(active: ActiveShare, count: Int) {
        val before = active.watchers
        active.watchers = count
        if (count > 0) {
            // The adapter stops waiting for viewers once it has seen some.
            active.awaiting = false
            if (before == 0) {
                // Whoever just arrived may be looking at an old snapshot.
                publish(active, snapshot = true)
                tick(active)
            }
        } else {
            active.ticker?.cancel()
            active.ticker = null
            if (!active.awaiting) {
                active.awaiting = true
                relay.handle(RelayRequest.AwaitViewers(active.id, active.writeToken))
            }
        }
        publishStatus()
    }

    private fun tick(active: ActiveShare) {
        active.ticker?.cancel()
        active.ticker = scope.launch {
            while (true) {
                delay(if (workouts.value.kind.location == Location.Indoor) WATCHED_INDOOR_MS else WATCHED_MS)
                publish(active, snapshot = false)
            }
        }
    }

    private fun publish(active: ActiveShare, snapshot: Boolean, final: WorkoutStatus? = null) {
        // Requests reach the relay in order, so frames sent before it confirmed the share follow its opening.
        if (active.offline && final == null) return
        val status = final ?: workouts.value
        val source = final ?: if (status.phase == WorkoutPhase.Idle) lastActive?.takeIf { active.sawWorkout } ?: status else status
        val route = source.route
        val state = ShareState(
            atMs = nowMs(),
            phase = if (final != null) SharePhase.Ended else phaseOf(status, active),
            totals = source.totals,
            heartRateBpm = source.heartRateBpm?.takeIf { active.includeHeartRate && final == null },
            batteryPercent = battery.percent(),
            lastMovedMs = active.lastMovedMs,
            navigation = source.navigation?.let {
                ShareNavigation(it.progressMeters, it.remainingMeters, it.offCourseMeters, it.onCourse, it.finished, it.joined)
            },
        )
        val message = if (snapshot) {
            ShareMessage.Snapshot(source.kind, sharedCourse(active, source.course), state, route)
        } else {
            ShareMessage.Update(state, route.drop(active.ackedRoute))
        }
        val seq = nextSeq(active)
        val metadata = metadata(active.id)
        val frame = active.sealer.seal(seq, metadata, ShareMessageCodec.encode(message))
        val signature = cipher.sign(active.alias, metadata + frame) ?: return
        active.pending[seq] = route.size
        relay.handle(RelayRequest.Publish(active.id, active.writeToken, seq, frame + signature, snapshot))
    }

    /** Sends the last snapshot and closes the share, keeping it for viewers until the relay forgets it. */
    private fun finish(active: ActiveShare, status: WorkoutStatus) {
        val ended = if (active.sawWorkout) {
            // During a workout, the summary is still the one of the walk before.
            status.summary?.takeIf { status.phase == WorkoutPhase.Idle }?.let { record ->
                (lastActive ?: status).copy(
                    totals = PadCounters(record.activeSeconds, record.distanceMeters, record.steps),
                    route = record.route,
                    kind = record.kind,
                )
            } ?: lastActive ?: status
        } else {
            status
        }
        active.offline = false
        publish(active, snapshot = true, final = ended)
        relay.handle(RelayRequest.Close(active.id, active.writeToken))
        clear(active)
    }

    private fun clear(active: ActiveShare) {
        active.ticker?.cancel()
        share = null
        lastActive = null
        storage.putString(KEY_ACTIVE, null)
        cipher.putSecret(active.alias, null)
    }

    private fun nextSeq(active: ActiveShare): Long {
        val seq = active.nextSeq++
        if (active.nextSeq > active.reservedSeq) {
            active.reservedSeq = active.nextSeq + SEQ_RESERVATION
            checkpoint(active)
        }
        return seq
    }

    private fun phaseOf(status: WorkoutStatus, active: ActiveShare) = when {
        status.phase == WorkoutPhase.Active -> SharePhase.Active
        status.phase == WorkoutPhase.Paused -> SharePhase.Paused
        active.sawWorkout -> SharePhase.Ended
        else -> SharePhase.Waiting
    }

    private fun sharedCourse(active: ActiveShare, course: Course?): SharedCourse? {
        if (course == null) return null
        active.course?.takeIf { it.first == course.id }?.let { return it.second }
        val shared = SharedCourse(course.name, course.distanceMeters, CourseGeometry.simplify(course.points, COURSE_TOLERANCE_M, COURSE_MAX_POINTS))
        active.course = course.id to shared
        return shared
    }

    private fun publishStatus() {
        val active = share ?: return
        _status.value = LiveShareStatus.Live(active.link, active.watchers, active.offline, active.includeHeartRate)
    }

    private val ActiveShare.link: String
        get() = ShareLink(id, baseKey, signingKey).url(viewerUrl)

    private fun checkpoint(active: ActiveShare) {
        storage.putString(
            KEY_ACTIVE,
            listOf(
                CHECKPOINT_VERSION, active.id, Base64Url.encode(active.writeToken), Base64Url.encode(active.signingKey),
                active.reservedSeq, if (active.includeHeartRate) 1 else 0, if (active.sawWorkout) 1 else 0,
            ).joinToString("|"),
        )
    }

    private fun decodeCheckpoint(text: String): ActiveShare? = runCatching {
        val f = text.split('|')
        if (f.size != 7 || f[0] != CHECKPOINT_VERSION) return null
        val alias = "$ALIAS_PREFIX${f[1]}"
        ActiveShare(
            id = f[1],
            writeToken = Base64Url.decode(f[2]) ?: return null,
            alias = alias,
            signingKey = Base64Url.decode(f[3]) ?: return null,
            baseKey = cipher.secret(alias) ?: return null,
            includeHeartRate = f[5] == "1",
            reservedSeq = f[4].toLong(),
            nextSeq = f[4].toLong(),
            sawWorkout = f[6] == "1",
        )
    }.getOrNull()

    private inner class ActiveShare(
        val id: String,
        val writeToken: ByteArray,
        val alias: String,
        /** The public half, uncompressed; the private half stays with the platform under [alias]. */
        val signingKey: ByteArray,
        val baseKey: ByteArray,
        val includeHeartRate: Boolean,
        /** Counters below this may have been used; it is on disk before any is. */
        var reservedSeq: Long,
        var nextSeq: Long,
        /** A workout was in progress while sharing, so the share ends with it. */
        var sawWorkout: Boolean,
    ) {
        /** A random key id for this run of the process; see the class comment. */
        val sealer by lazy { SFrameSealer(cipher, baseKey, Random.nextLong(1L shl 31)) }
        var offline = false
        var watchers = 0
        /** The adapter is waiting for viewers, as asked by [RelayRequest.AwaitViewers]. */
        var awaiting = false
        var ticker: Job? = null

        /** Route points the relay confirmed; updates carry the rest. */
        var ackedRoute = 0

        /** Route points each unconfirmed frame carried, by counter. */
        val pending = mutableMapOf<Long, Int>()
        var anchorMeters = 0
        var lastMovedMs: Long? = null
        var moment: Pair<SharePhase, Boolean?>? = null
        var course: Pair<String, SharedCourse>? = null
    }

    companion object {
        const val WATCHED_MS = 15_000L
        const val WATCHED_INDOOR_MS = 30_000L

        /** The distance that counts as moving on, for viewers who wonder whether the walker stopped. */
        private const val MOVED_METERS = 15
        private const val ID_BYTES = 16
        private const val TOKEN_BYTES = 32
        private const val PRESENCE_BYTES = 32
        private const val SEQ_RESERVATION = 64
        private const val COURSE_TOLERANCE_M = 5.0
        private const val COURSE_MAX_POINTS = 1_000
        private const val CHECKPOINT_VERSION = "1"
        private const val KEY_ACTIVE = "share.active"
        private const val KEY_HEART_RATE = "share.includeHeartRate"
        private const val ALIAS_PREFIX = "share."
        internal val PRESENCE_LABEL = "hashiru presence".encodeToByteArray()

        /** Authenticated with each frame, so the relay cannot pass one share's frames off as another's. */
        internal fun metadata(shareId: String) = "hashiru share $shareId".encodeToByteArray()
    }
}
