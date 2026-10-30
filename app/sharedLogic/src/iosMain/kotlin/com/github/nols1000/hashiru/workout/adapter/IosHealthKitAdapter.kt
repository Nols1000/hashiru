package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.PastWalk
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import com.github.nols1000.hashiru.workout.port.HealthStoreBusyException
import com.github.nols1000.hashiru.workout.port.HealthSyncPort
import com.github.nols1000.hashiru.workout.port.WorkoutHistoryPort
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import com.github.nols1000.hashiru.workout.codec.SessionMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateInterval
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSPredicate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSUUID
import platform.CoreLocation.CLLocation
import platform.HealthKit.HKAuthorizationRequestStatusShouldRequest
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKAuthorizationStatusSharingDenied
import platform.HealthKit.HKBiologicalSexFemale
import platform.HealthKit.HKBiologicalSexMale
import platform.HealthKit.HKCharacteristicTypeIdentifierBiologicalSex
import platform.HealthKit.HKCharacteristicTypeIdentifierDateOfBirth
import platform.HealthKit.HKDevice
import platform.HealthKit.HKErrorDatabaseInaccessible
import platform.HealthKit.HKErrorDomain
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKMetadataKeyExternalUUID
import platform.HealthKit.HKMetadataKeyIndoorWorkout
import platform.HealthKit.HKMetadataKeySyncIdentifier
import platform.HealthKit.HKMetadataKeySyncVersion
import platform.HealthKit.HKMetricPrefixKilo
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierBasalEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierBodyMass
import platform.HealthKit.HKQuantityTypeIdentifierDistanceWalkingRunning
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKQuery
import platform.HealthKit.HKQueryOptionNone
import platform.HealthKit.HKQueryOptionStrictEndDate
import platform.HealthKit.HKQueryOptionStrictStartDate
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKSeriesType
import platform.HealthKit.HKSampleSortIdentifierEndDate
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityTypeRunning
import platform.HealthKit.HKWorkoutActivityTypeWalking
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutEvent
import platform.HealthKit.HKWorkoutEventType
import platform.HealthKit.HKWorkoutEventTypePause
import platform.HealthKit.HKWorkoutEventTypeResume
import platform.HealthKit.HKWorkoutRoute
import platform.HealthKit.HKWorkoutRouteBuilder
import platform.HealthKit.HKWorkoutRouteQuery
import platform.HealthKit.HKWorkoutSession
import platform.HealthKit.HKWorkoutSessionLocationTypeIndoor
import platform.HealthKit.HKWorkoutSessionLocationTypeOutdoor
import platform.HealthKit.countUnit
import platform.HealthKit.gramUnitWithMetricPrefix
import platform.HealthKit.kilocalorieUnit
import platform.HealthKit.meterUnit
import platform.HealthKit.minuteUnit
import platform.HealthKit.predicateForObjectWithUUID
import platform.HealthKit.predicateForObjectsFromWorkout
import platform.HealthKit.predicateForObjectsWithMetadataKey
import platform.HealthKit.predicateForSamplesWithStartDate
import platform.HealthKit.predicateForWorkoutsWithWorkoutActivityType
import platform.HealthKit.startWatchAppWithWorkoutConfiguration
import platform.HealthKit.unitDividedByUnit
import platform.HealthKit.workoutSessionMirroringStartHandler
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Tracks and saves workouts in HealthKit, the newest way available first. Wherever a workout session runs, it is
 * the one source of the workout's time, energy and heart rate, and holds the pad's counts as samples.
 *
 * - With the Apple Watch app, the watch runs the session and mirrors it to the phone ([MirroredWorkout]); the
 *   watch measures and saves the workout.
 * - Otherwise, on iOS 26 and later, the phone runs the session ([LiveWorkout]), whose live builder saves it. For a
 *   workout the phone tracks, the session also counts steps and distance.
 * - Before that, or where no session starts, the phone's clock times a pad walk and
 *   [CMPedometer][platform.CoreMotion.CMPedometer] counts for a phone workout; the recorder estimates energy, and
 *   the workout is saved with an [HKWorkoutBuilder] from the recorded samples and pauses. A save that a session
 *   could not finish, such as after a relaunch or on a retry, goes that way too.
 *
 * An outdoor workout's route comes from the phone's GPS ([RouteTracker]) wherever the phone records it, and goes
 * to the recorder for the map and the record. The watch records and saves its own; every other save attaches the
 * record's route with an [HKWorkoutRouteBuilder].
 *
 * Every object carries the workout's ID as its external UUID, for [delete], and the builder's save a sync
 * identifier, so saving a workout again replaces what an interrupted save left behind instead of adding to it.
 *
 * It also reads walking and running workouts back, from every source, for the history pages. With [simulated],
 * phone workouts count on their own, since the simulator has no motion sensors.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHealthKitAdapter(private val simulated: Boolean = false) : HealthSyncPort, WorkoutHistoryPort {
    private val store = HKHealthStore()
    private val workoutType = HKObjectType.workoutType()
    private val distanceType = quantityType(HKQuantityTypeIdentifierDistanceWalkingRunning)
    private val stepsType = quantityType(HKQuantityTypeIdentifierStepCount)
    private val energyType = quantityType(HKQuantityTypeIdentifierActiveEnergyBurned)
    private val bodyMassType = quantityType(HKQuantityTypeIdentifierBodyMass)
    private val heartRateType = quantityType(HKQuantityTypeIdentifierHeartRate)
    private val basalType = quantityType(HKQuantityTypeIdentifierBasalEnergyBurned)
    private val routeType = HKSeriesType.workoutRouteType()
    private val writeTypes = setOf(workoutType, distanceType, stepsType, energyType, basalType)

    /** What is asked for: [writeTypes] decide whether access counts as granted, as routes came later. */
    private val shareTypes = writeTypes + routeType
    private val readTypes = shareTypes + setOf(
        bodyMassType,
        heartRateType,
        HKObjectType.characteristicTypeForIdentifier(HKCharacteristicTypeIdentifierDateOfBirth)!!,
        HKObjectType.characteristicTypeForIdentifier(HKCharacteristicTypeIdentifierBiologicalSex)!!,
    )
    private val types = HealthTypes(distanceType, stepsType, energyType, basalType, heartRateType)
    private val scope = MainScope()
    private val _events = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)
    override val events: Flow<HealthEvent> = _events
    private val trackers = mutableMapOf<String, PhoneTracker>()
    private val routes = mutableMapOf<String, RouteTracker>()
    private val saving = mutableSetOf<String>()
    private val liveSessions: Boolean by lazy {
        !simulated && NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(cValue<NSOperatingSystemVersion> { majorVersion = 26 })
    }
    private val padDevice = HKDevice(
        name = "WalkingPad",
        manufacturer = "KingSmith",
        model = null,
        hardwareVersion = null,
        firmwareVersion = null,
        softwareVersion = null,
        localIdentifier = null,
        UDIDeviceIdentifier = null,
    )

    /** A start or restore waiting for the watch to mirror its session. */
    private var pendingMirror: CompletableDeferred<HKWorkoutSession>? = null

    /** A mirror nobody asked for yet, as after a relaunch before the walk is restored; dropped after a while. */
    private var unclaimedMirror: HKWorkoutSession? = null

    init {
        // Set at launch, so a mirror is caught even when it relaunches the app; it comes again after each reconnect.
        store.workoutSessionMirroringStartHandler = { mirror: HKWorkoutSession? -> if (mirror != null) scope.launch { onMirror(mirror) } }
    }

    private fun onMirror(mirror: HKWorkoutSession) {
        pendingMirror?.takeIf { !it.isCompleted }?.let {
            it.complete(mirror)
            return
        }
        trackers.values.filterIsInstance<MirroredWorkout>().firstOrNull()?.let {
            it.reattach(mirror)
            return
        }
        unclaimedMirror = mirror
        scope.launch {
            delay(UNCLAIMED_MIRROR_MS)
            // Nothing on this phone belongs to it: the watch drops it rather than recording on its own.
            if (unclaimedMirror === mirror) {
                unclaimedMirror = null
                MirroredWorkout.send(mirror, SessionMessage.Discard)
            }
        }
    }

    /** Has the watch app start a session for the workout and waits for its mirror; null without a watch that answers. */
    private suspend fun mirror(kind: WorkoutKind, launch: Boolean): HKWorkoutSession? {
        unclaimedMirror?.let {
            unclaimedMirror = null
            return it
        }
        val mirror = CompletableDeferred<HKWorkoutSession>()
        pendingMirror = mirror
        try {
            if (launch) {
                val launched = suspendCancellableCoroutine { cont ->
                    store.startWatchAppWithWorkoutConfiguration(configuration(kind)) { ok, error ->
                        error?.let { println("IosHealthKitAdapter: no watch workout: ${it.localizedDescription}") }
                        cont.resume(ok)
                    }
                }
                if (!launched) return null
            }
            return withTimeoutOrNull(MIRROR_TIMEOUT) { mirror.await() }
        } finally {
            pendingMirror = null
        }
    }

    override fun handle(event: WorkoutEvent) {
        // In order, on the main thread.
        scope.launch {
            when (event) {
                is WorkoutEvent.StartRequested -> start(event.id, event.atMs, event.kind, event.source)
                is WorkoutEvent.Restored -> restore(event)
                is WorkoutEvent.Paused -> {
                    routes[event.id]?.pause()
                    trackers[event.id]?.pause(event.atMs)
                }
                is WorkoutEvent.Resumed -> {
                    routes[event.id]?.resume()
                    trackers[event.id]?.resume(event.atMs, event.activeSeconds)
                }
                is WorkoutEvent.Counted -> trackers[event.id]?.count(event)
                is WorkoutEvent.Discarded -> {
                    routes.remove(event.id)?.stop()
                    trackers.remove(event.id)?.stop()
                }
                is WorkoutEvent.Finished -> {
                    routes.remove(event.record.id)?.stop()
                    save(event.record)
                }
            }
        }
    }

    private fun emit(event: HealthEvent) {
        _events.tryEmit(event)
    }

    private suspend fun start(id: String, atMs: Long, kind: WorkoutKind, source: WorkoutSource) {
        // Outdoors, the route comes first: without location access there is no workout to start.
        val route = if (kind.location == Location.Outdoor) {
            requestRouteAccess()
            when (val started = RouteTracker.begin(id, ::emit)) {
                is RouteStart.Started -> started.tracker
                is RouteStart.Rejected -> {
                    emit(HealthEvent.Rejected(id, started.reason))
                    return
                }
            }
        } else {
            null
        }
        route?.let { routes[id] = it }
        val mirror = if (simulated) null else mirror(kind, launch = true)
        if (mirror != null) {
            trackers[id] = MirroredWorkout(id, mirror, ::emit, scope).also { it.start(atMs, kind, source) }
            if (source == WorkoutSource.Phone) emit(HealthEvent.Tracking(id))
            return
        }
        when (source) {
            // The pad counts; a session times the walk and estimates its energy, else the phone's clock times it.
            WorkoutSource.Pad -> {
                val live = if (liveSessions) LiveWorkout.begin(store, configuration(kind), id, atMs, false, types, padDevice, ::emit, scope) else null
                trackers[id] = (live as? TrackerStart.Started)?.tracker ?: ClockTracker(id, atMs, ::emit, scope)
            }
            WorkoutSource.Phone -> {
                val result = track(id, atMs, kind, route)
                if (result !is TrackerStart.Started) routes.remove(id)?.stop()
                when (result) {
                    is TrackerStart.Started -> {
                        trackers[id] = result.tracker
                        emit(HealthEvent.Tracking(id))
                    }
                    is TrackerStart.Rejected -> emit(HealthEvent.Rejected(id, result.reason))
                    TrackerStart.Failed -> emit(HealthEvent.Rejected(id, RejectReason.Unsupported))
                }
            }
        }
    }

    /** The newest way of counting that works here; outdoors, [route] measures the distance where that can't. */
    private suspend fun track(id: String, atMs: Long, kind: WorkoutKind, route: RouteTracker?): TrackerStart {
        if (simulated) return TrackerStart.Started(SimulatedTracker(id, ::emit, scope))
        if (liveSessions) {
            val live = LiveWorkout.begin(store, configuration(kind), id, atMs, true, types, padDevice, ::emit, scope, route)
            if (live !is TrackerStart.Failed) return live
        }
        return PedometerTracker.begin(id, atMs, ::emit, scope, route)
    }

    /** Asks for writing routes once, where walks are already saved; the sheet lists only what is new. */
    private suspend fun requestRouteAccess() {
        if (authorizationStatus() != HealthAuthorization.Granted) return
        val status = suspendCancellableCoroutine { cont ->
            store.getRequestStatusForAuthorizationToShareTypes(setOf(routeType), readTypes = setOf(routeType)) { status, _ -> cont.resume(status) }
        }
        if (status == HKAuthorizationRequestStatusShouldRequest) requestAuthorization()
    }

    private suspend fun restore(event: WorkoutEvent.Restored) {
        // The route goes on from now; what came before the restart is in the recorder's checkpoint.
        val route = if (event.kind.location == Location.Outdoor) RouteTracker.resume(event.id, ::emit) else null
        route?.let { routes[event.id] = it }
        // The watch's session ran on while the app was gone; it mirrors again once the app is back.
        val mirror = if (simulated) null else mirror(event.kind, launch = false)
        if (mirror != null) {
            trackers[event.id] = MirroredWorkout(event.id, mirror, ::emit, scope)
            return
        }
        val countsMotion = event.source == WorkoutSource.Phone
        val recovered = if (liveSessions) LiveWorkout.recover(store, event.id, countsMotion, types, padDevice, ::emit, scope, route) else null
        if (recovered != null) {
            trackers[event.id] = recovered
            return
        }
        if (!countsMotion) {
            // The pad counts on; the phone's clock times the walk from the time it has once it resumes.
            trackers[event.id] = ClockTracker(event.id, null, ::emit, scope)
            return
        }
        // Counting again from now; the recorder takes the restart for a new segment.
        val restarted = if (simulated) TrackerStart.Started(SimulatedTracker(event.id, ::emit, scope)) else PedometerTracker.begin(event.id, nowMs(), ::emit, scope, route)
        if (restarted is TrackerStart.Started) {
            trackers[event.id] = restarted.tracker
        } else {
            routes.remove(event.id)?.stop()
            emit(HealthEvent.TrackingLost(event.id))
        }
    }

    /** Saves with the workout's live builder where it can, else with a new [HKWorkoutBuilder]. */
    private suspend fun save(record: WorkoutRecord) {
        if (!saving.add(record.id)) return
        try {
            val tracker = trackers.remove(record.id)
            if (tracker is MirroredWorkout) {
                val result = tracker.finish(record.endMs, syncVersion())
                if (result?.ok == true) {
                    emit(HealthEvent.Saved(record.id))
                    return
                }
                println("IosHealthKitAdapter: the watch did not save the walk (${result?.message ?: "no answer"}); saving here")
            } else if (tracker is LiveWorkout && tracker.usable) {
                try {
                    saveRoute(record, saveLive(tracker, record))
                    emit(HealthEvent.Saved(record.id))
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("IosHealthKitAdapter: live save failed, saving again: ${e.message}")
                }
            } else {
                tracker?.stop()
            }
            emit(
                try {
                    saveRoute(record, saveWithBuilder(record))
                    HealthEvent.Saved(record.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HealthStoreBusyException) {
                    HealthEvent.SaveDeferred(record.id)
                } catch (e: Exception) {
                    HealthEvent.SaveFailed(record.id, e.message ?: "Could not save the workout")
                },
            )
        } finally {
            saving.remove(record.id)
        }
    }

    private suspend fun saveLive(workout: LiveWorkout, record: WorkoutRecord): HKWorkout? {
        val version = syncVersion()
        return workout.finish(endMs = record.endMs, metadata = metadata(record, version, "workout"))
    }

    /**
     * Attaches the record's route to the saved [workout], replacing one an earlier save attached. The workout is
     * saved either way, so a route that fails is only logged.
     */
    private suspend fun saveRoute(record: WorkoutRecord, workout: HKWorkout?) {
        if (workout == null || record.route.isEmpty()) return
        try {
            val predicate = HKQuery.predicateForObjectsWithMetadataKey(HKMetadataKeyExternalUUID, allowedValues = listOf(record.id))
            await { store.deleteObjectsOfType(routeType, predicate = predicate) { _, _, error -> it.complete(error) } }
            val builder = HKWorkoutRouteBuilder(healthStore = store, device = null)
            for (chunk in record.route.chunked(ROUTE_CHUNK)) {
                await { builder.insertRouteData(chunk.map { it.toLocation() }) { _, error -> it.complete(error) } }
            }
            await { builder.finishRouteWithWorkout(workout, metadata = mapOf<Any?, Any?>(HKMetadataKeyExternalUUID to record.id)) { _, error -> it.complete(error) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("IosHealthKitAdapter: could not save the route: ${e.message}")
        }
    }

    override fun availability(): HealthAvailability =
        if (HKHealthStore.isHealthDataAvailable()) HealthAvailability.Available else HealthAvailability.NotSupported

    override suspend fun authorizationStatus(): HealthAuthorization {
        val statuses = writeTypes.map { store.authorizationStatusForType(it) }
        return when {
            statuses.all { it == HKAuthorizationStatusSharingAuthorized } -> HealthAuthorization.Granted
            statuses.any { it == HKAuthorizationStatusSharingDenied } -> HealthAuthorization.Denied
            else -> HealthAuthorization.NotDetermined
        }
    }

    override suspend fun requestAuthorization(): HealthAuthorization {
        suspendCancellableCoroutine { cont ->
            store.requestAuthorizationToShareTypes(shareTypes, readTypes = readTypes) { _, error -> cont.complete(error) }
        }
        return authorizationStatus()
    }

    override suspend fun latestBodyMassKg(): Double? = suspendCancellableCoroutine { cont ->
        val query = HKSampleQuery(
            sampleType = bodyMassType,
            predicate = null,
            limit = 1u,
            sortDescriptors = listOf(NSSortDescriptor(key = HKSampleSortIdentifierEndDate, ascending = false)),
        ) { _, results, _ ->
            // A denied read looks like no data; either way there is no weight to use.
            val sample = results?.firstOrNull() as? HKQuantitySample
            cont.resume(sample?.quantity?.doubleValueForUnit(HKUnit.gramUnitWithMetricPrefix(HKMetricPrefixKilo)))
        }
        store.executeQuery(query)
    }

    /** A denied read throws, like a missing value; either way that part of the profile is unknown. */
    override suspend fun profile(): UserProfile {
        val calendar = NSCalendar.currentCalendar
        val age = store.dateOfBirthComponentsWithError(null)?.year?.takeIf { it > 0 }?.let {
            (calendar.component(NSCalendarUnitYear, fromDate = NSDate()) - it).toInt()
        }
        val sex = when (store.biologicalSexWithError(null)?.biologicalSex) {
            HKBiologicalSexFemale -> Sex.Female
            HKBiologicalSexMale -> Sex.Male
            else -> null
        }
        return UserProfile(age, sex)
    }

    private suspend fun saveWithBuilder(record: WorkoutRecord): HKWorkout? {
        val version = syncVersion()
        val builder = HKWorkoutBuilder(healthStore = store, configuration = configuration(record.kind), device = device(record))
        val samples = samples(record, version, withCounts = true)
        val events = pauseEvents(record)

        await { builder.beginCollectionWithStartDate(date(record.startMs)) { _, error -> it.complete(error) } }
        if (samples.isNotEmpty()) await { builder.addSamples(samples) { _, error -> it.complete(error) } }
        if (events.isNotEmpty()) await { builder.addWorkoutEvents(events) { _, error -> it.complete(error) } }
        await { builder.addMetadata(metadata(record, version, "workout")) { _, error -> it.complete(error) } }
        await { builder.endCollectionWithEndDate(date(record.endMs)) { _, error -> it.complete(error) } }
        return builder.finish()
    }

    /** Newer than any earlier attempt, so HealthKit replaces what that attempt saved. */
    private fun syncVersion() = nowMs()

    private fun metadata(record: WorkoutRecord, version: Long, kind: String, index: Int? = null): Map<Any?, *> = mapOf(
        HKMetadataKeyIndoorWorkout to (record.kind.location == Location.Indoor),
        HKMetadataKeyExternalUUID to record.id,
        HKMetadataKeySyncIdentifier to if (index == null) "${record.id}-$kind" else "${record.id}-$kind-$index",
        HKMetadataKeySyncVersion to version,
    )

    /** One energy sample per window, and distance and steps [withCounts]. */
    private fun samples(record: WorkoutRecord, version: Long, withCounts: Boolean): List<HKQuantitySample> {
        val device = device(record)
        return record.samples.flatMapIndexed { i, s ->
            val start = date(s.startMs)
            val end = date(s.endMs)
            fun sample(type: HKQuantityType, quantity: HKQuantity, kind: String) =
                HKQuantitySample.quantitySampleWithType(type, quantity, start, end, device, metadata(record, version, kind, i))
            listOfNotNull(
                s.distanceMeters.takeIf { withCounts && it > 0 }?.let {
                    sample(distanceType, HKQuantity.quantityWithUnit(HKUnit.meterUnit(), it.toDouble()), "distance")
                },
                s.steps.takeIf { withCounts && it > 0 }?.let {
                    sample(stepsType, HKQuantity.quantityWithUnit(HKUnit.countUnit(), it.toDouble()), "steps")
                },
                sample(energyType, HKQuantity.quantityWithUnit(HKUnit.kilocalorieUnit(), s.activeKcal), "energy"),
            )
        }
    }

    private fun pauseEvents(record: WorkoutRecord) = record.pauses.flatMap {
        listOf(event(HKWorkoutEventTypePause, it.startMs), event(HKWorkoutEventTypeResume, it.endMs))
    }

    private fun device(record: WorkoutRecord) = when (record.source) {
        WorkoutSource.Pad -> padDevice
        WorkoutSource.Phone -> HKDevice.localDevice()
    }

    private fun configuration(kind: WorkoutKind) = HKWorkoutConfiguration().apply {
        activityType = when (kind.activity) {
            Activity.Walking -> HKWorkoutActivityTypeWalking
            Activity.Running -> HKWorkoutActivityTypeRunning
        }
        locationType = when (kind.location) {
            Location.Indoor -> HKWorkoutSessionLocationTypeIndoor
            Location.Outdoor -> HKWorkoutSessionLocationTypeOutdoor
        }
    }

    override suspend fun delete(record: WorkoutRecord) = deleteRecord(record.id)

    override suspend fun delete(walk: PastWalk) {
        deleteRecord(walk.recordId ?: return)
    }

    /** Removes the workout, the samples it carries the ID on, and those a live session collected for it. */
    private suspend fun deleteRecord(id: String) {
        val predicate = HKQuery.predicateForObjectsWithMetadataKey(HKMetadataKeyExternalUUID, allowedValues = listOf(id))
        val collected = samples(workoutType, predicate, NO_LIMIT, ascending = true).filterIsInstance<HKWorkout>()
            .map { HKQuery.predicateForObjectsFromWorkout(it) }
        for (type in listOf(distanceType, stepsType, energyType, basalType)) {
            for (fromWorkout in collected) {
                await { store.deleteObjectsOfType(type, predicate = fromWorkout) { _, _, error -> it.complete(error) } }
            }
        }
        for (type in shareTypes) {
            await { store.deleteObjectsOfType(type, predicate = predicate) { _, _, error -> it.complete(error) } }
        }
    }

    override suspend fun requestReadAccess() {
        val status = suspendCancellableCoroutine { cont ->
            store.getRequestStatusForAuthorizationToShareTypes(shareTypes, readTypes = readTypes) { status, _ -> cont.resume(status) }
        }
        if (status == HKAuthorizationRequestStatusShouldRequest) requestAuthorization()
    }

    override suspend fun walks(sinceMs: Long?, beforeMs: Long?, limit: Int): List<PastWalk> {
        val predicates = listOfNotNull<NSPredicate>(
            NSCompoundPredicate.orPredicateWithSubpredicates(
                listOf(
                    HKQuery.predicateForWorkoutsWithWorkoutActivityType(HKWorkoutActivityTypeWalking),
                    HKQuery.predicateForWorkoutsWithWorkoutActivityType(HKWorkoutActivityTypeRunning),
                ),
            ),
            if (sinceMs != null || beforeMs != null) {
                // Inclusive at both ends; the end is made exclusive below.
                HKQuery.predicateForSamplesWithStartDate(
                    sinceMs?.let(::date),
                    endDate = beforeMs?.let(::date),
                    options = HKQueryOptionStrictStartDate or HKQueryOptionStrictEndDate,
                )
            } else {
                null
            },
        )
        val workouts = samples(
            workoutType,
            NSCompoundPredicate.andPredicateWithSubpredicates(predicates),
            limit + 1,
            ascending = false,
        ).filterIsInstance<HKWorkout>()
        return workouts.map { it.toPastWalk() }.filter { beforeMs == null || it.endMs < beforeMs }.take(limit)
    }

    override suspend fun heartRate(walk: PastWalk): List<HeartRateSample> {
        val predicate = HKQuery.predicateForSamplesWithStartDate(date(walk.startMs), endDate = date(walk.endMs), options = HKQueryOptionNone)
        val bpm = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())
        return samples(heartRateType, predicate, NO_LIMIT, ascending = true)
            .filterIsInstance<HKQuantitySample>()
            .map { HeartRateSample(it.startDate.epochMs(), it.quantity.doubleValueForUnit(bpm).roundToInt()) }
    }

    override suspend fun route(walk: PastWalk): List<RoutePoint> {
        val uuid = NSUUID(uUIDString = walk.id)
        val workout = samples(workoutType, HKQuery.predicateForObjectWithUUID(uuid), 1, ascending = true).firstOrNull() as? HKWorkout
            ?: return emptyList()
        return samples(routeType, HKQuery.predicateForObjectsFromWorkout(workout), NO_LIMIT, ascending = true)
            .filterIsInstance<HKWorkoutRoute>()
            .flatMap { locations(it) }
    }

    /** The positions of [route], which HealthKit hands over in batches. */
    private suspend fun locations(route: HKWorkoutRoute): List<RoutePoint> = suspendCancellableCoroutine { cont ->
        val points = mutableListOf<RoutePoint>()
        var finished = false
        val query = HKWorkoutRouteQuery(route) { _, locations, done, error ->
            if (finished) return@HKWorkoutRouteQuery
            when {
                error != null -> {
                    finished = true
                    cont.resumeWithException(error.toException())
                }
                else -> {
                    locations?.filterIsInstance<CLLocation>()?.mapTo(points) { it.toRoutePoint() }
                    if (done) {
                        finished = true
                        cont.resume(points.toList())
                    }
                }
            }
        }
        store.executeQuery(query)
    }

    private suspend fun samples(type: HKSampleType, predicate: NSPredicate?, limit: Int, ascending: Boolean): List<*> =
        suspendCancellableCoroutine { cont ->
            val query = HKSampleQuery(
                sampleType = type,
                predicate = predicate,
                limit = limit.toULong(),
                sortDescriptors = listOf(NSSortDescriptor(key = HKSampleSortIdentifierEndDate, ascending = ascending)),
            ) { _, results, error ->
                if (error == null) cont.resume(results.orEmpty()) else cont.resumeWithException(error.toException())
            }
            store.executeQuery(query)
        }

    private fun HKWorkout.toPastWalk(): PastWalk {
        val metadata = metadata.orEmpty()
        val bundleId = NSBundle.mainBundle.bundleIdentifier
        // The watch app (`<bundle ID>.watchkitapp`) saves the walks it ran the session for.
        val ownSource = bundleId != null && sourceRevision.source.bundleIdentifier.let { it == bundleId || it?.startsWith("$bundleId.") == true }
        fun sum(type: HKQuantityType, unit: HKUnit) = statisticsForType(type)?.sumQuantity()?.doubleValueForUnit(unit)
        return PastWalk(
            id = UUID.UUIDString,
            startMs = startDate.epochMs(),
            endMs = endDate.epochMs(),
            durationSeconds = duration.roundToInt(),
            distanceMeters = sum(distanceType, HKUnit.meterUnit())?.roundToInt() ?: 0,
            steps = sum(stepsType, HKUnit.countUnit())?.roundToInt(),
            activeKcal = sum(energyType, HKUnit.kilocalorieUnit())?.roundToInt(),
            indoor = metadata[HKMetadataKeyIndoorWorkout].isTrue(),
            sourceName = sourceRevision.source.name,
            recordId = (metadata[HKMetadataKeyExternalUUID] as? String)?.takeIf { ownSource },
            activity = if (workoutActivityType == HKWorkoutActivityTypeRunning) Activity.Running else Activity.Walking,
        )
    }

    private fun Any?.isTrue() = when (this) {
        is Boolean -> this
        is NSNumber -> boolValue
        else -> false
    }

    private fun event(type: HKWorkoutEventType, atMs: Long) =
        HKWorkoutEvent.workoutEventWithType(type, dateInterval = NSDateInterval(startDate = date(atMs), duration = 0.0), metadata = null)

    private companion object {
        /** HealthKit's HKObjectQueryNoLimit. */
        const val NO_LIMIT = 0

        /** How long the watch gets to mirror its session after being asked, or after a relaunch. */
        val MIRROR_TIMEOUT = 10.seconds
        const val UNCLAIMED_MIRROR_MS = 15_000L

        /** Positions handed to the route builder at a time. */
        const val ROUTE_CHUNK = 500

        fun quantityType(identifier: String?): HKQuantityType = HKQuantityType.quantityTypeForIdentifier(identifier!!)!!
    }
}

internal suspend fun await(block: (Continuation<Unit>) -> Unit) = suspendCancellableCoroutine { cont -> block(cont) }

/** Finishes the workout and hands it back; throws if it cannot be saved. */
internal suspend fun HKWorkoutBuilder.finish(): HKWorkout? = suspendCancellableCoroutine { cont ->
    finishWorkoutWithCompletion { workout, error -> if (error == null) cont.resume(workout) else cont.resumeWithException(error.toException()) }
}

internal fun Continuation<Unit>.complete(error: NSError?) {
    if (error == null) resume(Unit) else resumeWithException(error.toException())
}

internal fun NSError.toException(): Exception =
    if (domain == HKErrorDomain && code == HKErrorDatabaseInaccessible) {
        // The phone is locked; the workout is saved after the next unlock.
        HealthStoreBusyException(localizedDescription)
    } else {
        IllegalStateException(localizedDescription)
    }
