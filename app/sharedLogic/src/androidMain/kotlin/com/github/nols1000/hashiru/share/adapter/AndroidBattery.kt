package com.github.nols1000.hashiru.share.adapter

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.github.nols1000.hashiru.share.port.BatteryPort

/** The charge from the sticky battery broadcast, read when asked; nothing stays registered. */
class AndroidBattery(context: Context) : BatteryPort {
    private val context = context.applicationContext

    override fun percent(): Int? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return batteryPercent(status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), status.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
    }
}

/** [level] out of [scale] as 0–100, or null when the broadcast leaves either out. */
internal fun batteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
