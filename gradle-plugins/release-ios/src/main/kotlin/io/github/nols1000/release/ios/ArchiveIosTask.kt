package io.github.nols1000.release.ios

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Archives the app for App Store distribution with automatic (cloud) signing. */
abstract class ArchiveIosTask : DefaultTask() {
    @get:Internal abstract val xcodeProject: DirectoryProperty
    @get:Input abstract val scheme: Property<String>
    @get:Input abstract val configuration: Property<String>
    @get:Input abstract val teamId: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val frameworkSearchPaths: ConfigurableFileCollection

    @get:Internal abstract val derivedData: DirectoryProperty
    @get:OutputDirectory abstract val archive: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    init {
        group = "publishing"
        description = "Archives the iOS app for App Store distribution."
        doNotTrackState("Xcode sources are not tracked by Gradle")
    }

    @TaskAction
    fun archiveApp() {
        archive.get().asFile.deleteRecursively()
        val searchPaths = frameworkSearchPaths.files.joinToString(" ") { "\"${it.absolutePath}\"" }
        exec.exec {
            commandLine(
                listOf(
                    "xcodebuild", "archive",
                    "-project", xcodeProject.get().asFile.path,
                    "-scheme", scheme.get(),
                    "-configuration", configuration.get(),
                    "-destination", "generic/platform=iOS",
                    "-archivePath", archive.get().asFile.path,
                    "-derivedDataPath", derivedData.get().asFile.path,
                    "-allowProvisioningUpdates",
                ) + AppStoreConnectKey.fromEnvironment()?.xcodebuildArgs().orEmpty() + listOf(
                    "TEAM_ID=${teamId.get()}",
                    "MARKETING_VERSION=${versionName.get()}",
                    "CURRENT_PROJECT_VERSION=${buildNumber.get()}",
                    "FRAMEWORK_SEARCH_PATHS=\$(inherited) $searchPaths",
                )
            )
            // Skips the Xcode build phase that would run Gradle again; the frameworks are already built.
            environment("OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED", "YES")
        }
    }
}
