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
           // Host tests run against a stubbed android.jar; let android.util.Log do nothing there instead of throwing.
           isReturnDefaultValues = true
       }
    }
    sourceSets {
        commonMain.dependencies {
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

// The phone–watch wire format's golden vectors, which the iOS app's tests read too. commonTest can't read
// files on every target, so the JSON becomes a Kotlin constant: maps, lists, strings, numbers and booleans.
val watchCodecVectors = tasks.register("generateWatchCodecVectors") {
    val fixture = rootProject.layout.projectDirectory.file("fixtures/watch-codec-vectors.json")
    val output = layout.buildDirectory.dir("generated/watchCodecVectors/kotlin")
    inputs.file(fixture)
    outputs.dir(output)
    doLast {
        fun literal(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
            is Map<*, *> -> value.entries.joinToString(", ", "mapOf(", ")") { "${literal(it.key)} to ${literal(it.value)}" }
            is List<*> -> value.joinToString(", ", "listOf(", ")") { literal(it) }
            is Boolean, is Int -> value.toString()
            else -> error("Unsupported value in the codec vectors: $value")
        }
        val json = groovy.json.JsonSlurper().parse(fixture.asFile)
        output.get().file("WatchCodecVectors.kt").asFile.apply { parentFile.mkdirs() }.writeText(
            "// Generated from fixtures/watch-codec-vectors.json by :app:sharedLogic:generateWatchCodecVectors.\n" +
                "package com.github.nols1000.hashiru.companion\n\n" +
                "internal val watchCodecVectors: Map<String, Any?> = ${literal(json)}\n",
        )
    }
}

kotlin.sourceSets.commonTest {
    kotlin.srcDir(watchCodecVectors)
}

// Lint reads the test sources too, but not through the source set's task wiring.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("LintModel") || it.name.startsWith("lintAnalyze") }
    .configureEach { dependsOn(watchCodecVectors) }
