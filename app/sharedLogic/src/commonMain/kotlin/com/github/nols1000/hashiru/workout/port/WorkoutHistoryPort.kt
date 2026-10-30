package com.github.nols1000.hashiru.workout.port

import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.PastWalk

/** Driven port: walking workouts read back from the platform health store, whichever app recorded them. */
interface WorkoutHistoryPort {
    /**
     * Walks newest first that started at or after [sinceMs] and ended before [beforeMs], at most [limit] of them.
     * A store that hides denied reads returns nothing then, so an empty list may mean either.
     */
    suspend fun walks(sinceMs: Long?, beforeMs: Long?, limit: Int): List<PastWalk>

    /** Heart-rate readings during [walk], oldest first. */
    suspend fun heartRate(walk: PastWalk): List<HeartRateSample>

    /** Shows the system's permission sheet for reading walks, unless the user has already answered it. */
    suspend fun requestReadAccess()

    /** Removes a walk this app recorded, with its samples. */
    suspend fun delete(walk: PastWalk)
}
