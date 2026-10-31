package com.github.nols1000.hashiru.log

import android.util.Log

internal actual fun log(tag: String, message: String) {
    Log.i(tag, message)
}
