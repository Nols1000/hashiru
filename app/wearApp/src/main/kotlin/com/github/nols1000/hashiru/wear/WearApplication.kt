package com.github.nols1000.hashiru.wear

import android.app.Application
import android.content.Context
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds what the watch knows of the phone's walk. The listener service, the exercise service and the screen
 * share it; the phone stays the source of truth and this is a mirror.
 */
class WearApplication : Application() {
    val link: PhoneLink by lazy { PhoneLink(this) }

    /** The phone's latest report. */
    val telemetry = MutableStateFlow(WristTelemetry())

    /** This watch's own latest reading, shown ahead of the one echoed back by the phone. */
    val heartRateBpm = MutableStateFlow<Int?>(null)
}

val Context.wear: WearApplication get() = applicationContext as WearApplication
