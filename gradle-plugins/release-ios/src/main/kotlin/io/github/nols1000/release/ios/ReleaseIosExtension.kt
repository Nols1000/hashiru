package io.github.nols1000.release.ios

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

abstract class ReleaseIosExtension {
    abstract val xcodeProject: DirectoryProperty
    abstract val scheme: Property<String>
    abstract val configuration: Property<String>

    /** Apple developer team ID, passed to Xcode as the `TEAM_ID` build setting. */
    abstract val teamId: Property<String>

    /** Bundle ID of the App Store Connect app record, checked by `bootstrapTestFlight`. */
    abstract val bundleId: Property<String>

    /**
     * Directories containing the device (`iosArm64`) Kotlin frameworks the app imports. Gradle builds them before the
     * archive and Xcode's "Compile Kotlin Framework" phase is skipped, so Gradle never runs inside Gradle.
     */
    abstract val frameworkSearchPaths: ConfigurableFileCollection

    /** `owner/name` of the GitHub repository. Defaults to the repository of the current git remote. */
    abstract val githubRepo: Property<String>

    /** GitHub deployment environment that holds the App Store Connect secrets. */
    abstract val githubEnvironment: Property<String>
}
