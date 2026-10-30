plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(project(":release-common"))
    compileOnly(localGroovy())
}

gradlePlugin {
    plugins {
        register("releaseIos") {
            id = "io.github.nols1000.release.ios"
            implementationClass = "io.github.nols1000.release.ios.ReleaseIosPlugin"
        }
    }
}
