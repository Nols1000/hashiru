package com.github.nols1000.hashiru

import android.app.Application
import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.adapter.WearDataLayerAdapter
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.companion.port.WatchCompanionPort
import com.github.nols1000.hashiru.share.adapter.AndroidBattery
import com.github.nols1000.hashiru.share.adapter.AndroidShareCipher
import com.github.nols1000.hashiru.share.adapter.HttpShareRelay
import com.github.nols1000.hashiru.share.adapter.UrlConnectionTransport
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
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
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Set to true to run against a simulated WalkingPad instead of Bluetooth. */
private const val USE_MOCK_TRANSPORT = false

/** Set to true to run against a simulated watch, which reports heart rate while walking, instead of Wear OS. */
private const val USE_MOCK_WATCH = false

/**
 * Holds the process-wide [session], [workouts], [relay] and [share], which the activity, the foreground service and
 * the companion service share.
 */
class HashiruApplication : Application() {
    val associations: DeviceAssociationPort by lazy {
        if (USE_MOCK_TRANSPORT) MockAssociationAdapter() else AndroidCompanionAssociationAdapter(this)
    }

    val session: TreadmillSession by lazy {
        TreadmillSession(if (USE_MOCK_TRANSPORT) MockTransportAdapter() else AndroidTransportAdapter(this), associations)
    }

    val health: HealthConnectAdapter by lazy { HealthConnectAdapter(this) }

    private val storage: KeyValueStorePort by lazy { SharedPreferencesStore(this) }

    val workouts: WorkoutRecorder by lazy { WorkoutRecorder(session, health, storage) }

    val watch: WatchCompanionPort by lazy { if (USE_MOCK_WATCH) MockWatchCompanionAdapter() else WearDataLayerAdapter(this) }

    val relay: WatchRelay by lazy { WatchRelay(session, workouts, watch) }

    /**
     * Live sharing to [BuildConfig.RELAY_URL], which debug builds may point at a local relay. The relay client keeps
     * its bookkeeping on the main thread, as the engine does; requests travel on the IO dispatcher.
     */
    val share: LiveShareEngine by lazy {
        val shareRelay = HttpShareRelay(BuildConfig.RELAY_URL, UrlConnectionTransport(), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        LiveShareEngine(workouts, shareRelay, AndroidShareCipher(this), AndroidBattery(this), storage, BuildConfig.VIEWER_URL)
    }

    override fun onCreate() {
        super.onCreate()
        start()
        // Keep the process alive in the background for as long as a connection is wanted; the service stops itself.
        MainScope().launch {
            session.connectionState.collect { if (it != ConnectionState.Disconnected) WalkingPadConnectionService.start(this@HashiruApplication) }
        }
    }

    /**
     * Starts the session, the recorder, the relay and the share, each after what it listens to; each listens before
     * it sends, so no answer is missed. A walk cut off by process death is restored or finished here, and so is its
     * share. Starting again does nothing.
     */
    fun start() {
        session.start()
        workouts.start()
        relay.start()
        share.start()
    }
}

val android.content.Context.hashiru: HashiruApplication get() = applicationContext as HashiruApplication
