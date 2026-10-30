package com.github.nols1000.hashiru.companion.adapter

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Data Layer paths and capabilities shared by the phone app and the Wear OS app. */
object WearDataLayer {
    /** Declared by the watch app in `android_wear_capabilities`. */
    const val CAPABILITY_WATCH = "hashiru_watch"

    /** Declared by the phone app in `android_wear_capabilities`. */
    const val CAPABILITY_PHONE = "hashiru_phone"

    /** Watch → phone: a [com.github.nols1000.hashiru.companion.domain.WristCommand]. */
    const val PATH_COMMAND = "/hashiru/cmd"

    /** Phone → watch: the answer to a command. */
    const val PATH_ACK = "/hashiru/ack"

    /** Watch → phone: a heart-rate reading. */
    const val PATH_HEART_RATE = "/hashiru/hr"

    /** Phone → watch: live metrics, as a message. */
    const val PATH_TELEMETRY = "/hashiru/telemetry"

    /** Phone → watch: the walk's phase, as a data item, so the watch gets it once it is back in reach. */
    const val PATH_STATE = "/hashiru/state"

    const val KEY_STATE = "state"
}

/** Waits for a Play services [Task] without pulling in kotlinx-coroutines-play-services. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
