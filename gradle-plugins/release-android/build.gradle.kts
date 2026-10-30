plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(project(":release-common"))
    compileOnly(libs.android.gradlePluginApi)
    compileOnly(localGroovy())
}

gradlePlugin {
    plugins {
        register("releaseAndroid") {
            id = "io.github.nols1000.release.android"
            implementationClass = "io.github.nols1000.release.android.ReleaseAndroidPlugin"
        }
    }
}
