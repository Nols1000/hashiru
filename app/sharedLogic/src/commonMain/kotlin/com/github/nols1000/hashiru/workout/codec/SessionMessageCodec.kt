package com.github.nols1000.hashiru.workout.codec

import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.HeartRateStats
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.TrackedTotals
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import kotlin.math.roundToInt

/**
 * What travels between the phone and the watch's primary workout session, over HealthKit's mirrored session
 * (`sendToRemoteWorkoutSession`). The phone owns the walk and the pad; the watch's session times it, estimates
 * its energy, holds its samples and saves it.
 */
sealed interface SessionMessage {
    /** The walk [id] began at [startMs]; the watch starts its session dated back to then. */
    data class Start(val id: String, val startMs: Long, val kind: WorkoutKind, val source: WorkoutSource) : SessionMessage

    /** The pad's distance and steps for the walk so far; the watch adds what it has not added yet. */
    data class Counted(val atMs: Long, val meters: Int, val steps: Int) : SessionMessage

    /** End the walk at [endMs] and save it, tagged with the phone's [id] and [syncVersion]. */
    data class End(val endMs: Long, val id: String, val syncVersion: Long) : SessionMessage
    data object Discard : SessionMessage

    /** The watch session's totals so far. */
    data class Totals(val totals: TrackedTotals) : SessionMessage

    /** Whether the watch saved the walk. */
    data class Result(val ok: Boolean, val message: String) : SessionMessage
}

/**
 * UTF-8 text with fields split by `|`: a kind letter, the format version, then the fields. Absent values are
 * empty, energy is in whole kilocalories times ten. The watchOS app has its own copy of this format
 * (`WatchCodec.swift`); keep both in step with these examples:
 *
 * ```
 * S|1|walk-1|1700000000000|Walking|Indoor|Pad   start: id, start (epoch ms), activity, location, source
 * N|1|1700000060000|80|140                       pad counts so far: at (epoch ms), meters, steps
 * E|1|1700000600000|walk-1|1700000700000          end: at (epoch ms), id, sync version
 * D|1|                                            discard
 * T|1|600|480|840|352|611|112|105|131             totals: seconds, meters, steps, active and total energy
 *                                                 (0.1 kcal), latest, average and maximum bpm
 * R|1|0|Not authorized                            save result: ok, message
 * ```
 *
 * Decoding returns null for anything it does not understand, including a newer version.
 */
object SessionMessageCodec {
    private const val VERSION = "1"

    fun encode(message: SessionMessage): ByteArray = when (message) {
        is SessionMessage.Start -> listOf("S", VERSION, message.id, message.startMs, message.kind.activity.name, message.kind.location.name, message.source.name)
        is SessionMessage.Counted -> listOf("N", VERSION, message.atMs, message.meters, message.steps)
        is SessionMessage.End -> listOf("E", VERSION, message.endMs, message.id, message.syncVersion)
        SessionMessage.Discard -> listOf("D", VERSION, "")
        is SessionMessage.Totals -> with(message.totals) {
            listOf(
                "T", VERSION, seconds, meters ?: "", steps ?: "", activeKcal?.let(::tenths) ?: "", totalKcal?.let(::tenths) ?: "",
                heartRate?.latestBpm ?: "", heartRate?.avgBpm ?: "", heartRate?.maxBpm ?: "",
            )
        }
        is SessionMessage.Result -> listOf("R", VERSION, if (message.ok) "1" else "0", message.message.filterNot { it == '|' })
    }.joinToString("|").encodeToByteArray()

    fun decode(bytes: ByteArray): SessionMessage? = runCatching {
        val f = bytes.decodeToString().split('|')
        if (f.size < 3 || f[1] != VERSION) return null
        when (f[0]) {
            "S" -> if (f.size == 7) {
                SessionMessage.Start(f[2], f[3].toLong(), WorkoutKind(Activity.valueOf(f[4]), Location.valueOf(f[5])), WorkoutSource.valueOf(f[6]))
            } else {
                null
            }
            "N" -> if (f.size == 5) SessionMessage.Counted(f[2].toLong(), f[3].toInt(), f[4].toInt()) else null
            "E" -> if (f.size == 5) SessionMessage.End(f[2].toLong(), f[3], f[4].toLong()) else null
            "D" -> SessionMessage.Discard
            "T" -> if (f.size == 10) {
                val avg = f[8].toIntOrNull()
                val max = f[9].toIntOrNull()
                SessionMessage.Totals(
                    TrackedTotals(
                        seconds = f[2].toInt(),
                        meters = f[3].toIntOrNull(),
                        steps = f[4].toIntOrNull(),
                        activeKcal = f[5].toIntOrNull()?.let { it / 10.0 },
                        totalKcal = f[6].toIntOrNull()?.let { it / 10.0 },
                        heartRate = if (avg != null && max != null) HeartRateStats(f[7].toIntOrNull(), avg, max) else null,
                    ),
                )
            } else {
                null
            }
            "R" -> if (f.size == 4) SessionMessage.Result(f[2] == "1", f[3]) else null
            else -> null
        }
    }.getOrNull()

    private fun tenths(kcal: Double) = (kcal * 10).roundToInt()
}
