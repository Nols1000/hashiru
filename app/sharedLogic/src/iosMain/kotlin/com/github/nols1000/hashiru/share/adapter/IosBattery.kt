package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.port.BatteryPort
import platform.UIKit.UIDevice

class IosBattery : BatteryPort {
    init {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
    }

    override fun percent(): Int? {
        val level = UIDevice.currentDevice.batteryLevel.toDouble()
        return if (level < 0.0) null else (level * 100).toInt().coerceIn(0, 100)
    }
}
