import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
        iosTarget.compilations.getByName("main") {
            cinterops.create("requestSetters") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/requestSetters.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
        }
    }
    // The web viewer's logic, as an npm library with TypeScript definitions for app/webApp. Tests run on
    // Node, which has WebCrypto, rather than in a headless browser.
    js {
        outputModuleName = "sharedLogic"
        browser {
            testTask { enabled = false }
        }
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions {
            target = "es2015"
        }
    }
    compilerOptions {
        optIn.add("kotlin.js.ExperimentalJsExport")
    }
    android {
       namespace = "com.github.nols1000.hashiru.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
        }
        androidMain.dependencies {
            // Google's own client; it brings Guava along. It needs API 26, the adapter only uses it on API 28+.
            implementation(libs.androidx.healthConnect)
            // The Wear OS Data Layer; Google's, and the only way to reach the watch app.
            implementation(libs.playServices.wearable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}