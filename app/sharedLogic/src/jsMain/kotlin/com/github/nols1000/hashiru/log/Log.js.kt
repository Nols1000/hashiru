package com.github.nols1000.hashiru.log

internal actual fun log(tag: String, message: String) {
    console.log("$tag: $message")
}
