package com.github.nols1000.hashiru.wear

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.startExercise
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Runs a Health Services walking exercise while the phone records a walk. The exercise is what keeps the
 * optical heart-rate sensor sampling with the screen off; the readings go to the phone at most once a
 * second. The phone writes the walk to Health Connect, so nothing is saved here.
 */
class ExerciseService : Service() {
    private val scope = MainScope()
    private val client by lazy { HealthServices.getClient(this).exerciseClient }
    private var lastSentMs = 0L

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val point = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull() ?: return
            val bpm = point.value.roundToInt()
            val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            val sample = HeartRateSample(point.getTimeInstant(bootInstant).toEpochMilli(), bpm)
            wear.heartRateBpm.value = bpm
            if (sample.epochMs - lastSentMs < SEND_INTERVAL_MS) return
            lastSentMs = sample.epochMs
            scope.launch { wear.link.sendHeartRate(sample) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "Exercise updates unavailable", throwable)
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        scope.launch { startExercise() }
    }

    override fun onDestroy() {
        running = false
        wear.heartRateBpm.value = null
        client.clearUpdateCallbackAsync(callback)
        // The scope is going away; the end request does not need it.
        client.endExerciseAsync()
        scope.cancel()
        super.onDestroy()
    }

    // The status constants are public on the annotation interface; Kotlin resolves them through its
    // library-internal companion, which lint mistakes for restricted API and an unknown constant.
    @SuppressLint("RestrictedApi", "WrongConstant")
    private suspend fun startExercise() {
        try {
            // Never take over a workout the user runs in another app; its heart rate is not ours to end.
            if (client.getCurrentExerciseInfo().exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) {
                Log.i(TAG, "Another app is tracking an exercise")
                stopSelf()
                return
            }
            client.setUpdateCallback(callback)
            val config = ExerciseConfig.builder(ExerciseType.WALKING)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
                .build()
            client.startExercise(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the exercise", e)
            stopSelf()
        }
    }

    private fun notification() = run {
        createChannel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ongoing_walking))
            .setContentText(getString(R.string.ongoing_heart_rate))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .setOngoing(true)
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.ongoing_walking)).build())
            .build()
            .apply(this)
        builder.build()
    }

    companion object {
        private const val TAG = "ExerciseService"
        private const val CHANNEL_ID = "walk"
        private const val NOTIFICATION_ID = 1
        private const val PROMPT_ID = 2
        private const val SEND_INTERVAL_MS = 1_000L

        @Volatile var running = false
            private set

        /**
         * Starts the exercise while the phone's walk is in progress and ends it once the walk is over. Wear OS
         * may refuse to start it from the background; the user then gets a notification that starts it.
         */
        fun follow(context: Context, phase: WorkoutPhase) {
            val intent = Intent(context, ExerciseService::class.java)
            when {
                phase == WorkoutPhase.Idle -> if (running) context.stopService(intent)
                running -> Unit
                else -> try {
                    context.startForegroundService(intent)
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    Log.i(TAG, "Background start refused, asking the user", e)
                    promptToStart(context)
                } catch (e: SecurityException) {
                    // Body sensors not granted yet; the app asks when opened.
                    Log.i(TAG, "Missing sensor permission", e)
                    promptToStart(context)
                }
            }
            if (phase == WorkoutPhase.Idle) context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
        }

        private fun promptToStart(context: Context) {
            createChannel(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.start_prompt_title))
                .setContentText(context.getString(R.string.start_prompt_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
