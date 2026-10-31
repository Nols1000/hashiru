package com.github.nols1000.hashiru.workout.codec

import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.BodyWeight
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateStats
import com.github.nols1000.hashiru.workout.domain.HeartRateSummary
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WeightSource
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutPause
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSample
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.engine.ActivePause
import com.github.nols1000.hashiru.workout.engine.HeartAccumulator
import com.github.nols1000.hashiru.workout.engine.WorkoutState

/**
 * Flat text encoding for the few things kept on disk: fields split by `|`, list items by `;` and the values
 * inside an item by `,`. Every value is a number, an identifier or an enum name, apart from failure messages,
 * which are stripped of separators. Heart-rate series inside an item use ` ` between readings and `:` inside
 * one. Decoding returns null for anything it does not understand.
 *
 * Version 2 added heart rate to samples and the profile and heart-rate accumulator to the walk in progress;
 * version 3 added the workout's activity, location and source. Older text still decodes, without them: an
 * indoor walk on the pad. Version 4 added when the user ended the walk in progress; records did not change. Version 5 added the health
 * store's heart rate to records; the walk in progress did not change. Version 6 added the route to both; version 7
 * the course the walk in progress follows, and records did not change.
 */
object WorkoutCodec {
    private const val VERSION = "7"
    private const val VERSION_6 = "6"
    private const val VERSION_5 = "5"
    private const val VERSION_4 = "4"
    private const val VERSION_3 = "3"
    private const val VERSION_2 = "2"
    private const val VERSION_1 = "1"

    fun encodeCounters(c: PadCounters): String = "${c.seconds},${c.meters},${c.steps}"

    fun decodeCounters(text: String): PadCounters? {
        val parts = text.split(',')
        if (parts.size != 3) return null
        return PadCounters(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
    }

    fun encodeRecord(r: WorkoutRecord): String = listOf(
        VERSION, r.id, r.startMs, r.endMs, r.activeSeconds, r.distanceMeters, r.steps, r.activeKcal, r.totalKcal,
        r.weight.kg, r.weight.source.name, syncName(r.syncState), (r.syncState as? SyncState.Failed)?.message?.let(::clean) ?: "",
        encodePauses(r.pauses), encodeSamples(r.samples), r.kind.activity.name, r.kind.location.name, r.source.name,
        r.heartRate?.let { "${it.latestBpm ?: ""},${it.avgBpm},${it.maxBpm}" } ?: "", encodeRoute(r.route),
    ).joinToString("|")

    fun decodeRecord(text: String): WorkoutRecord? = runCatching {
        val f = text.split('|')
        val v6 = f[0] in setOf(VERSION, VERSION_6) && f.size == 20
        val v5 = v6 || f[0] == VERSION_5 && f.size == 19
        val v3 = v5 || f[0] in setOf(VERSION_4, VERSION_3) && f.size == 18
        if (!v3 && (f.size != 15 || f[0] !in setOf(VERSION_2, VERSION_1))) return null
        WorkoutRecord(
            id = f[1],
            startMs = f[2].toLong(),
            endMs = f[3].toLong(),
            activeSeconds = f[4].toInt(),
            distanceMeters = f[5].toInt(),
            steps = f[6].toInt(),
            activeKcal = f[7].toDouble(),
            totalKcal = f[8].toDouble(),
            weight = BodyWeight(f[9].toDouble(), WeightSource.valueOf(f[10])),
            syncState = syncState(f[11], f[12]),
            pauses = decodePauses(f[13]),
            samples = decodeSamples(f[14]),
            kind = if (v3) decodeKind(f[15], f[16]) else WorkoutKind.IndoorWalk,
            source = if (v3) WorkoutSource.valueOf(f[17]) else WorkoutSource.Pad,
            heartRate = if (v5) decodeHeartRateStats(f[18]) else null,
            route = if (v6) decodeRoute(f[19]) else emptyList(),
        )
    }.getOrNull()

    private fun decodeHeartRateStats(text: String): HeartRateStats? {
        val v = text.split(',').takeIf { it.size == 3 } ?: return null
        return HeartRateStats(v[0].toIntOrNull(), v[1].toInt(), v[2].toInt())
    }

    fun encodeRecords(records: List<WorkoutRecord>): String = records.joinToString("\n", transform = ::encodeRecord)

    fun decodeRecords(text: String): List<WorkoutRecord> =
        text.lineSequence().filter { it.isNotEmpty() }.mapNotNull(::decodeRecord).toList()

    fun encodeInProgress(s: WorkoutState.InProgress): String = listOf(
        VERSION, s.id, s.startMs, s.weight.kg, s.weight.source.name,
        encodeCounters(s.closed), encodeCounters(s.segmentBase), encodeCounters(s.segmentLast),
        s.windowStartMs, encodeCounters(s.windowStartTotals),
        s.pause?.reason?.name ?: "", s.pause?.sinceMs ?: "",
        encodePauses(s.pauses), encodeSamples(s.samples),
        s.profile.ageYears ?: "", s.profile.sex?.name ?: "",
        with(s.heart) { "$sumBpm,$count,$minBpm,$maxBpm,$coveredMs,${lastMs ?: ""}" }, encodeSeries(s.heart.series),
        s.kind.activity.name, s.kind.location.name, s.source.name, s.endingSinceMs ?: "", encodeRoute(s.route), s.courseId ?: "",
    ).joinToString("|")

    fun decodeInProgress(text: String): WorkoutState.InProgress? = runCatching {
        val f = text.split('|')
        val v7 = f[0] == VERSION && f.size == 24
        val v6 = v7 || f[0] == VERSION_6 && f.size == 23
        val v4 = v6 || f[0] in setOf(VERSION_5, VERSION_4) && f.size == 22
        val v3 = v4 || f[0] == VERSION_3 && f.size == 21
        val v2 = v3 || f[0] == VERSION_2 && f.size == 18
        if (!v2 && (f[0] != VERSION_1 || f.size != 14)) return null
        WorkoutState.InProgress(
            id = f[1],
            startMs = f[2].toLong(),
            weight = BodyWeight(f[3].toDouble(), WeightSource.valueOf(f[4])),
            closed = decodeCounters(f[5]) ?: return null,
            segmentBase = decodeCounters(f[6]) ?: return null,
            segmentLast = decodeCounters(f[7]) ?: return null,
            windowStartMs = f[8].toLong(),
            windowStartTotals = decodeCounters(f[9]) ?: return null,
            pause = if (f[10].isEmpty()) null else ActivePause(PauseReason.valueOf(f[10]), f[11].toLong()),
            pauses = decodePauses(f[12]),
            samples = decodeSamples(f[13]),
            profile = if (v2) UserProfile(f[14].toIntOrNull(), f[15].takeIf { it.isNotEmpty() }?.let(Sex::valueOf)) else UserProfile(),
            heart = if (v2) decodeHeart(f[16], f[17]) else HeartAccumulator(),
            kind = if (v3) decodeKind(f[18], f[19]) else WorkoutKind.IndoorWalk,
            source = if (v3) WorkoutSource.valueOf(f[20]) else WorkoutSource.Pad,
            endingSinceMs = if (v4) f[21].toLongOrNull() else null,
            route = if (v6) decodeRoute(f[22]) else emptyList(),
            courseId = if (v7) f[23].takeIf { it.isNotEmpty() } else null,
        )
    }.getOrNull()

    private fun decodeKind(activity: String, location: String) = WorkoutKind(Activity.valueOf(activity), Location.valueOf(location))

    private fun decodeHeart(values: String, series: String): HeartAccumulator {
        val v = values.split(',')
        return HeartAccumulator(v[0].toLong(), v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toLong(), v[5].toLongOrNull(), decodeSeries(series))
    }

    private fun encodeSeries(series: List<HeartRateSample>) = series.joinToString(" ") { "${it.epochMs}:${it.bpm}" }

    private fun decodeSeries(text: String): List<HeartRateSample> =
        if (text.isEmpty()) emptyList() else text.split(' ').map { val (t, bpm) = it.split(':'); HeartRateSample(t.toLong(), bpm.toInt()) }

    private fun encodePauses(pauses: List<WorkoutPause>) = pauses.joinToString(";") { "${it.startMs},${it.endMs}" }

    private fun decodePauses(text: String): List<WorkoutPause> = items(text).map {
        val v = it.split(',')
        WorkoutPause(v[0].toLong(), v[1].toLong())
    }

    /** Six values per sample, plus five for heart rate when there was any: avg, min, max, used for energy, series. */
    private fun encodeSamples(samples: List<WorkoutSample>) = samples.joinToString(";") {
        val base = "${it.startMs},${it.endMs},${it.distanceMeters},${it.steps},${it.activeKcal},${it.totalKcal}"
        val hr = it.heartRate ?: return@joinToString base
        "$base,${hr.avgBpm},${hr.minBpm},${hr.maxBpm},${if (hr.usedForEnergy) 1 else 0},${encodeSeries(hr.series)}"
    }

    private fun decodeSamples(text: String): List<WorkoutSample> = items(text).map {
        val v = it.split(',')
        val heartRate = if (v.size == 11) HeartRateSummary(v[6].toInt(), v[7].toInt(), v[8].toInt(), v[9] == "1", decodeSeries(v[10])) else null
        WorkoutSample(v[0].toLong(), v[1].toLong(), v[2].toInt(), v[3].toInt(), v[4].toDouble(), v[5].toDouble(), heartRate)
    }

    /** Five values per point: time, latitude, longitude, altitude (empty when unknown), accuracy. */
    fun encodeRoute(route: List<RoutePoint>) = route.joinToString(";") {
        "${it.atMs},${it.latitude},${it.longitude},${it.altitudeM ?: ""},${it.accuracyM}"
    }

    fun decodeRoute(text: String): List<RoutePoint> = items(text).map {
        val v = it.split(',')
        RoutePoint(v[0].toLong(), v[1].toDouble(), v[2].toDouble(), v[3].toDoubleOrNull(), v[4].toDouble())
    }

    private fun items(text: String) = if (text.isEmpty()) emptyList() else text.split(';')

    private fun syncName(state: SyncState) = when (state) {
        SyncState.Pending -> "Pending"
        SyncState.Busy -> "Busy"
        SyncState.Saved -> "Saved"
        is SyncState.Failed -> "Failed"
        SyncState.NotAuthorized -> "NotAuthorized"
        SyncState.Unavailable -> "Unavailable"
    }

    private fun syncState(name: String, message: String): SyncState = when (name) {
        "Pending" -> SyncState.Pending
        "Busy" -> SyncState.Busy
        "Saved" -> SyncState.Saved
        "Failed" -> SyncState.Failed(message)
        "NotAuthorized" -> SyncState.NotAuthorized
        "Unavailable" -> SyncState.Unavailable
        else -> error("Unknown sync state $name")
    }

    private fun clean(message: String) = message.filterNot { it in "|;,\n\r" }
}
