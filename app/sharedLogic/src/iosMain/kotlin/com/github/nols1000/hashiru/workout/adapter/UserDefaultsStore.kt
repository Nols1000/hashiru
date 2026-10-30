package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import platform.Foundation.NSUserDefaults

/** Keeps workout state in the app's standard user defaults. */
class UserDefaultsStore : KeyValueStorePort {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}
