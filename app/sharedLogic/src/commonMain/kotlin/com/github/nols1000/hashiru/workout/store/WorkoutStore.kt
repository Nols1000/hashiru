package com.github.nols1000.hashiru.workout.store

import com.github.nols1000.hashiru.workout.codec.WorkoutCodec
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.engine.WorkoutState
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort

/** Whether the user has answered the offer to save walks to the health store. */
enum class HealthPrompt { NotAsked, Accepted, Declined }

/**
 * What the app keeps on the device: the walk in progress (so it survives process death), walks that still
 * need syncing, the last summary, and the user's profile. There is no history; the health store has that.
 */
class WorkoutStore(private val kv: KeyValueStorePort) {
    /**
     * The walk in progress. Its route only grows, so it is kept apart from the rest: each checkpoint appends the
     * points since the last one as a chunk of its own, and the index says whose route the chunks hold and how many
     * points they hold in all. A checkpoint from before this kept the route inline; it still restores, and the next
     * checkpoint moves the route into chunks.
     */
    var inProgress: WorkoutState.InProgress?
        get() {
            val state = kv.getString(KEY_IN_PROGRESS)?.let(WorkoutCodec::decodeInProgress) ?: return null
            val index = routeIndex()?.takeIf { it.id == state.id } ?: return state
            return state.copy(route = (0 until index.chunks).flatMap { routeChunk(it) })
        }
        set(value) {
            val index = routeIndex()
            if (value == null) {
                clearRoute(index)
                kv.putString(KEY_IN_PROGRESS, null)
                return
            }
            // Chunks of another walk, or a route that did not grow from them, are replaced.
            val kept = index?.takeIf { it.id == value.id && it.points <= value.route.size }
            if (kept == null) clearRoute(index)
            val chunks = kept?.chunks ?: 0
            val points = kept?.points ?: 0
            if (value.route.size > points) {
                kv.putString(chunkKey(chunks), WorkoutCodec.encodeRoute(value.route.subList(points, value.route.size)))
                kv.putString(KEY_ROUTE_INDEX, "${value.id},${chunks + 1},${value.route.size}")
            }
            kv.putString(KEY_IN_PROGRESS, WorkoutCodec.encodeInProgress(value.copy(route = emptyList())))
        }

    var lastFinalCounters: PadCounters?
        get() = kv.getString(KEY_LAST_FINAL)?.let(WorkoutCodec::decodeCounters)
        set(value) = kv.putString(KEY_LAST_FINAL, value?.let(WorkoutCodec::encodeCounters))

    var lastSummaryId: String?
        get() = kv.getString(KEY_SUMMARY)
        set(value) = kv.putString(KEY_SUMMARY, value)

    var manualWeightKg: Double?
        get() = kv.getString(KEY_WEIGHT)?.toDoubleOrNull()
        set(value) = kv.putString(KEY_WEIGHT, value?.toString())

    var manualBirthYear: Int?
        get() = kv.getString(KEY_BIRTH_YEAR)?.toIntOrNull()
        set(value) = kv.putString(KEY_BIRTH_YEAR, value?.toString())

    var manualSex: Sex?
        get() = kv.getString(KEY_SEX)?.let { name -> Sex.entries.firstOrNull { it.name == name } }
        set(value) = kv.putString(KEY_SEX, value?.name)

    var healthPrompt: HealthPrompt
        get() = kv.getString(KEY_HEALTH_PROMPT)?.let { name -> HealthPrompt.entries.firstOrNull { it.name == name } } ?: HealthPrompt.NotAsked
        set(value) = kv.putString(KEY_HEALTH_PROMPT, value.name)

    var weeklyGoalMeters: Int
        get() = kv.getString(KEY_WEEKLY_GOAL)?.toIntOrNull() ?: DEFAULT_WEEKLY_GOAL_METERS
        set(value) = kv.putString(KEY_WEEKLY_GOAL, value.toString())

    var priorityTipShown: Boolean
        get() = kv.getString(KEY_PRIORITY_TIP) == "1"
        set(value) = kv.putString(KEY_PRIORITY_TIP, if (value) "1" else null)

    var watchTipDismissed: Boolean
        get() = kv.getString(KEY_WATCH_TIP) == "1"
        set(value) = kv.putString(KEY_WATCH_TIP, if (value) "1" else null)

    fun records(): List<WorkoutRecord> = kv.getString(KEY_RECORDS)?.let(WorkoutCodec::decodeRecords).orEmpty()

    fun record(id: String): WorkoutRecord? = records().firstOrNull { it.id == id }

    /** Inserts or replaces [record], then drops settled walks other than the last summary. */
    fun put(record: WorkoutRecord) {
        val summaryId = lastSummaryId
        val kept = (records().filter { it.id != record.id } + record)
            .filter { it.needsSync || it.id == summaryId || it.id == record.id }
            .takeLast(MAX_RECORDS)
        kv.putString(KEY_RECORDS, WorkoutCodec.encodeRecords(kept))
    }

    fun remove(id: String) {
        kv.putString(KEY_RECORDS, WorkoutCodec.encodeRecords(records().filter { it.id != id }))
        if (lastSummaryId == id) lastSummaryId = null
    }

    private class RouteIndex(val id: String, val chunks: Int, val points: Int)

    private fun routeIndex(): RouteIndex? {
        val v = kv.getString(KEY_ROUTE_INDEX)?.split(',')?.takeIf { it.size == 3 } ?: return null
        return RouteIndex(v[0], v[1].toIntOrNull() ?: return null, v[2].toIntOrNull() ?: return null)
    }

    private fun routeChunk(n: Int) = kv.getString(chunkKey(n))?.let { runCatching { WorkoutCodec.decodeRoute(it) }.getOrNull() }.orEmpty()

    private fun clearRoute(index: RouteIndex?) {
        if (index == null) return
        repeat(index.chunks) { kv.putString(chunkKey(it), null) }
        kv.putString(KEY_ROUTE_INDEX, null)
    }

    private fun chunkKey(n: Int) = "$KEY_ROUTE_INDEX.$n"

    companion object {
        const val DEFAULT_WEEKLY_GOAL_METERS = 10_000

        private const val KEY_IN_PROGRESS = "workout.inProgress"
        private const val KEY_ROUTE_INDEX = "workout.inProgress.route"
        private const val KEY_LAST_FINAL = "workout.lastFinalCounters"
        private const val KEY_SUMMARY = "workout.lastSummaryId"
        private const val KEY_RECORDS = "workout.records"
        private const val KEY_WEIGHT = "profile.weightKg"
        private const val KEY_BIRTH_YEAR = "profile.birthYear"
        private const val KEY_SEX = "profile.sex"
        private const val KEY_HEALTH_PROMPT = "profile.healthPrompt"
        private const val KEY_PRIORITY_TIP = "profile.priorityTipShown"
        private const val KEY_WATCH_TIP = "profile.watchTipDismissed"
        private const val KEY_WEEKLY_GOAL = "profile.weeklyGoalMeters"
        private const val MAX_RECORDS = 50
    }
}

val WorkoutRecord.needsSync: Boolean
    get() = syncState == SyncState.Pending || syncState == SyncState.Busy || syncState is SyncState.Failed
