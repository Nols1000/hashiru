package com.github.nols1000.hashiru.log

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.OS_LOG_DEFAULT
import platform.darwin.OS_LOG_TYPE_DEFAULT
import platform.darwin.__dso_handle
import platform.darwin._os_log_internal

/**
 * The `os_log` macro is out of reach from Kotlin, and the `_os_log_impl` behind it wants its format string in the
 * binary's own log section. `_os_log_internal` reads the format at run time, so the message goes through it. Both
 * parts are marked public, or a release build would show them as `<private>`.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun log(tag: String, message: String) {
    memScoped {
        _os_log_internal(
            __dso_handle.ptr,
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_DEFAULT,
            "%{public}s: %{public}s",
            tag.cstr.ptr,
            message.cstr.ptr,
        )
    }
}
