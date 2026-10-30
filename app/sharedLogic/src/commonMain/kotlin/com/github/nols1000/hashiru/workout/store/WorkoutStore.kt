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
    var inProgress: WorkoutState.InProgress?
        get() = kv.getString(KEY_IN_PROGRESS)?.let(WorkoutCodec::decodeInProgress)
        set(value) = kv.putString(KEY_IN_PROGRESS, value?.let(WorkoutCodec::encodeInProgress))

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

    companion object {
        const val DEFAULT_WEEKLY_GOAL_METERS = 10_000

        private const val KEY_IN_PROGRESS = "workout.inProgress"
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
