import io.ktor.plugin.features.DockerImageRegistry

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "com.github.nols1000.hashiru"
version = "1.0.0"
application {
    mainClass = "com.github.nols1000.hashiru.ApplicationKt"
}

// `publishImage` pushes ghcr.io/nols1000/hashiru-server for the cluster's arm64 nodes, see deploy/.
ktor {
    docker {
        localImageName = "hashiru-server"
        imageTag = providers.gradleProperty("release.version").orElse("latest")
        // Runs as the image's nonroot user (65532), without a shell.
        customBaseImage = "gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d"
        externalRegistry = DockerImageRegistry.externalRegistry(
            username = providers.environmentVariable("GITHUB_ACTOR"),
            password = providers.environmentVariable("GITHUB_TOKEN"),
            project = provider { "hashiru-server" },
            hostname = provider { "ghcr.io" },
            namespace = provider { "nols1000" },
        )
    }
}

jib {
    from {
        platforms {
            platform {
                os = "linux"
                architecture = "arm64"
            }
        }
    }
}

dependencies {
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverSse)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
