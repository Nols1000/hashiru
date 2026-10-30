package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.port.KeyValueStorePort

/** Keeps values for the life of the process; used by tests and previews. */
class InMemoryKeyValueStore : KeyValueStorePort {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
