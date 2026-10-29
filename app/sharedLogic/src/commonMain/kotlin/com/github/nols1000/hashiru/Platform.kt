package com.github.nols1000.hashiru

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform