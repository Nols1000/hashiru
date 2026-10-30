package com.github.nols1000.hashiru.course.port

import com.github.nols1000.hashiru.course.domain.Course

/** Driven port: the courses kept on this device (files on iOS). Courses are small enough to read on the spot. */
interface CourseLibraryPort {
    /** Every course, newest import first. */
    fun list(): List<Course>

    fun load(id: String): Course?

    /** Adds [course], or replaces the one with its ID. */
    fun save(course: Course)

    fun delete(id: String)
}
