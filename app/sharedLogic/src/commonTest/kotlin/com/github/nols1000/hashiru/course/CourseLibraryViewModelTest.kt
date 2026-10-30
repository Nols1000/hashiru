package com.github.nols1000.hashiru.course

import androidx.lifecycle.ViewModelStore
import com.github.nols1000.hashiru.course.adapter.InMemoryCourseLibrary
import com.github.nols1000.hashiru.course.domain.ImportFailure
import com.github.nols1000.hashiru.course.domain.ImportResult
import com.github.nols1000.hashiru.course.presentation.CourseLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CourseLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private var ids = 0
    private val library = InMemoryCourseLibrary()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        CourseLibraryViewModel(library, { 1_000L + ids }, { "course-${++ids}" }, dispatcher).also { viewModels.put("courses", it) }

    private val gpx = """<gpx><trk><trkseg><trkpt lat="52.37" lon="4.89"/><trkpt lat="52.38" lon="4.90"/></trkseg></trk></gpx>"""

    private fun importing(viewModel: CourseLibraryViewModel, name: String, bytes: ByteArray): ImportResult {
        var result: ImportResult? = null
        viewModel.import(name, bytes) { result = it }
        dispatcher.scheduler.runCurrent()
        return result!!
    }

    @Test
    fun filesImportByExtensionOrContentAndAreListedNewestFirst() = runTest(dispatcher) {
        val viewModel = viewModel()
        val fit = FitWriter().apply {
            defineRecord()
            record(51.50, -0.12, null)
            record(51.51, -0.13, null)
        }.build()

        val first = assertIs<ImportResult.Imported>(importing(viewModel, "Canal.gpx", gpx.encodeToByteArray())).course
        val second = assertIs<ImportResult.Imported>(importing(viewModel, "download", fit)).course

        assertEquals("Canal", first.name)
        assertEquals("download", second.name)
        assertEquals(listOf(second.id, first.id), viewModel.uiState.value.courses.map { it.id })
        assertTrue(first.distanceMeters in 1_300..1_400)
    }

    @Test
    fun unusableFilesAreRejected() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertEquals(ImportResult.Failed(ImportFailure.Unrecognized), importing(viewModel, "notes.txt", "hello".encodeToByteArray()))
        assertEquals(ImportResult.Failed(ImportFailure.Empty), importing(viewModel, "empty.gpx", "<gpx/>".encodeToByteArray()))
        assertTrue(viewModel.uiState.value.courses.isEmpty())
    }

    @Test
    fun deletingRemovesTheCourse() = runTest(dispatcher) {
        val viewModel = viewModel()
        val course = assertIs<ImportResult.Imported>(importing(viewModel, "Canal.gpx", gpx.encodeToByteArray())).course
        viewModel.delete(course)

        assertTrue(viewModel.uiState.value.courses.isEmpty())
        assertEquals(null, library.load(course.id))
    }
}
