package com.github.nols1000.hashiru.course.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.nols1000.hashiru.course.codec.CourseImporter
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.ImportResult
import com.github.nols1000.hashiru.course.port.CourseLibraryPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CourseLibraryUiState(val courses: List<Course> = emptyList())

/** The state before anything is known; for Swift, which cannot use the constructor's default arguments. */
fun initialCourseLibraryUiState(): CourseLibraryUiState = CourseLibraryUiState()

/** The routes on this device: imported from GPX and FIT files, listed newest first, and deleted. */
class CourseLibraryViewModel internal constructor(
    private val library: CourseLibraryPort,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    /** Where files are parsed; a long recorded track takes a moment. */
    private val parsing: CoroutineDispatcher,
) : ViewModel() {
    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    constructor(library: CourseLibraryPort) : this(
        library,
        { Clock.System.now().toEpochMilliseconds() },
        { Uuid.random().toString() },
        Dispatchers.Default,
    )

    private val _uiState = MutableStateFlow(CourseLibraryUiState(library.list()))
    val uiState: StateFlow<CourseLibraryUiState> = _uiState.asStateFlow()

    /** Imports the file [fileName] holding [bytes] and hands the result to [onResult] on the main thread. */
    fun import(fileName: String, bytes: ByteArray, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(parsing) { CourseImporter.import(fileName, bytes, newId(), nowMs()) }
            if (result is ImportResult.Imported) {
                library.save(result.course)
                reload()
            }
            onResult(result)
        }
    }

    fun delete(course: Course) {
        library.delete(course.id)
        reload()
    }

    fun course(id: String): Course? = library.load(id)

    private fun reload() {
        _uiState.value = CourseLibraryUiState(library.list())
    }

    /** Swift-friendly observation of [uiState]; call `close()` on the result to stop. */
    fun watch(onChange: (CourseLibraryUiState) -> Unit): AutoCloseable {
        val job = viewModelScope.launch { uiState.collect { onChange(it) } }
        return AutoCloseable { job.cancel() }
    }
}
