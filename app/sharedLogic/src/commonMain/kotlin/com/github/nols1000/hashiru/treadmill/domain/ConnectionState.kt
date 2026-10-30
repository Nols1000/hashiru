package com.github.nols1000.hashiru.treadmill.domain

enum class ConnectionState {
    Disconnected,

    /** Scanning, or waiting for a pending OS-level connection to the associated device to complete. */
    Connecting,
    Connected,
}
