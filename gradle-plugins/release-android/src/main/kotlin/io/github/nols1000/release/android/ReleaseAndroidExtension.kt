package io.github.nols1000.release.android

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

abstract class ReleaseAndroidExtension {
    /** Google Play track to release to. */
    abstract val track: Property<String>

    /**
     * Added to the build number to form the version code. Every bundle of one package needs its own version
     * code, so a second form factor of the same app (a Wear OS app) uses a different range.
     */
    abstract val versionCodeOffset: Property<Int>

    /** Status of the Play release: `completed`, or `draft` while the app has not been published yet. */
    abstract val releaseStatus: Property<String>

    /** `owner/name` of the GitHub repository. Defaults to the repository of the current git remote. */
    abstract val githubRepo: Property<String>

    /** GitHub deployment environment that holds the publishing secrets. */
    abstract val githubEnvironment: Property<String>

    /** GCP project that hosts the Workload Identity Federation pool and the publishing service account. */
    abstract val gcpProject: Property<String>

    /** Google Play developer account ID, the number in the Play Console URL. */
    abstract val playDeveloperId: Property<String>

    /** Upload keystore used to sign release bundles locally. Its passwords live next to it in `<file>.properties`. */
    abstract val keystoreFile: RegularFileProperty

    abstract val keyAlias: Property<String>
}
