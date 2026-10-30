package com.github.nols1000.hashiru

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import com.github.nols1000.hashiru.treadmill.adapter.AndroidCompanionAssociationAdapter

/**
 * Bound by the system when the associated WalkingPad comes into or leaves range, even while the app process
 * is dead. Starts the foreground service before reporting presence, so the connection that follows is kept.
 *
 * The callback the system uses depends on the API level, so every variant is overridden without calling super.
 */
@RequiresApi(Build.VERSION_CODES.S)
class WalkingPadCompanionService : CompanionDeviceService() {
    @Deprecated("Used on API 31-32")
    override fun onDeviceAppeared(address: String) = report(nearby = true)

    @Deprecated("Used on API 31-32")
    override fun onDeviceDisappeared(address: String) = report(nearby = false)

    @Deprecated("Used on API 33-35")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = report(nearby = true)

    @Deprecated("Used on API 33-35")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = report(nearby = false)

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> report(nearby = true)
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> report(nearby = false)
        }
    }

    private fun report(nearby: Boolean) {
        if (nearby) WalkingPadConnectionService.start(this)
        hashiru.workouts.start()
        (hashiru.associations as? AndroidCompanionAssociationAdapter)?.onPresenceChanged(nearby)
    }
}
