package com.github.nols1000.hashiru.treadmill.domain

/** Latest status reported by the treadmill. Kept by the engine so a freshly mounted UI renders it immediately. */
data class TelemetrySnapshot(
    val speedKmh: Double = 0.0,
    val isRunning: Boolean = false,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val steps: Int = 0,
)
