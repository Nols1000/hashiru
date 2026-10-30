package com.github.nols1000.hashiru.workout.port

/** Driven port: small persistent strings (SharedPreferences on Android, NSUserDefaults on iOS). */
interface KeyValueStorePort {
    fun getString(key: String): String?

    /** Stores [value], or removes the key when it is null. */
    fun putString(key: String, value: String?)
}
