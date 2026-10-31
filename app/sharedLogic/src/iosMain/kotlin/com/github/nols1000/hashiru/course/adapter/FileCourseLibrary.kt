package com.github.nols1000.hashiru.course.adapter

import com.github.nols1000.hashiru.course.codec.CourseCodec
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.port.CourseLibraryPort
import com.github.nols1000.hashiru.log.log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL

/**
 * Keeps each course as a file of its own under Application Support/Courses, in [CourseCodec]'s text, written
 * atomically. Read once, then held in memory; the library is only changed through here.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileCourseLibrary : CourseLibraryPort {
    private val files = NSFileManager.defaultManager
    private val directory: NSURL? by lazy {
        val support = files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, create = true, error = null)
        support?.URLByAppendingPathComponent("Courses", isDirectory = true)?.also {
            files.createDirectoryAtURL(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    private val courses: MutableMap<String, Course> by lazy {
        val dir = directory ?: return@lazy mutableMapOf()
        files.contentsOfDirectoryAtURL(dir, includingPropertiesForKeys = null, options = 0u, error = null).orEmpty()
            .filterIsInstance<NSURL>()
            .filter { it.pathExtension == EXTENSION }
            .mapNotNull { url ->
                NSString.stringWithContentsOfURL(url, encoding = NSUTF8StringEncoding, error = null)?.let(CourseCodec::decode)
            }
            .associateBy { it.id }
            .toMutableMap()
    }

    override fun list(): List<Course> = courses.values.sortedByDescending { it.importedAtMs }

    override fun load(id: String): Course? = courses[id]

    override fun save(course: Course) {
        val url = file(course.id) ?: return
        val written = NSString.create(string = CourseCodec.encode(course))
            .writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (written) courses[course.id] = course else log("FileCourseLibrary", "could not write ${course.id}")
    }

    override fun delete(id: String) {
        courses.remove(id)
        file(id)?.let { files.removeItemAtURL(it, error = null) }
    }

    /** IDs are UUIDs; anything else is kept out of the path. */
    private fun file(id: String): NSURL? {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' }
        return directory?.URLByAppendingPathComponent("$safe.$EXTENSION")
    }

    private companion object {
        const val EXTENSION = "course"
    }
}
