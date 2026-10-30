@file:OptIn(ExperimentalJsExport::class)

package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.BrowserShareStream
import com.github.nols1000.hashiru.share.adapter.WebCryptoViewerCrypto
import com.github.nols1000.hashiru.share.viewer.ShareViewer
import com.github.nols1000.hashiru.share.viewer.ViewerNote
import com.github.nols1000.hashiru.share.viewer.ViewerState
import com.github.nols1000.hashiru.share.viewer.viewerUi
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.js.Date

@JsExport
class LatLon(val lat: Double, val lon: Double)

/** A note for viewers: [kind] is `silent`, `notMoving`, `headingToCourse`, `offCourse` or `onCourse`. */
@JsExport
class ShareNote(val kind: String, val minutes: Int, val meters: Int)

/**
 * The share as the web viewer shows it; plain values TypeScript can use as they are. [connection] is
 * `connecting`, `live`, `reconnecting`, `full`, `notFound` or `invalidLink`; [phase] is `waiting`, `active`,
 * `paused`, `ended`, or null before the first snapshot. [routeVersion] changes only when [route] does, so a map
 * redraws only then.
 */
@JsExport
class ShareView(
    val connection: String,
    val phase: String?,
    val activity: String?,
    val outdoor: Boolean,
    val seconds: Int,
    val meters: Int,
    val steps: Int,
    val paceSecondsPerKm: Int?,
    val heartRateBpm: Int?,
    val batteryPercent: Int?,
    val notes: Array<ShareNote>,
    val updatedAgoMs: Double?,
    val route: Array<LatLon>,
    val routeVersion: Int,
    val courseName: String?,
    val course: Array<LatLon>,
)

/**
 * Follows the share in [fragment] (the link's `#…`) for the React viewer, reaching the relay under [apiBase] on
 * this origin. Listeners get a new [ShareView] each second and whenever something arrives.
 */
@JsExport
class LiveShareViewer(fragment: String, apiBase: String = "") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewer = ShareViewer(fragment, WebCryptoViewerCrypto(), BrowserShareStream(apiBase), ::now)
    private val listeners = mutableListOf<(ShareView) -> Unit>()
    private var started = false

    private var routeSource: List<RoutePoint>? = null
    private var route = emptyArray<LatLon>()
    private var routeVersion = 0
    private var courseSource: Any? = null
    private var course = emptyArray<LatLon>()

    /** Calls [listener] with each new view, the current one first; returns the function that stops it. */
    fun subscribe(listener: (ShareView) -> Unit): () -> Unit {
        listeners += listener
        start()
        listener(view(viewer.state.value))
        return { listeners -= listener }
    }

    /** Stops following the share. */
    fun close() {
        scope.cancel()
        listeners.clear()
    }

    private fun start() {
        if (started) return
        started = true
        scope.launch { viewer.run() }
        scope.launch { viewer.state.collect(::publish) }
        scope.launch {
            while (true) {
                delay(1_000)
                publish(viewer.state.value)
            }
        }
    }

    private fun publish(state: ViewerState) {
        val view = view(state)
        listeners.toList().forEach { it(view) }
    }

    private fun view(state: ViewerState): ShareView {
        if (state.route !== routeSource) {
            routeSource = state.route
            route = state.route.map { LatLon(it.latitude, it.longitude) }.toTypedArray()
            routeVersion++
        }
        if (state.course !== courseSource) {
            courseSource = state.course
            course = state.course?.points.orEmpty().map { LatLon(it.lat, it.lon) }.toTypedArray()
        }
        val ui = viewerUi(state, now())
        return ShareView(
            connection = ui.connection.name.replaceFirstChar { it.lowercase() },
            phase = ui.phase?.name?.lowercase(),
            activity = state.kind?.activity?.name?.lowercase(),
            outdoor = ui.outdoor,
            seconds = ui.seconds,
            meters = ui.meters,
            steps = ui.steps,
            paceSecondsPerKm = ui.paceSecondsPerKm,
            heartRateBpm = ui.heartRateBpm,
            batteryPercent = ui.batteryPercent,
            notes = ui.notes.map(::note).toTypedArray(),
            updatedAgoMs = ui.updatedAgoMs?.toDouble(),
            route = route,
            routeVersion = routeVersion,
            courseName = state.course?.name,
            course = course,
        )
    }

    private fun note(note: ViewerNote) = when (note) {
        is ViewerNote.Silent -> ShareNote("silent", note.minutes, 0)
        is ViewerNote.NotMoving -> ShareNote("notMoving", note.minutes, 0)
        is ViewerNote.HeadingToCourse -> ShareNote("headingToCourse", 0, note.meters)
        is ViewerNote.OffCourse -> ShareNote("offCourse", 0, note.meters)
        is ViewerNote.OnCourse -> ShareNote("onCourse", 0, note.remainingMeters)
    }

    private companion object {
        fun now(): Long = Date.now().toLong()
    }
}
