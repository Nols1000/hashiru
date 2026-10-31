package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.log.log
import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import platform.CoreLocation.CLActivityTypeFitness
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLBackgroundActivitySession
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.math.roundToInt

/**
 * Records an outdoor workout's route with the phone's GPS, alongside whatever tracks its time and counts. New
 * positions go to the recorder as [HealthEvent.Route]; nothing is recorded while paused, and the distance between
 * positions on either side of a pause is not counted. Created and called on the main thread, where CoreLocation
 * also delivers its updates.
 */
@OptIn(ExperimentalForeignApi::class)
internal class RouteTracker private constructor(
    val id: String,
    private val manager: CLLocationManager,
    private val emit: (HealthEvent) -> Unit,
) {
    private val delegate = Delegate()

    /** Keeps updates coming while the app is in the background with when-in-use access. */
    private var background: CLBackgroundActivitySession? = null
    private var paused = false
    private var last: CLLocation? = null
    private var distance = 0.0

    /** Metres between accurate positions while active; null before the first fix. */
    var meters: Int? = null
        private set

    private fun begin() {
        manager.delegate = delegate
        manager.activityType = CLActivityTypeFitness
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        background = CLBackgroundActivitySession.backgroundActivitySession()
        manager.startUpdatingLocation()
    }

    fun pause() {
        paused = true
        // The way back from where the pause began is no part of the distance.
        last = null
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        manager.stopUpdatingLocation()
        manager.delegate = null
        background?.invalidate()
        background = null
    }

    private fun onLocations(locations: List<CLLocation>) {
        if (paused) return
        val points = mutableListOf<RoutePoint>()
        for (location in locations) {
            val accuracy = location.horizontalAccuracy
            if (accuracy < 0 || accuracy > MAX_ACCURACY_M) continue
            val previous = last
            val atMs = location.timestamp.epochMs()
            if (previous != null && atMs <= previous.timestamp.epochMs()) continue
            if (previous != null) distance += location.distanceFromLocation(previous)
            last = location
            meters = distance.roundToInt()
            points += location.toRoutePoint()
        }
        if (points.isNotEmpty()) emit(HealthEvent.Route(id, points))
    }

    /** CoreLocation calls these on the main thread, where the manager was created. */
    private inner class Delegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            onLocations(didUpdateLocations.filterIsInstance<CLLocation>())
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            log("RouteTracker", "${didFailWithError.localizedDescription}")
        }
    }

    /** Waits for the user to answer the location prompt. */
    private class Authorization : NSObject(), CLLocationManagerDelegateProtocol {
        val answered = CompletableDeferred<CLAuthorizationStatus>()

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            if (status != kCLAuthorizationStatusNotDetermined) answered.complete(status)
        }
    }

    companion object {
        /** Positions less accurate than this are left out. */
        private const val MAX_ACCURACY_M = 20.0

        /** Starts recording; the first call asks for location access while in use. */
        suspend fun begin(id: String, emit: (HealthEvent) -> Unit): RouteStart {
            val manager = CLLocationManager()
            if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
                val authorization = Authorization()
                manager.delegate = authorization
                manager.requestWhenInUseAuthorization()
                authorization.answered.await()
                manager.delegate = null
            }
            if (!manager.authorizationStatus.allowed()) return RouteStart.Rejected(RejectReason.LocationAccessDenied)
            return RouteStart.Started(RouteTracker(id, manager, emit).also { it.begin() })
        }

        /** Starts recording again after a restart, without asking: null when access was taken away meanwhile. */
        fun resume(id: String, emit: (HealthEvent) -> Unit): RouteTracker? {
            val manager = CLLocationManager()
            if (!manager.authorizationStatus.allowed()) return null
            return RouteTracker(id, manager, emit).also { it.begin() }
        }

        private fun CLAuthorizationStatus.allowed() =
            this == kCLAuthorizationStatusAuthorizedWhenInUse || this == kCLAuthorizationStatusAuthorizedAlways
    }
}

internal sealed interface RouteStart {
    data class Started(val tracker: RouteTracker) : RouteStart
    data class Rejected(val reason: RejectReason) : RouteStart
}

@OptIn(ExperimentalForeignApi::class)
internal fun CLLocation.toRoutePoint(): RoutePoint = coordinate.useContents {
    RoutePoint(
        atMs = timestamp.epochMs(),
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitude.takeIf { verticalAccuracy >= 0 },
        accuracyM = horizontalAccuracy,
    )
}

/** The point as CoreLocation has it, for HealthKit's route builder. */
@OptIn(ExperimentalForeignApi::class)
internal fun RoutePoint.toLocation(): CLLocation = CLLocation(
    coordinate = CLLocationCoordinate2DMake(latitude, longitude),
    altitude = altitudeM ?: 0.0,
    horizontalAccuracy = accuracyM,
    verticalAccuracy = if (altitudeM == null) -1.0 else accuracyM,
    timestamp = date(atMs),
)
