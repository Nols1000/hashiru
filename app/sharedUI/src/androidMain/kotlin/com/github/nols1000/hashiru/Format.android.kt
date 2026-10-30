package com.github.nols1000.hashiru

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun formatTimeRange(startEpochMs: Long, endEpochMs: Long): String = DateUtils.formatDateRange(
    LocalContext.current,
    startEpochMs,
    endEpochMs,
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
        DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
)
