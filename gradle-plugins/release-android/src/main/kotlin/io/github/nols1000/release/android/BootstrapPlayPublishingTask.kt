package io.github.nols1000.release.android

import io.github.nols1000.release.common.GitHubEnvironment
import io.github.nols1000.release.common.Http
import io.github.nols1000.release.common.HttpException
import io.github.nols1000.release.common.run
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject

/**
 * One-time, idempotent setup for keyless publishing to Google Play from GitHub Actions:
 * upload key, Workload Identity Federation, service account, Play Console access and GitHub environment.
 * Uses the local `gcloud` and `gh` logins.
 */
abstract class BootstrapPlayPublishingTask : DefaultTask() {
    @get:Internal abstract val packageName: Property<String>
    @get:Internal abstract val gcpProject: Property<String>
    @get:Internal abstract val playDeveloperId: Property<String>
    @get:Internal abstract val githubRepo: Property<String>
    @get:Internal abstract val githubEnvironment: Property<String>
    @get:Internal abstract val keystoreFile: RegularFileProperty
    @get:Internal abstract val keyAlias: Property<String>

    /** Only create the upload key, e.g. to sign the first bundle that has to be uploaded by hand. */
    @get:Internal abstract val keystoreOnly: Property<Boolean>

    @get:Inject abstract val exec: ExecOperations

    init {
        group = "publishing"
        description = "Sets up keyless publishing to Google Play from GitHub Actions."
        doNotTrackState("Changes external services")
    }

    @TaskAction
    fun bootstrap() {
        val uploadKey = ensureUploadKey()
        if (keystoreOnly.get()) return

        val project = required(gcpProject, "release.gcpProject")
        val repo = GitHubEnvironment.resolveRepo(exec, githubRepo.orNull)
        val environment = GitHubEnvironment(exec, logger, repo, githubEnvironment.get())

        gcloud("services", "enable", "androidpublisher.googleapis.com", "iam.googleapis.com", "iamcredentials.googleapis.com", "sts.googleapis.com")
        val projectNumber = gcloud("projects", "describe", project, "--format=value(projectNumber)").stdout
        val provider = ensureWorkloadIdentityProvider(projectNumber, repo, environment.name)
        val serviceAccount = ensureServiceAccount(project, projectNumber, repo)
        grantPlayAccess(project, serviceAccount)

        environment.ensureExists()
        environment.setSecret("ANDROID_UPLOAD_KEYSTORE_B64", Base64.getEncoder().encodeToString(uploadKey.storeFile.readBytes()))
        environment.setSecret("ANDROID_KEYSTORE_PASSWORD", uploadKey.storePassword)
        environment.setSecret("ANDROID_KEY_ALIAS", uploadKey.keyAlias)
        environment.setSecret("ANDROID_KEY_PASSWORD", uploadKey.keyPassword)
        environment.setVariable("GCP_WIF_PROVIDER", provider)
        environment.setVariable("GCP_SERVICE_ACCOUNT", serviceAccount)
    }

    private fun ensureUploadKey(): UploadKey {
        val storeFile = keystoreFile.get().asFile
        val propertiesFile = UploadKey.propertiesFile(storeFile)
        if (storeFile.exists()) {
            if (!propertiesFile.exists()) throw GradleException("$storeFile exists but its passwords file $propertiesFile is missing")
            logger.lifecycle("Using existing upload key $storeFile")
            return UploadKey.parse(storeFile, propertiesFile.readText())
        }

        storeFile.parentFile.mkdirs()
        val password = randomPassword()
        exec.run(
            "keytool", "-genkeypair",
            "-keystore", storeFile.path, "-storetype", "PKCS12",
            "-alias", keyAlias.get(), "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
            "-dname", "CN=${packageName.get()}",
            "-storepass:env", "KEYSTORE_PASSWORD",
            environment = mapOf("KEYSTORE_PASSWORD" to password),
        )
        // PKCS12 keystores use the store password for the key as well.
        val uploadKey = UploadKey(storeFile, password, keyAlias.get(), password)
        uploadKey.writeProperties()
        logger.warn(
            """
            Created upload key $storeFile with its passwords in $propertiesFile.
            BACK UP BOTH FILES. Losing them means asking Google Play support to reset the upload key.
            """.trimIndent()
        )
        return uploadKey
    }

    private fun ensureWorkloadIdentityProvider(projectNumber: String, repo: String, environment: String): String {
        if (!gcloud("iam", "workload-identity-pools", "describe", POOL, "--location=global", check = false).succeeded) {
            gcloud("iam", "workload-identity-pools", "create", POOL, "--location=global", "--display-name=GitHub Actions")
        }
        val exists = gcloud("iam", "workload-identity-pools", "providers", "describe", PROVIDER, "--workload-identity-pool=$POOL", "--location=global", check = false).succeeded
        // Updating on every run keeps the condition in sync with the configured repository and environment.
        gcloud(
            "iam", "workload-identity-pools", "providers", if (exists) "update-oidc" else "create-oidc", PROVIDER,
            "--workload-identity-pool=$POOL", "--location=global",
            "--issuer-uri=https://token.actions.githubusercontent.com",
            "--attribute-mapping=google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.environment=assertion.environment",
            "--attribute-condition=assertion.repository == '$repo' && assertion.environment == '$environment'",
        )
        return "projects/$projectNumber/locations/global/workloadIdentityPools/$POOL/providers/$PROVIDER"
    }

    private fun ensureServiceAccount(project: String, projectNumber: String, repo: String): String {
        val email = "$SERVICE_ACCOUNT@$project.iam.gserviceaccount.com"
        if (!gcloud("iam", "service-accounts", "describe", email, check = false).succeeded) {
            gcloud("iam", "service-accounts", "create", SERVICE_ACCOUNT, "--display-name=Google Play publisher")
        }
        gcloud(
            "iam", "service-accounts", "add-iam-policy-binding", email,
            "--role=roles/iam.workloadIdentityUser",
            "--member=principalSet://iam.googleapis.com/projects/$projectNumber/locations/global/workloadIdentityPools/$POOL/attribute.repository/$repo",
        )
        return email
    }

    private fun grantPlayAccess(project: String, serviceAccount: String) {
        val developerId = required(playDeveloperId, "release.playDeveloperId")
        val manualHint = "In Play Console > Users and permissions, invite $serviceAccount with 'Release to testing tracks' for ${packageName.get()}."
        val token = exec.run("gcloud", "auth", "application-default", "print-access-token", check = false)
        if (!token.succeeded) {
            logger.warn(
                "Could not get a Play API token. Run `gcloud auth application-default login " +
                    "--scopes=openid,https://www.googleapis.com/auth/cloud-platform,${GooglePlayApi.SCOPE}` and re-run, or: $manualHint"
            )
            return
        }
        val user = "developers/$developerId/users/$serviceAccount"
        try {
            Http.json(
                "POST",
                "https://androidpublisher.googleapis.com/androidpublisher/v3/developers/$developerId/users",
                bearer = token.stdout,
                // User credentials need a quota project that has the Play API enabled.
                headers = mapOf("x-goog-user-project" to project),
                body = mapOf(
                    "name" to user,
                    "email" to serviceAccount,
                    "grants" to listOf(
                        mapOf(
                            "name" to "$user/grants/${packageName.get()}",
                            "packageName" to packageName.get(),
                            "appLevelPermissions" to listOf("CAN_MANAGE_TRACK_APKS", "CAN_MANAGE_DRAFT_APPS"),
                        ),
                    ),
                ),
            )
            logger.lifecycle("Granted $serviceAccount release access to ${packageName.get()} in Play Console")
        } catch (e: HttpException) {
            when (e.status) {
                409 -> logger.lifecycle("$serviceAccount already has access in Play Console")
                else -> logger.warn("Could not grant Play Console access (${e.message}). $manualHint")
            }
        }
    }

    private fun gcloud(vararg args: String, check: Boolean = true) =
        exec.run("gcloud", *args, "--project=${gcpProject.get()}", "--quiet", check = check)

    private fun required(property: Property<String>, gradleProperty: String): String =
        property.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Set $gradleProperty in gradle.properties to run $name")

    private fun randomPassword(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))

    private companion object {
        const val POOL = "github"
        const val PROVIDER = "github"
        const val SERVICE_ACCOUNT = "play-publisher"
    }
}
