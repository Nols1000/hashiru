package io.github.nols1000.release.ios

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Signs the archive for the App Store and lets Xcode upload it to App Store Connect / TestFlight. */
abstract class PublishTestFlightTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: DirectoryProperty

    @get:Input abstract val teamId: Property<String>
    @get:Internal abstract val exportDir: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    init {
        group = "publishing"
        description = "Uploads the iOS archive to TestFlight."
        doNotTrackState("Uploads to App Store Connect")
    }

    @TaskAction
    fun publish() {
        val exportDir = exportDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val exportOptions = exportDir.resolve("ExportOptions.plist")
        exportOptions.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>method</key><string>app-store-connect</string>
                <key>destination</key><string>upload</string>
                <key>teamID</key><string>${teamId.get()}</string>
                <key>signingStyle</key><string>automatic</string>
                <key>manageAppVersionAndBuildNumber</key><false/>
            </dict>
            </plist>
            """.trimIndent()
        )
        exec.exec {
            commandLine(
                listOf(
                    "xcodebuild", "-exportArchive",
                    "-archivePath", archive.get().asFile.path,
                    "-exportPath", exportDir.path,
                    "-exportOptionsPlist", exportOptions.path,
                    "-allowProvisioningUpdates",
                ) + AppStoreConnectKey.fromEnvironment()?.xcodebuildArgs().orEmpty()
            )
        }
    }
}
