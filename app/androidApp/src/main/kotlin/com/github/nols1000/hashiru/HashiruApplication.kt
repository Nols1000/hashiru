package com.github.nols1000.hashiru

import android.app.Application
import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.adapter.WearDataLayerAdapter
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.companion.port.WatchCompanionPort
import com.github.nols1000.hashiru.treadmill.adapter.AndroidCompanionAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.AndroidTransportAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociationPort
import com.github.nols1000.hashiru.workout.adapter.HealthConnectAdapter
import com.github.nols1000.hashiru.workout.adapter.SharedPreferencesStore
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Set to true to run against a simulated WalkingPad instead of Bluetooth. */
private const val USE_MOCK_TRANSPORT = false

/** Set to true to run against a simulated watch, which reports heart rate while walking, instead of Wear OS. */
private const val USE_MOCK_WATCH = false

/**
 * Holds the process-wide [session], [workouts] and [relay], which the activity, the foreground service and the
 * companion service share.
 */
class HashiruApplication : Application() {
    val associations: DeviceAssociationPort by lazy {
        if (USE_MOCK_TRANSPORT) MockAssociationAdapter() else AndroidCompanionAssociationAdapter(this)
    }

    val session: TreadmillSession by lazy {
        TreadmillSession(if (USE_MOCK_TRANSPORT) MockTransportAdapter() else AndroidTransportAdapter(this), associations)
    }

    val health: HealthConnectAdapter by lazy { HealthConnectAdapter(this) }

    val workouts: WorkoutRecorder by lazy { WorkoutRecorder(session, health, SharedPreferencesStore(this)) }

    val watch: WatchCompanionPort by lazy { if (USE_MOCK_WATCH) MockWatchCompanionAdapter() else WearDataLayerAdapter(this) }

    val relay: WatchRelay by lazy { WatchRelay(session, workouts, watch) }

    override fun onCreate() {
        super.onCreate()
        // Starts the recorder and the session too; a walk cut off by process death is restored or finished here.
        relay.start()
        // Keep the process alive in the background for as long as a connection is wanted; the service stops itself.
        MainScope().launch {
            session.connectionState.collect { if (it != ConnectionState.Disconnected) WalkingPadConnectionService.start(this@HashiruApplication) }
        }
    }
}

val android.content.Context.hashiru: HashiruApplication get() = applicationContext as HashiruApplication
