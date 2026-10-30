package com.github.nols1000.hashiru

import com.github.nols1000.hashiru.companion.adapter.WearDataLayerAdapter
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives commands and heart rate from the watch app. The system binds it for each message, starting the
 * process if needed, so a wrist command reaches the phone even after the app was killed.
 */
class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        (hashiru.watch as? WearDataLayerAdapter)?.onMessage(event.sourceNodeId, event.path, event.data)
    }
}
