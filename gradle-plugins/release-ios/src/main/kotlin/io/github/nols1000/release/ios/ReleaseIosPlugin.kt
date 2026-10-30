package io.github.nols1000.release.ios

import io.github.nols1000.release.common.ReleaseVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/**
 * Archives and publishes an iOS app to TestFlight with xcodebuild and an App Store Connect API key.
 *
 * Tasks: `archiveIos`, `publishTestFlight` (used by CI) and `bootstrapTestFlight` (one-time setup).
 */
class ReleaseIosPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<ReleaseIosExtension>("releaseIos").apply {
            configuration.convention("Release")
            githubEnvironment.convention("testflight")
        }
        val version = ReleaseVersion.from(project.providers)
        val buildDir = project.layout.buildDirectory.dir("ios")

        val archiveTask = project.tasks.register<ArchiveIosTask>("archiveIos") {
            xcodeProject.set(extension.xcodeProject)
            scheme.set(extension.scheme)
            configuration.set(extension.configuration)
            teamId.set(extension.teamId)
            versionName.set(version.name)
            buildNumber.set(version.buildNumber)
            frameworkSearchPaths.from(extension.frameworkSearchPaths)
            derivedData.set(buildDir.map { it.dir("DerivedData") })
            archive.set(buildDir.zip(extension.scheme) { dir, scheme -> dir.dir("$scheme.xcarchive") })
        }

        project.tasks.register<PublishTestFlightTask>("publishTestFlight") {
            archive.set(archiveTask.flatMap { it.archive })
            teamId.set(extension.teamId)
            exportDir.set(buildDir.map { it.dir("export") })
        }

        project.tasks.register<BootstrapTestFlightTask>("bootstrapTestFlight") {
            val providers = project.providers
            val rootDir = project.rootDir
            keyFile.fileProvider(providers.gradleProperty("ascKeyPath").map { rootDir.resolve(it) })
            keyId.set(providers.gradleProperty("ascKeyId"))
            issuerId.set(providers.gradleProperty("ascIssuerId"))
            teamId.set(extension.teamId)
            bundleId.set(extension.bundleId)
            githubRepo.set(extension.githubRepo)
            githubEnvironment.set(extension.githubEnvironment)
        }
    }
}
