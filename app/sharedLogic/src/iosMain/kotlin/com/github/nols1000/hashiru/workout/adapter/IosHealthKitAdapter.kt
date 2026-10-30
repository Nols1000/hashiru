package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.PastWalk
import com.github.nols1000.hashiru.workout.domain.RejectReason
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
import kotlinx.coroutines.CancellationException
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
import platform.HealthKit.HKWorkoutSessionLocationTypeIndoor
import platform.HealthKit.HKWorkoutSessionLocationTypeOutdoor
import platform.HealthKit.countUnit
import platform.HealthKit.gramUnitWithMetricPrefix
import platform.HealthKit.kilocalorieUnit
import platform.HealthKit.meterUnit
import platform.HealthKit.minuteUnit
import platform.HealthKit.predicateForObjectsFromWorkout
import platform.HealthKit.predicateForObjectsWithMetadataKey
import platform.HealthKit.predicateForSamplesWithStartDate
import platform.HealthKit.predicateForWorkoutsWithWorkoutActivityType
import platform.HealthKit.unitDividedByUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Tracks and saves workouts in HealthKit, the newest way the phone supports first.
 *
 * - On iOS 26 and later every workout runs as an iPhone workout session, whose live builder saves it. For a
 *   workout the phone tracks, the session also counts steps and distance.
 * - Before that, or where a session cannot start, [CMPedometer][platform.CoreMotion.CMPedometer] counts for
 *   the phone, and the workout is saved with an [HKWorkoutBuilder] from the recorded samples. A save the live
 *   builder could not finish, such as after a relaunch or on a retry, goes that way too.
 *
 * Either way the workout gets one energy sample per window, plus distance and step samples where the session did
 * not count them, and its pauses, so its duration leaves them out. Every object carries the workout's ID as its
 * external UUID, for [delete], and a sync identifier, so saving a workout again replaces what an interrupted save
 * left behind instead of adding to it.
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
    private val writeTypes = setOf(workoutType, distanceType, stepsType, energyType)
    private val readTypes = writeTypes + setOf(
        bodyMassType,
        heartRateType,
        HKObjectType.characteristicTypeForIdentifier(HKCharacteristicTypeIdentifierDateOfBirth)!!,
        HKObjectType.characteristicTypeForIdentifier(HKCharacteristicTypeIdentifierBiologicalSex)!!,
    )
    private val types = HealthTypes(distanceType, stepsType, energyType)
    private val scope = MainScope()
    private val _events = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)
    override val events: Flow<HealthEvent> = _events
    private val trackers = mutableMapOf<String, PhoneTracker>()
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

    override fun handle(event: WorkoutEvent) {
        // In order, on the main thread.
        scope.launch {
            when (event) {
                is WorkoutEvent.StartRequested -> start(event.id, event.atMs, event.kind, event.source)
                is WorkoutEvent.Restored -> restore(event)
                is WorkoutEvent.Paused -> trackers[event.id]?.pause(event.atMs)
                is WorkoutEvent.Resumed -> trackers[event.id]?.resume(event.atMs)
                is WorkoutEvent.Discarded -> trackers.remove(event.id)?.stop()
                is WorkoutEvent.Finished -> save(event.record)
            }
        }
    }

    private fun emit(event: HealthEvent) {
        _events.tryEmit(event)
    }

    private suspend fun start(id: String, atMs: Long, kind: WorkoutKind, source: WorkoutSource) {
        when (source) {
            // The pad counts; a session only gives the save a live builder.
            WorkoutSource.Pad -> if (liveSessions) {
                (LiveWorkout.begin(store, configuration(kind), id, atMs, false, types, ::emit, scope) as? TrackerStart.Started)
                    ?.let { trackers[id] = it.tracker }
            }
            WorkoutSource.Phone -> {
                // Outdoor workouts need a route, which is not recorded yet.
                val result = if (kind.location == Location.Outdoor) TrackerStart.Rejected(RejectReason.Unsupported) else track(id, atMs, kind)
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

    /** The newest way of counting that works here. */
    private suspend fun track(id: String, atMs: Long, kind: WorkoutKind): TrackerStart {
        if (simulated) return TrackerStart.Started(SimulatedTracker(id, ::emit, scope))
        if (liveSessions) {
            val live = LiveWorkout.begin(store, configuration(kind), id, atMs, true, types, ::emit, scope)
            if (live !is TrackerStart.Failed) return live
        }
        return PedometerTracker.begin(id, atMs, ::emit, scope)
    }

    private suspend fun restore(event: WorkoutEvent.Restored) {
        val countsMotion = event.source == WorkoutSource.Phone
        val recovered = if (liveSessions) LiveWorkout.recover(store, event.id, countsMotion, types, ::emit, scope) else null
        if (recovered != null) {
            trackers[event.id] = recovered
            return
        }
        if (!countsMotion) return
        // Counting again from now; the recorder takes the restart for a new segment.
        val restarted = if (simulated) TrackerStart.Started(SimulatedTracker(event.id, ::emit, scope)) else PedometerTracker.begin(event.id, nowMs(), ::emit, scope)
        if (restarted is TrackerStart.Started) trackers[event.id] = restarted.tracker else emit(HealthEvent.TrackingLost(event.id))
    }

    /** Saves with the workout's live builder where it can, else with a new [HKWorkoutBuilder]. */
    private suspend fun save(record: WorkoutRecord) {
        if (!saving.add(record.id)) return
        try {
            val tracker = trackers.remove(record.id)
            if (tracker is LiveWorkout && tracker.usable) {
                try {
                    saveLive(tracker, record)
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
                    saveWithBuilder(record)
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

    private suspend fun saveLive(workout: LiveWorkout, record: WorkoutRecord) {
        val version = syncVersion()
        // A phone workout's session counted steps and distance and recorded its pauses itself.
        val pad = record.source == WorkoutSource.Pad
        workout.finish(
            endMs = record.endMs,
            samples = samples(record, version, withCounts = pad),
            events = if (pad) pauseEvents(record) else emptyList(),
            metadata = metadata(record, version, "workout"),
        )
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
            store.requestAuthorizationToShareTypes(writeTypes, readTypes = readTypes) { _, error -> cont.complete(error) }
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

    private suspend fun saveWithBuilder(record: WorkoutRecord) {
        val version = syncVersion()
        val builder = HKWorkoutBuilder(healthStore = store, configuration = configuration(record.kind), device = device(record))
        val samples = samples(record, version, withCounts = true)
        val events = pauseEvents(record)

        await { builder.beginCollectionWithStartDate(date(record.startMs)) { _, error -> it.complete(error) } }
        if (samples.isNotEmpty()) await { builder.addSamples(samples) { _, error -> it.complete(error) } }
        if (events.isNotEmpty()) await { builder.addWorkoutEvents(events) { _, error -> it.complete(error) } }
        await { builder.addMetadata(metadata(record, version, "workout")) { _, error -> it.complete(error) } }
        await { builder.endCollectionWithEndDate(date(record.endMs)) { _, error -> it.complete(error) } }
        await { builder.finishWorkoutWithCompletion { _, error -> it.complete(error) } }
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
        for (type in listOf(distanceType, stepsType)) {
            for (fromWorkout in collected) {
                await { store.deleteObjectsOfType(type, predicate = fromWorkout) { _, _, error -> it.complete(error) } }
            }
        }
        for (type in writeTypes) {
            await { store.deleteObjectsOfType(type, predicate = predicate) { _, _, error -> it.complete(error) } }
        }
    }

    override suspend fun requestReadAccess() {
        val status = suspendCancellableCoroutine { cont ->
            store.getRequestStatusForAuthorizationToShareTypes(writeTypes, readTypes = readTypes) { status, _ -> cont.resume(status) }
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
        val ownSource = bundleId != null && sourceRevision.source.bundleIdentifier == bundleId
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

        fun quantityType(identifier: String?): HKQuantityType = HKQuantityType.quantityTypeForIdentifier(identifier!!)!!
    }
}

internal suspend fun await(block: (Continuation<Unit>) -> Unit) = suspendCancellableCoroutine { cont -> block(cont) }

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
