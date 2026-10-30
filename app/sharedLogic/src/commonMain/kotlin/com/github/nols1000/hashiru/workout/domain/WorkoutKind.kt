package com.github.nols1000.hashiru.workout.domain

enum class Activity { Walking, Running }

enum class Location { Indoor, Outdoor }

/** What kind of workout is recorded; the health store files it under the matching type. */
data class WorkoutKind(val activity: Activity, val location: Location) {
    companion object {
        val IndoorWalk = WorkoutKind(Activity.Walking, Location.Indoor)
        val OutdoorWalk = WorkoutKind(Activity.Walking, Location.Outdoor)
        val IndoorRun = WorkoutKind(Activity.Running, Location.Indoor)
        val OutdoorRun = WorkoutKind(Activity.Running, Location.Outdoor)
    }
}

/** Where a workout's time, distance and steps come from: the WalkingPad, or the phone's own sensors. */
enum class WorkoutSource { Pad, Phone }
