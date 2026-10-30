package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import kotlinx.cinterop.ExperimentalForeignApi
import platform.HealthKit.HKDevice
import platform.HealthKit.HKMetadataKeyExternalUUID
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.countUnit
import platform.HealthKit.meterUnit
import kotlin.math.roundToInt

/**
 * The pad's counts for the walk [id], as distance and step samples for a workout session that runs on this
 * phone. Each sample covers the time since the one before; the walk's ID as external UUID lets [delete] find them.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PadSamples(private val id: String, startMs: Long, private val types: HealthTypes, private val device: HKDevice) {
    private val increments = CountedIncrements(startMs)

    /** Goes on from what a recovered builder already holds. */
    fun seed(builder: HKWorkoutBuilder, types: HealthTypes) {
        increments.seed(
            builder.sum(types.distance, HKUnit.meterUnit()).roundToInt(),
            builder.sum(types.steps, HKUnit.countUnit()).roundToInt(),
            nowMs(),
        )
    }

    fun samples(counted: WorkoutEvent.Counted): List<HKQuantitySample> {
        val more = increments.next(counted.atMs, counted.meters, counted.steps) ?: return emptyList()
        val start = date(more.fromMs)
        val end = date(more.toMs)
        val metadata = mapOf<Any?, Any>(HKMetadataKeyExternalUUID to id)
        return listOfNotNull(
            more.meters.takeIf { it > 0 }?.let {
                HKQuantitySample.quantitySampleWithType(types.distance, HKQuantity.quantityWithUnit(HKUnit.meterUnit(), it.toDouble()), start, end, device, metadata)
            },
            more.steps.takeIf { it > 0 }?.let {
                HKQuantitySample.quantitySampleWithType(types.steps, HKQuantity.quantityWithUnit(HKUnit.countUnit(), it.toDouble()), start, end, device, metadata)
            },
        )
    }
}
