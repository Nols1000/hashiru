package com.github.nols1000.hashiru.workout.adapter

/** What a workout session has not been given yet: the counts from [fromMs] to [toMs]. */
data class Increment(val fromMs: Long, val toMs: Long, val meters: Int, val steps: Int)

/**
 * Turns a pad walk's cumulative counts into increments a workout session can take as samples, each covering the
 * time since the one before. Counts that are not newer than what was added, as a repeated or late message, add
 * nothing; a lost message only makes the next increment span more time.
 */
class CountedIncrements(private var lastMs: Long) {
    private var meters = 0
    private var steps = 0

    /** Takes the counts a recovered session already holds, as of [atMs]. */
    fun seed(meters: Int, steps: Int, atMs: Long) {
        this.meters = meters
        this.steps = steps
        lastMs = maxOf(lastMs, atMs)
    }

    /** The increment up to [atMs], or null when there is nothing new. */
    fun next(atMs: Long, meters: Int, steps: Int): Increment? {
        if (atMs <= lastMs) return null
        val more = Increment(lastMs, atMs, (meters - this.meters).coerceAtLeast(0), (steps - this.steps).coerceAtLeast(0))
        if (more.meters == 0 && more.steps == 0) return null
        this.meters = maxOf(this.meters, meters)
        this.steps = maxOf(this.steps, steps)
        lastMs = atMs
        return more
    }
}
