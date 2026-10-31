package com.github.nols1000.hashiru.treadmill

import com.github.nols1000.hashiru.companion.adapter.IosWatchConnectivityAdapter
import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.course.adapter.FileCourseLibrary
import com.github.nols1000.hashiru.course.domain.ImportResult
import com.github.nols1000.hashiru.course.port.CourseLibraryPort
import com.github.nols1000.hashiru.course.presentation.CourseLibraryViewModel
import com.github.nols1000.hashiru.share.adapter.IosBattery
import com.github.nols1000.hashiru.share.adapter.IosShareRelayAdapter
import com.github.nols1000.hashiru.share.adapter.MockShareRelayAdapter
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.share.port.ShareCipherPort
import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.treadmill.adapter.IosAccessoryAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.IosTransportAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.IosHealthKitAdapter
import com.github.nols1000.hashiru.workout.adapter.UserDefaultsStore
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import com.github.nols1000.hashiru.workout.port.WorkoutHistoryPort
import com.github.nols1000.hashiru.workout.presentation.HistoryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitWeekday
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

/** The process-wide objects of the iOS app. */
class IosApp(
    val session: TreadmillSession,
    val workouts: WorkoutRecorder,
    val relay: WatchRelay,
    val share: LiveShareEngine,
    private val history: WorkoutHistoryPort,
    private val storage: KeyValueStorePort,
    private val courses: CourseLibraryPort,
) {
    /** A presenter for the walks in Apple Health, with weeks in the user's calendar. */
    fun historyViewModel(): HistoryViewModel = HistoryViewModel(history, workouts, storage, ::weekDayStarts)

    /** A presenter for the routes imported to this iPhone. */
    fun courseLibraryViewModel(): CourseLibraryViewModel = CourseLibraryViewModel(courses)
}

/** Imports the GPX or FIT file [fileName] holding [data]; for Swift, which hands over `Data`. */
fun CourseLibraryViewModel.importFile(fileName: String, data: NSData, onResult: (ImportResult) -> Unit) =
    import(fileName, data.toByteArray(), onResult)

/** Midnight of each day of the week containing [nowMs], then of the next week's first day, as the calendar has it. */
private fun weekDayStarts(nowMs: Long): List<Long> {
    val calendar = NSCalendar.currentCalendar
    val today = calendar.startOfDayForDate(NSDate.dateWithTimeIntervalSince1970(nowMs / 1000.0))
    val daysIntoWeek = (calendar.component(NSCalendarUnitWeekday, fromDate = today) - calendar.firstWeekday.toLong() + 7) % 7
    return (0L..7L).map { day ->
        val date = calendar.dateByAddingUnit(NSCalendarUnitDay, value = day - daysIntoWeek, toDate = today, options = 0u)!!
        (date.timeIntervalSince1970 * 1000).toLong()
    }
}

/**
 * Creates the process-wide session and walk recorder for the iOS app. Call it once from
 * `application(_:didFinishLaunchingWithOptions:)` so CoreBluetooth state restoration reaches the transport,
 * and a walk cut off when iOS ended the app is restored or finished. It also activates the WatchConnectivity
 * session, which must happen before launch finishes. HealthKit is used in the simulator too; the watch is
 * simulated there, and so are workouts the phone tracks. Without a [relayUrl], live shares go to a simulated relay.
 */
fun createIosApp(
    simulated: Boolean,
    shareCipher: ShareCipherPort,
    relayUrl: String?,
    viewerUrl: String,
): IosApp {
    val session = createIosTreadmillSession(simulated)
    val health = IosHealthKitAdapter(simulated)
    val storage = UserDefaultsStore()
    val courses = FileCourseLibrary()
    val workouts = WorkoutRecorder(session, health, storage, courses)
    val relay = WatchRelay(session, workouts, if (simulated) MockWatchCompanionAdapter() else IosWatchConnectivityAdapter())
    val shareRelay = if (relayUrl == null) MockShareRelayAdapter() else IosShareRelayAdapter(relayUrl)
    val share = LiveShareEngine(workouts, shareRelay, shareCipher, IosBattery(), storage, viewerUrl)
    // Each starts after what it listens to, and each listens before it sends, so no answer is missed.
    session.start()
    workouts.start()
    relay.start()
    share.start()
    return IosApp(session, workouts, relay, share, health, storage, courses)
}

/**
 * iOS reports no presence changes, so a pending connection to the paired pad is requested as soon as the
 * pairing is known; it completes whenever the pad is in range.
 */
private fun createIosTreadmillSession(simulated: Boolean): TreadmillSession {
    val session = if (simulated) {
        TreadmillSession(MockTransportAdapter(), MockAssociationAdapter())
    } else {
        TreadmillSession(IosTransportAdapter(), IosAccessoryAssociationAdapter())
    }
    if (!simulated) {
        MainScope().launch {
            session.association.filterNotNull().first()
            if (session.connectionState.value != ConnectionState.Disconnected) return@launch
            try {
                session.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("WalkingPadBle: automatic connect failed: ${e.message}")
            }
        }
    }
    return session
}
