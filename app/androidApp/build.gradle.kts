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
/** Where live shares go, and where viewers open their links. */
val RELEASE_RELAY_URL = "https://hashiru.tw-esport.de/api"
val RELEASE_VIEWER_URL = "https://hashiru.tw-esport.de"

dependencies {
    implementation(project(":app:sharedUI"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.playServices.wearable)

    constraints {
        // Play services pulls in Fragment 1.1, whose Activity Result handling is broken; not a new dependency.
        implementation(libs.androidx.fragment)
    }

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.github.nols1000.hashiru"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.github.nols1000.hashiru"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        buildConfigField("String", "RELAY_URL", "\"$RELEASE_RELAY_URL\"")
        buildConfigField("String", "VIEWER_URL", "\"$RELEASE_VIEWER_URL\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            // A local relay, as with `-Phashiru.relay=http://10.0.2.2:8080/api` from the emulator.
            providers.gradleProperty("hashiru.relay").orNull?.let { buildConfigField("String", "RELAY_URL", "\"$it\"") }
            providers.gradleProperty("hashiru.viewer").orNull?.let { buildConfigField("String", "VIEWER_URL", "\"$it\"") }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

releaseAndroid {
    gcpProject = providers.gradleProperty("release.gcpProject")
    playDeveloperId = providers.gradleProperty("release.playDeveloperId")
    keystoreFile = layout.file(providers.systemProperty("user.home").map { File(it, ".hashiru-secrets/upload-keystore.p12") })
}
