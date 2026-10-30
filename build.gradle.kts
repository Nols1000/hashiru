plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    id("io.github.nols1000.release.ios")
}

// Kotlin/JS uses the Node.js installed on the machine (and on CI runners) instead of downloading its own.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download = false
    }
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download = false
}

releaseIos {
    xcodeProject = layout.projectDirectory.dir("app/iosApp/iosApp.xcodeproj")
    scheme = "iosApp"
    teamId = providers.environmentVariable("APPLE_TEAM_ID").orElse(providers.gradleProperty("release.appleTeamId"))
    bundleId = "com.github.nols1000.hashiru"
    frameworkSearchPaths.from(
        files("app/sharedLogic/build/bin/iosArm64/releaseFramework") {
            builtBy(":app:sharedLogic:linkReleaseFrameworkIosArm64")
        }
    )
}