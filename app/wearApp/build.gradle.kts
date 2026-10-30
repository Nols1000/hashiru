import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    id("io.github.nols1000.release.android")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":app:sharedLogic"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.composeMaterial3)
    implementation(libs.androidx.wear.composeFoundation)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.healthServices)
    implementation(libs.playServices.wearable)

    constraints {
        // Play services pulls in Fragment 1.1, whose Activity Result handling is broken; not a new dependency.
        implementation(libs.androidx.fragment)
    }
}

android {
    namespace = "com.github.nols1000.hashiru.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // The Data Layer only links apps that share the phone app's package name and signing key.
        applicationId = "com.github.nols1000.hashiru"
        minSdk = libs.versions.android.wearMinSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

releaseAndroid {
    // Play's Wear OS form factor track; the same package as the phone app, so its own version-code range.
    track = "wear:internal"
    versionCodeOffset = 1_000_000_000
    gcpProject = providers.gradleProperty("release.gcpProject")
    playDeveloperId = providers.gradleProperty("release.playDeveloperId")
    keystoreFile = layout.file(providers.systemProperty("user.home").map { File(it, ".hashiru-secrets/upload-keystore.p12") })
}
