package com.github.nols1000.hashiru

import com.github.nols1000.hashiru.share.ShareStore
import com.github.nols1000.hashiru.share.shareRoutes
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(store: ShareStore = ShareStore()) {
    install(SSE)
    // Only the viewer (app/webApp, on its own static host) calls the API, through that host's proxy.
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Robots-Tag", "noindex, nofollow")
        call.response.headers.append("Cache-Control", "no-store")
    }
    launch {
        while (isActive) {
            delay(60_000)
            store.sweep()
        }
    }
    routing {
        get("/") {
            call.respondText("ok")
        }
        shareRoutes(store)
    }
}
