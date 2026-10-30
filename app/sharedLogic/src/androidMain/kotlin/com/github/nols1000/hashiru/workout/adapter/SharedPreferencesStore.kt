package com.github.nols1000.hashiru.workout.adapter

import android.content.Context
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort

/** Keeps workout state in the app's private preferences. Writes go to disk in the background. */
class SharedPreferencesStore(context: Context) : KeyValueStorePort {
    private val preferences = context.applicationContext.getSharedPreferences("workouts", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
