package com.github.nols1000.hashiru.course.adapter

import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.port.CourseLibraryPort

/** Keeps courses for the life of the process; used by tests and previews. */
class InMemoryCourseLibrary(courses: List<Course> = emptyList()) : CourseLibraryPort {
    private val courses = courses.associateBy { it.id }.toMutableMap()

    override fun list(): List<Course> = courses.values.sortedByDescending { it.importedAtMs }

    override fun load(id: String): Course? = courses[id]

    override fun save(course: Course) {
        courses[course.id] = course
    }

    override fun delete(id: String) {
        courses.remove(id)
    }
}
