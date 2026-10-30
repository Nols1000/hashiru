package com.github.nols1000.hashiru.workout.port

import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import kotlinx.coroutines.flow.Flow

enum class HealthAvailability {
    Available,

    /** The platform supports a health store, but it needs installing or updating (Health Connect on Android 9–13). */
    NotInstalled,
    NotSupported,
}

/** Write access to walks. iOS never reveals whether read access was granted, so this tracks writing only. */
enum class HealthAuthorization { NotDetermined, Granted, Denied }

/** Thrown by a store when it cannot be written right now, such as while the phone is locked. */
class HealthStoreBusyException(message: String) : Exception(message)

/**
 * Driven port: the platform health store (HealthKit on iOS, Health Connect on Android), which also tracks
 * workouts the phone records with its own sensors. The recorder tells it what happens to each workout through
 * [handle], and it reports back on [events]; how it tracks and saves is up to the platform.
 */
interface HealthSyncPort {
    /** Answers to [handle], in order. */
    val events: Flow<HealthEvent>

    /** Takes note of [event]; never blocks, answers come on [events]. */
    fun handle(event: WorkoutEvent)

    fun availability(): HealthAvailability

    suspend fun authorizationStatus(): HealthAuthorization

    /** Asks for writing walks, distance, steps and energy, and for reading body weight. */
    suspend fun requestAuthorization(): HealthAuthorization

    /** The most recent body weight in the store, or null when there is none or reading it isn't allowed. */
    suspend fun latestBodyMassKg(): Double?

    /** Age and sex from the store, where it keeps them (HealthKit does, Health Connect does not); null otherwise. */
    suspend fun profile(): UserProfile?

    suspend fun delete(record: WorkoutRecord)
}
