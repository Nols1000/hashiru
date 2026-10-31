package com.github.nols1000.hashiru

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TelemetrySnapshot
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the process, and with it the GATT connection, alive while the app is in the background. Its
 * notification shows the walk in progress with Pause, Resume and End actions, as a Live Update on API 36+.
 * Stops itself once the session is disconnected.
 *
 * `START_STICKY` makes the system recreate it after the process is killed; it then reconnects to the
 * associated device on its own.
 */
class WalkingPadConnectionService : Service() {
    private val scope = MainScope()
    private val session get() = hashiru.session
    private val workouts get() = hashiru.workouts

    /** What the notification shows; compared so it is only re-posted when something visible changed. */
    private data class Content(
        val title: String,
        val text: String,
        val phase: WorkoutPhase = WorkoutPhase.Idle,
        val canResume: Boolean = false,
        val durationSeconds: Int = 0,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startInForeground(notification(Content(getString(R.string.app_name), getString(R.string.notification_connecting))))
        } catch (e: Exception) {
            // Background start not allowed, or the connectedDevice prerequisites are missing.
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            return
        }
        hashiru.start()
        scope.launch {
            combine(session.connectionState, session.telemetry, workouts.status, ::content)
                .distinctUntilChanged()
                .collect { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it)) }
        }
        scope.launch {
            val state = session.connectionState
            // Give a fresh start a moment to begin connecting, then run until the session settles on disconnected.
            val began = withTimeoutOrNull(START_GRACE) { state.first { it != ConnectionState.Disconnected } }
            if (began != null) state.first { it == ConnectionState.Disconnected }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> workouts.pause()
            ACTION_RESUME -> workouts.resume()
            ACTION_END -> workouts.end()
        }
        // A null intent means the system recreated the service after the process died.
        if (intent == null && session.connectionState.value == ConnectionState.Disconnected && session.association.value != null) {
            Log.d(TAG, "Restarted after process death, reconnecting")
            scope.launch {
                try {
                    session.connect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect failed", e)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun content(state: ConnectionState, telemetry: TelemetrySnapshot, workout: WorkoutStatus): Content {
        val connected = state == ConnectionState.Connected
        if (workout.phase == WorkoutPhase.Idle) {
            val text = when {
                !connected -> getString(R.string.notification_connecting)
                telemetry.isRunning -> getString(R.string.notification_running, "%.1f".format(telemetry.speedKmh))
                else -> getString(R.string.notification_connected)
            }
            return Content(getString(R.string.app_name), text)
        }
        val title = when {
            workout.phase == WorkoutPhase.Active -> getString(R.string.notification_walking)
            workout.pauseReason == PauseReason.Disconnected -> getString(R.string.notification_reconnecting)
            else -> getString(R.string.notification_paused)
        }
        val totals = workout.totals
        val text = getString(
            R.string.notification_walk,
            formatDuration(totals.seconds),
            "%d.%02d".format(totals.meters / 1000, totals.meters % 1000 / 10),
            "%.1f".format(if (connected) telemetry.speedKmh else 0.0),
        )
        return Content(
            title = title,
            text = text,
            phase = workout.phase,
            canResume = workout.phase == WorkoutPhase.Paused && workout.pauseReason != PauseReason.Disconnected,
            durationSeconds = totals.seconds,
        )
    }

    @SuppressLint("InlinedApi") // CATEGORY_WORKOUT is a plain string that older versions ignore.
    private fun notification(content: Content): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
        if (content.phase == WorkoutPhase.Idle) return builder.build()

        if (content.phase == WorkoutPhase.Active) {
            // The system ticks the clock, so the notification is not re-posted every second for it.
            builder.setWhen(System.currentTimeMillis() - content.durationSeconds * 1000L).setShowWhen(true).setUsesChronometer(true)
            builder.addAction(action(ACTION_PAUSE, R.string.notification_action_pause))
        } else if (content.canResume) {
            builder.addAction(action(ACTION_RESUME, R.string.notification_action_resume))
        }
        builder.addAction(action(ACTION_END, R.string.notification_action_end))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setRequestPromotedOngoing(true).setShortCriticalText(formatDuration(content.durationSeconds))
        }
        return builder.build()
    }

    private fun action(action: String, title: Int): Notification.Action {
        val intent = PendingIntent.getService(
            this, action.hashCode(), Intent(this, WalkingPadConnectionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), getString(title), intent).build()
    }

    private fun formatDuration(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    companion object {
        private const val TAG = "WalkingPadService"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.github.nols1000.hashiru.action.PAUSE"
        private const val ACTION_RESUME = "com.github.nols1000.hashiru.action.RESUME"
        private const val ACTION_END = "com.github.nols1000.hashiru.action.END"
        private val START_GRACE = 10.seconds

        fun start(context: Context) {
            val intent = Intent(context, WalkingPadConnectionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) when no background-start exemption applies.
                Log.w(TAG, "Could not start the connection service", e)
            }
        }
    }
}
