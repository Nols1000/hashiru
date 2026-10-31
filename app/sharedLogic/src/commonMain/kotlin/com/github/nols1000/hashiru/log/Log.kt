package com.github.nols1000.hashiru.log

/**
 * Writes [message] to the platform's log under [tag], the name of the part that speaks: Logcat on Android, the
 * unified log on iOS, the console on the web. Unlike `println`, it still shows in a release build.
 */
internal expect fun log(tag: String, message: String)
