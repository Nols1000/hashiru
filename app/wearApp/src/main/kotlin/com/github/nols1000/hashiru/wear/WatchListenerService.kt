package com.github.nols1000.hashiru.wear

import com.github.nols1000.hashiru.companion.adapter.WearDataLayer
import com.github.nols1000.hashiru.companion.codec.WatchMessage
import com.github.nols1000.hashiru.companion.codec.WatchMessageCodec
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Mirrors the phone's walk and starts or stops the heart-rate exercise with it. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (val message = WatchMessageCodec.decode(event.data)) {
            is WatchMessage.Telemetry -> if (event.path == WearDataLayer.PATH_TELEMETRY) wear.telemetry.value = message.telemetry
            is WatchMessage.Ack -> if (event.path == WearDataLayer.PATH_ACK) wear.link.onAck(message.ok)
            else -> Unit
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WearDataLayer.PATH_STATE) continue
            val bytes = DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(WearDataLayer.KEY_STATE) ?: continue
            val telemetry = (WatchMessageCodec.decode(bytes) as? WatchMessage.Telemetry)?.telemetry ?: continue
            wear.telemetry.value = telemetry
            ExerciseService.follow(this, telemetry.phase)
        }
    }
}
