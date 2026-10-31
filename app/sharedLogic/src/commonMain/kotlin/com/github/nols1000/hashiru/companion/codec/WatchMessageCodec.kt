package com.github.nols1000.hashiru.companion.codec

import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.domain.WristNavigation
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase

/** Everything that travels between phone and watch. */
sealed interface WatchMessage {
    data class Telemetry(val telemetry: WristTelemetry) : WatchMessage
    data class Command(val command: WristCommand) : WatchMessage
    data class HeartRate(val sample: HeartRateSample) : WatchMessage

    /** The phone's answer to a [Command]: whether it was carried out. */
    data class Ack(val ok: Boolean) : WatchMessage
}

/**
 * UTF-8 text with fields split by `|`: a kind letter, the format version, then the fields. Booleans are `0`/`1`
 * and absent values are empty. The watchOS app has its own copy of this format (`WatchCodec.swift`); both are
 * checked against `fixtures/watch-codec-vectors.json`. Examples:
 *
 * ```
 * T|2|Active|1|1|35|754|612|112|Aerobic|0|1|12|4380|1|0
 *                          telemetry: phase, connected, running, speed (0.1 km/h), duration (s), distance (m),
 *                          bpm, zone, can resume, then the course or five empty fields: on course,
 *                          off course (m), remaining (m), joined, finished
 * C|2|SetSpeed|35   C|2|SetPaused|1   C|2|StopBelt|
 * H|2|1700000000000|112    heart rate: epoch ms, bpm
 * A|2|1                    ack
 * ```
 *
 * Version 2 added the course to telemetry.
 *
 * Decoding returns null for anything it does not understand, including a newer version.
 */
object WatchMessageCodec {
    private const val VERSION = "2"

    fun encode(message: WatchMessage): ByteArray = when (message) {
        is WatchMessage.Telemetry -> with(message.telemetry) {
            listOf(
                "T", VERSION, phase.name, flag(isConnected), flag(isRunning), speedTenths, durationSeconds, distanceMeters,
                heartRateBpm ?: "", zone?.name ?: "", flag(canResume),
                navigation?.let { flag(it.onCourse) } ?: "", navigation?.offCourseMeters ?: "", navigation?.remainingMeters ?: "",
                navigation?.let { flag(it.joined) } ?: "", navigation?.let { flag(it.finished) } ?: "",
            )
        }
        is WatchMessage.Command -> when (val c = message.command) {
            is WristCommand.SetSpeed -> listOf("C", VERSION, "SetSpeed", c.tenthsKmh)
            is WristCommand.SetPaused -> listOf("C", VERSION, "SetPaused", flag(c.paused))
            WristCommand.StopBelt -> listOf("C", VERSION, "StopBelt", "")
        }
        is WatchMessage.HeartRate -> listOf("H", VERSION, message.sample.epochMs, message.sample.bpm)
        is WatchMessage.Ack -> listOf("A", VERSION, flag(message.ok))
    }.joinToString("|").encodeToByteArray()

    fun decode(bytes: ByteArray): WatchMessage? = runCatching {
        val f = bytes.decodeToString().split('|')
        if (f.size < 3 || f[1] != VERSION) return null
        when (f[0]) {
            "T" -> {
                if (f.size != 16) return null
                WatchMessage.Telemetry(
                    WristTelemetry(
                        phase = WorkoutPhase.valueOf(f[2]),
                        isConnected = flag(f[3]),
                        isRunning = flag(f[4]),
                        speedTenths = f[5].toInt(),
                        durationSeconds = f[6].toInt(),
                        distanceMeters = f[7].toInt(),
                        heartRateBpm = f[8].toIntOrNull(),
                        zone = f[9].takeIf { it.isNotEmpty() }?.let(HeartRateZone::valueOf),
                        canResume = flag(f[10]),
                        navigation = if (f[11].isEmpty()) {
                            null
                        } else {
                            WristNavigation(flag(f[11]), f[12].toInt(), f[13].toInt(), flag(f[14]), flag(f[15]))
                        },
                    ),
                )
            }
            "C" -> {
                if (f.size != 4) return null
                WatchMessage.Command(
                    when (f[2]) {
                        "SetSpeed" -> WristCommand.SetSpeed(f[3].toInt())
                        "SetPaused" -> WristCommand.SetPaused(flag(f[3]))
                        "StopBelt" -> WristCommand.StopBelt
                        else -> return null
                    },
                )
            }
            "H" -> if (f.size == 4) WatchMessage.HeartRate(HeartRateSample(f[2].toLong(), f[3].toInt())) else null
            "A" -> WatchMessage.Ack(flag(f[2]))
            else -> null
        }
    }.getOrNull()

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun flag(text: String) = when (text) {
        "1" -> true
        "0" -> false
        else -> error("Not a flag: $text")
    }
}
