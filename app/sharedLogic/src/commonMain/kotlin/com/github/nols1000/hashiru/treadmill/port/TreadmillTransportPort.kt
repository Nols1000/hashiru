package com.github.nols1000.hashiru.treadmill.port

import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Driven port: a raw byte pipe to a treadmill. */
interface TreadmillTransportPort {
    val connectionState: StateFlow<ConnectionState>
    val incomingBytes: Flow<ByteArray>

    /** Emits when the OS hands back a connection that a previous app process had established. */
    val restoredSessions: Flow<Unit>

    /**
     * Connects to [address] if given, otherwise to the first matching device found. Adapters may keep a
     * connection to a known [address] pending until the device comes into range.
     */
    suspend fun connect(address: String? = null)
    fun disconnect()
    suspend fun send(bytes: ByteArray)
}
