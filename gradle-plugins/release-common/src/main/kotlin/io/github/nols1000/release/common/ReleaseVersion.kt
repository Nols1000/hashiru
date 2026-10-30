package io.github.nols1000.release.common

import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * Version shared by all platforms. CI passes `-Prelease.version` (the tag without `v`)
 * and `-Prelease.buildNumber` (a monotonically increasing run number).
 */
class ReleaseVersion(
    val name: Provider<String>,
    val buildNumber: Provider<Int>,
) {
    companion object {
        fun from(providers: ProviderFactory) = ReleaseVersion(
            name = providers.gradleProperty("release.version").orElse("1.0.0"),
            buildNumber = providers.gradleProperty("release.buildNumber").map(String::toInt).orElse(1),
        )
    }
}
