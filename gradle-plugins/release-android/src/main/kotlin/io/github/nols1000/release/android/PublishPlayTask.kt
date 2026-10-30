package io.github.nols1000.release.android

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Uploads the release bundle to Google Play and rolls it out to [track]. */
abstract class PublishPlayTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundle: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val track: Property<String>

    @get:Input
    abstract val releaseStatus: Property<String>

    @get:Input
    abstract val releaseName: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    init {
        group = "publishing"
        description = "Uploads the release bundle to Google Play."
        doNotTrackState("Uploads to Google Play")
    }

    @TaskAction
    fun publish() {
        val play = GooglePlayApi(GoogleAuth(exec).accessToken(GooglePlayApi.SCOPE), packageName.get())
        val editId = play.insertEdit()
        try {
            val versionCode = play.uploadBundle(editId, bundle.get().asFile)
            play.updateTrack(editId, track.get(), releaseName.get(), versionCode, releaseStatus.get())
            play.commit(editId)
            logger.lifecycle("Released ${packageName.get()} ${releaseName.get()} ($versionCode) to the ${track.get()} track")
        } catch (e: Exception) {
            runCatching { play.deleteEdit(editId) }
            throw e
        }
    }
}
