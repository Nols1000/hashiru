package com.github.nols1000.hashiru.workout.adapter

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import com.github.nols1000.hashiru.workout.port.HealthSyncPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/** Shows the Health Connect permission sheet; returns the permissions the user granted. */
fun interface HealthPermissionLauncher {
    suspend fun launch(permissions: Set<String>): Set<String>
}

/**
 * Writes workouts to Health Connect. The phone does not track workouts itself here, so only pad walks are
 * recorded; a phone workout is rejected. The library needs API 26 and Health Connect itself API 28, so on older
 * versions every call reports [HealthAvailability.NotSupported] without touching the library.
 *
 * [requestAuthorization] needs an Activity; register [createPermissionContract] there and set [launcher].
 */
class HealthConnectAdapter(context: Context) : HealthSyncPort {
    private val appContext = context.applicationContext

    @Volatile var launcher: HealthPermissionLauncher? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)
    override val events: Flow<HealthEvent> = _events

    private val store: HealthConnectStore? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectStore(HealthConnectClient.getOrCreate(appContext))
        } else {
            null
        }
    }

    override fun availability(): HealthAvailability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> HealthAvailability.NotSupported
        else -> when (sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NotInstalled
            else -> HealthAvailability.NotSupported
        }
    }

    override suspend fun authorizationStatus(): HealthAuthorization {
        val store = store ?: return HealthAuthorization.NotDetermined
        // Health Connect does not say whether the user was asked, so a missing grant counts as undecided.
        return if (store.granted().containsAll(HealthConnectStore.WRITE_PERMISSIONS)) HealthAuthorization.Granted else HealthAuthorization.NotDetermined
    }

    override suspend fun requestAuthorization(): HealthAuthorization {
        val store = store ?: return HealthAuthorization.NotDetermined
        val launcher = launcher ?: error("Health Connect access needs the app in the foreground")
        val granted = launcher.launch(HealthConnectStore.ALL_PERMISSIONS) + store.granted()
        return if (granted.containsAll(HealthConnectStore.WRITE_PERMISSIONS)) HealthAuthorization.Granted else HealthAuthorization.Denied
    }

    override suspend fun latestBodyMassKg(): Double? {
        val store = store ?: return null
        if (HealthConnectStore.READ_WEIGHT !in store.granted()) return null
        return store.latestWeightKg()
    }

    /** Health Connect keeps neither age nor sex. */
    override suspend fun profile(): UserProfile? = null

    override fun handle(event: WorkoutEvent) {
        when (event) {
            is WorkoutEvent.StartRequested -> if (event.source == WorkoutSource.Phone) {
                _events.tryEmit(HealthEvent.Rejected(event.id, RejectReason.Unsupported))
            }
            is WorkoutEvent.Restored -> if (event.source == WorkoutSource.Phone) _events.tryEmit(HealthEvent.TrackingLost(event.id))
            is WorkoutEvent.Finished -> scope.launch { _events.emit(save(event.record)) }
            is WorkoutEvent.Paused, is WorkoutEvent.Resumed, is WorkoutEvent.Discarded -> Unit
        }
    }

    private suspend fun save(record: WorkoutRecord): HealthEvent = try {
        (store ?: error("Health Connect is not available")).save(record)
        HealthEvent.Saved(record.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HealthEvent.SaveFailed(record.id, e.message ?: "Could not save the workout")
    }

    override suspend fun delete(record: WorkoutRecord) {
        store?.delete(record)
    }


    /** Opens Health Connect's settings, where the user sets data-source priority. Null when it is not available. */
    fun settingsIntent(): Intent? =
        if (availability() == HealthAvailability.Available) Intent(settingsAction()) else null

    // Built into the system from Android 14, a separate app before that.
    private fun settingsAction() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        "android.health.connect.action.HEALTH_HOME_SETTINGS"
    } else {
        "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
    }

    private fun sdkStatus() = HealthConnectClient.getSdkStatus(appContext)

    companion object {
        /** The Activity Result contract for the permission sheet; register it on API 28+ only. */
        fun createPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()
    }
}

/**
 * The part that uses `java.time`, kept apart so older API levels never load it. Every record carries a
 * client record ID derived from the walk's ID, so saving a walk again replaces its records.
 *
 * Heart rate from the watch is written as one series per window, but only with its permission: an
 * ungranted record type would fail the whole insert. Wear OS does not write it to Health Connect itself.
 */
@RequiresApi(Build.VERSION_CODES.P)
private class HealthConnectStore(private val client: HealthConnectClient) {
    private val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "KingSmith", model = "WalkingPad")

    suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun latestWeightKg(): Double? {
        val now = Instant.now()
        val request = ReadRecordsRequest(
            WeightRecord::class,
            TimeRangeFilter.between(now.minusSeconds(WEIGHT_LOOKBACK_SECONDS), now),
            ascendingOrder = false,
            pageSize = 1,
        )
        return client.readRecords(request).records.firstOrNull()?.weight?.inKilograms
    }

    suspend fun save(walk: WorkoutRecord) {
        val zone = ZoneId.systemDefault().rules
        fun instant(ms: Long) = Instant.ofEpochMilli(ms)
        fun offset(ms: Long) = zone.getOffset(instant(ms))
        fun metadata(kind: String, index: Int? = null) =
            Metadata.activelyRecorded(device, clientRecordId(walk, kind, index), CLIENT_RECORD_VERSION)

        val withHeartRate = HEART_RATE_PERMISSION in granted()
        val records = buildList<Record> {
            add(
                ExerciseSessionRecord(
                    startTime = instant(walk.startMs),
                    startZoneOffset = offset(walk.startMs),
                    endTime = instant(walk.endMs),
                    endZoneOffset = offset(walk.endMs),
                    metadata = metadata(KIND_SESSION),
                    exerciseType = when (walk.kind.activity) {
                        Activity.Walking -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                        Activity.Running -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                    },
                    title = "Treadmill walk",
                    segments = walk.pauses.map {
                        ExerciseSegment(instant(it.startMs), instant(it.endMs), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE)
                    },
                ),
            )
            walk.samples.forEachIndexed { i, s ->
                val start = instant(s.startMs)
                val end = instant(s.endMs)
                val startOffset = offset(s.startMs)
                val endOffset = offset(s.endMs)
                if (s.distanceMeters > 0) {
                    add(DistanceRecord(start, startOffset, end, endOffset, Length.meters(s.distanceMeters.toDouble()), metadata(KIND_DISTANCE, i)))
                }
                if (s.steps > 0) add(StepsRecord(start, startOffset, end, endOffset, s.steps.toLong(), metadata(KIND_STEPS, i)))
                add(ActiveCaloriesBurnedRecord(start, startOffset, end, endOffset, Energy.kilocalories(s.activeKcal), metadata(KIND_ACTIVE, i)))
                add(TotalCaloriesBurnedRecord(start, startOffset, end, endOffset, Energy.kilocalories(s.totalKcal), metadata(KIND_TOTAL, i)))
                val series = s.heartRate?.series.orEmpty().filter { it.epochMs in s.startMs until s.endMs }
                if (withHeartRate && series.isNotEmpty()) {
                    val readings = series.map { HeartRateRecord.Sample(instant(it.epochMs), it.bpm.toLong()) }
                    add(HeartRateRecord(start, startOffset, end, endOffset, readings, metadata(KIND_HEART_RATE, i)))
                }
            }
        }
        // One call, so Health Connect stores the walk completely or not at all.
        client.insertRecords(records)
    }

    suspend fun delete(walk: WorkoutRecord) {
        val indices = walk.samples.indices.toList()
        val byKind: List<Pair<KClass<out Record>, List<String>>> = listOf(
            ExerciseSessionRecord::class to listOf(clientRecordId(walk, KIND_SESSION, null)),
            DistanceRecord::class to indices.map { clientRecordId(walk, KIND_DISTANCE, it) },
            StepsRecord::class to indices.map { clientRecordId(walk, KIND_STEPS, it) },
            ActiveCaloriesBurnedRecord::class to indices.map { clientRecordId(walk, KIND_ACTIVE, it) },
            TotalCaloriesBurnedRecord::class to indices.map { clientRecordId(walk, KIND_TOTAL, it) },
            HeartRateRecord::class to indices.map { clientRecordId(walk, KIND_HEART_RATE, it) },
        )
        val granted = granted()
        for ((type, ids) in byKind) {
            if (ids.isEmpty() || HealthPermission.getWritePermission(type) !in granted) continue
            client.deleteRecords(type, recordIdsList = emptyList(), clientRecordIdsList = ids)
        }
    }

    private fun clientRecordId(walk: WorkoutRecord, kind: String, index: Int?) =
        if (index == null) "${walk.id}-$kind" else "${walk.id}-$kind-$index"

    companion object {
        val WRITE_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        )
        val READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)

        /** Asked for, but optional: walks are saved without heart rate when it is not granted. */
        val HEART_RATE_PERMISSION = HealthPermission.getWritePermission(HeartRateRecord::class)
        val ALL_PERMISSIONS = WRITE_PERMISSIONS + READ_WEIGHT + HEART_RATE_PERMISSION

        private const val CLIENT_RECORD_VERSION = 1L
        private const val WEIGHT_LOOKBACK_SECONDS = 365L * 24 * 3600
        private const val KIND_SESSION = "session"
        private const val KIND_DISTANCE = "distance"
        private const val KIND_STEPS = "steps"
        private const val KIND_ACTIVE = "active"
        private const val KIND_TOTAL = "total"
        private const val KIND_HEART_RATE = "heartRate"
    }
}
