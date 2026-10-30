package io.github.nols1000.release.ios

import io.github.nols1000.release.common.GitHubEnvironment
import io.github.nols1000.release.common.Http
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.net.URLEncoder
import java.util.Base64
import javax.inject.Inject

/**
 * One-time, idempotent setup for publishing to TestFlight from GitHub Actions: checks the App Store Connect
 * API key and app record, then stores the key in the GitHub environment. Uses the local `gh` login.
 */
abstract class BootstrapTestFlightTask : DefaultTask() {
    @get:Internal abstract val keyFile: RegularFileProperty
    @get:Internal abstract val keyId: Property<String>
    @get:Internal abstract val issuerId: Property<String>
    @get:Internal abstract val teamId: Property<String>
    @get:Internal abstract val bundleId: Property<String>
    @get:Internal abstract val githubRepo: Property<String>
    @get:Internal abstract val githubEnvironment: Property<String>

    @get:Inject abstract val exec: ExecOperations

    init {
        group = "publishing"
        description = "Sets up publishing to TestFlight from GitHub Actions."
        doNotTrackState("Changes external services")
    }

    @TaskAction
    fun bootstrap() {
        val key = AppStoreConnectKey(
            keyFile = required(keyFile.map { it.asFile }, "-PascKeyPath"),
            keyId = required(keyId, "-PascKeyId"),
            issuerId = required(issuerId, "-PascIssuerId"),
        )
        val team = required(teamId, "release.appleTeamId")
        val bundle = required(bundleId, "releaseIos.bundleId")

        val apps = Http.json(
            "GET",
            "https://api.appstoreconnect.apple.com/v1/apps?filter%5BbundleId%5D=${URLEncoder.encode(bundle, Charsets.UTF_8)}",
            bearer = key.jwt(),
        )
        if ((apps["data"] as List<*>).isEmpty()) {
            throw GradleException(
                "App Store Connect has no app with bundle ID $bundle. Create it at https://appstoreconnect.apple.com/apps " +
                    "(Apple's API cannot create apps), then run this task again."
            )
        }
        logger.lifecycle("Found App Store Connect app $bundle")

        val repo = GitHubEnvironment.resolveRepo(exec, githubRepo.orNull)
        val environment = GitHubEnvironment(exec, logger, repo, githubEnvironment.get())
        environment.ensureExists()
        environment.setSecret("ASC_KEY_P8_B64", Base64.getEncoder().encodeToString(key.keyFile.readBytes()))
        environment.setSecret("ASC_KEY_ID", key.keyId)
        environment.setSecret("ASC_ISSUER_ID", key.issuerId)
        environment.setSecret("APPLE_TEAM_ID", team)
    }

    private fun <T : Any> required(property: Provider<T>, hint: String): T =
        property.orNull ?: throw GradleException("Missing $hint for $name")
}
