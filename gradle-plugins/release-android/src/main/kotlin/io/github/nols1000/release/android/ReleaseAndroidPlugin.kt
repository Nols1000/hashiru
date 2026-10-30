package io.github.nols1000.release.android

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import io.github.nols1000.release.common.ReleaseVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Versions, signs and publishes an Android app to Google Play.
 *
 * Tasks: `publishPlay` (used by CI) and `bootstrapPlayPublishing` (one-time setup).
 */
class ReleaseAndroidPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<ReleaseAndroidExtension>("releaseAndroid").apply {
            track.convention("internal")
            versionCodeOffset.convention(0)
            releaseStatus.convention("completed")
            githubEnvironment.convention("google-play-store-internal")
            keyAlias.convention("upload")
        }
        project.pluginManager.withPlugin("com.android.application") {
            configure(project, extension)
        }
    }

    private fun configure(project: Project, extension: ReleaseAndroidExtension) {
        val version = ReleaseVersion.from(project.providers)
        val uploadKey = uploadKey(project, extension)
        val androidComponents = project.extensions.getByType<ApplicationAndroidComponentsExtension>()

        val publishPlay = project.tasks.register<PublishPlayTask>("publishPlay") {
            track.set(extension.track)
            releaseStatus.set(extension.releaseStatus)
            releaseName.set(version.name)
        }
        val bootstrap = project.tasks.register<BootstrapPlayPublishingTask>("bootstrapPlayPublishing") {
            gcpProject.set(extension.gcpProject)
            playDeveloperId.set(extension.playDeveloperId)
            githubRepo.set(extension.githubRepo)
            githubEnvironment.set(extension.githubEnvironment)
            keystoreFile.set(extension.keystoreFile)
            keyAlias.set(extension.keyAlias)
            keystoreOnly.set(project.providers.gradleProperty("keystoreOnly").map { it != "false" }.orElse(false))
        }

        androidComponents.finalizeDsl { android ->
            val key = uploadKey.orNull ?: return@finalizeDsl
            // The container is declared with `out`, so configure the created element instead of passing an action.
            val signingConfig = android.signingConfigs.create("upload").apply {
                storeFile = key.storeFile
                storePassword = key.storePassword
                keyAlias = key.keyAlias
                keyPassword = key.keyPassword
            }
            android.buildTypes.getByName("release").signingConfig = signingConfig
        }

        androidComponents.onVariants { variant ->
            variant.outputs.forEach { output ->
                output.versionName.set(version.name)
                output.versionCode.set(version.buildNumber.zip(extension.versionCodeOffset, Int::plus))
            }
        }
        androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
            publishPlay.configure {
                bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
                packageName.set(variant.applicationId)
            }
            bootstrap.configure {
                packageName.set(variant.applicationId)
            }
        }
    }

    /** Upload key from the CI environment, or else from the local keystore created by `bootstrapPlayPublishing`. */
    private fun uploadKey(project: Project, extension: ReleaseAndroidExtension): Provider<UploadKey> {
        val providers = project.providers
        val fromEnvironment = providers.environmentVariable("ANDROID_KEYSTORE_FILE").map { path ->
            UploadKey(
                storeFile = File(path),
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get(),
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get(),
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get(),
            )
        }
        val fromKeystoreFile = extension.keystoreFile.flatMap { keystore ->
            val storeFile = keystore.asFile
            providers.fileContents(project.objects.fileProperty().fileValue(UploadKey.propertiesFile(storeFile)))
                .asText
                .map { UploadKey.parse(storeFile, it) }
        }
        return fromEnvironment.orElse(fromKeystoreFile)
    }
}
