package com.github.nols1000.hashiru.wear

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Speed changes go out once the crown has been still this long, so the treadmill gets one command per turn. */
private const val SPEED_SETTLE_MS = 300L
private const val REACHABILITY_POLL_MS = 5_000L

/** A failed command is shown this long; the next tap tells the walker whether it works now. */
private const val COMMAND_ERROR_MS = 4_000L

private val StopRed = Color(0xFFE5483B)

/** For states that only need attention; red is kept for a stop that did not reach the pad. */
private val WarningAmber = Color(0xFFFFB547)

/**
 * Black canvas, the heart rate first: the zone colour reads at a glance mid-stride. The crown or bezel sets
 * the belt speed in 0.1 km/h steps with a tick per step and a stronger click at whole km/h; Stop is a full
 * width red button that stops the belt at once.
 */
@Composable
fun WalkScreen(app: WearApplication, permissionsGranted: Boolean, onRequestPermissions: () -> Unit) {
    val telemetry by app.telemetry.collectAsState()
    val localBpm by app.heartRateBpm.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var phoneReachable by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    var targetTenths by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        // Opened after the phone last reported: start from its stored state rather than a blank one.
        app.link.lastState()?.let { if (app.telemetry.value == WristTelemetry()) app.telemetry.value = it }
        ExerciseService.follow(context, app.telemetry.value.phase)
        while (true) {
            phoneReachable = app.link.phoneNodeId() != null
            delay(REACHABILITY_POLL_MS)
        }
    }
    LaunchedEffect(telemetry.speedTenths, telemetry.isRunning) {
        if (!telemetry.isRunning || telemetry.speedTenths == targetTenths) targetTenths = null
        // Stopped by the pad's own button or remote, the failed stop no longer matters.
        if (!telemetry.isRunning && error == R.string.stop_failed) error = null
    }
    LaunchedEffect(error) {
        if (error == R.string.command_failed) {
            delay(COMMAND_ERROR_MS)
            error = null
        }
    }
    LaunchedEffect(targetTenths) {
        val target = targetTenths ?: return@LaunchedEffect
        delay(SPEED_SETTLE_MS)
        if (!app.link.send(WristCommand.SetSpeed(target))) {
            view.haptic(HapticFeedbackConstants.REJECT)
            error = R.string.command_failed
            targetTenths = null
        }
    }

    fun send(command: WristCommand, failure: Int = R.string.command_failed) {
        error = null
        scope.launch {
            if (app.link.send(command)) {
                view.haptic(HapticFeedbackConstants.CONFIRM)
            } else {
                view.haptic(HapticFeedbackConstants.REJECT)
                error = failure
            }
        }
    }

    val controlsEnabled = phoneReachable && telemetry.isConnected
    val stepPixels = remember { ViewConfiguration.get(context).scaledVerticalScrollFactor }
    var rotaryPixels by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val listState = rememberScalingLazyListState()
    MaterialTheme {
        AppScaffold {
            ScreenScaffold(scrollState = listState) { padding ->
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = padding,
                    // The crown sets the speed instead of scrolling; the list still scrolls by touch.
                    rotaryScrollableBehavior = null,
                    modifier = Modifier
                        .onRotaryScrollEvent { event ->
                            if (!controlsEnabled || !telemetry.isRunning) return@onRotaryScrollEvent false
                            rotaryPixels += event.verticalScrollPixels
                            while (abs(rotaryPixels) >= stepPixels) {
                                val direction = if (rotaryPixels > 0) 1 else -1
                                rotaryPixels -= direction * stepPixels
                                val base = targetTenths ?: telemetry.speedTenths
                                val next = (base + direction).coerceIn(TreadmillCommand.SetSpeed.MIN_TENTHS, TreadmillCommand.SetSpeed.MAX_TENTHS)
                                if (next == base) {
                                    view.haptic(HapticFeedbackConstants.REJECT)
                                    rotaryPixels = 0f
                                    break
                                }
                                targetTenths = next
                                view.haptic(if (next % 10 == 0) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                            }
                            true
                        }
                        .focusRequester(focus)
                        .focusable(),
                ) {
                    item { HeartRate(localBpm ?: telemetry.heartRateBpm, telemetry.zone) }
                    item {
                        val speed = formatSpeed(telemetry.speedTenths)
                        val pending = targetTenths?.let { stringResource(R.string.speed_pending, formatSpeed(it)) }.orEmpty()
                        Text(
                            stringResource(R.string.speed_value, speed) + if (pending.isEmpty()) "" else " $pending",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    item {
                        Text(
                            "${formatDuration(telemetry.durationSeconds)} · ${formatKilometres(telemetry.distanceMeters)} km",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Most pressing first: without the phone nothing else can be fixed from here.
                    val status = when {
                        !permissionsGranted -> R.string.permission_needed
                        !phoneReachable -> R.string.phone_unreachable
                        !telemetry.isConnected && telemetry.phase != WorkoutPhase.Idle -> R.string.pad_reconnecting
                        !telemetry.isConnected -> R.string.pad_disconnected
                        else -> error
                    }
                    if (status != null) {
                        item {
                            Text(
                                stringResource(status),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status == R.string.stop_failed) StopRed else WarningAmber,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (!permissionsGranted) {
                        item { Button(onClick = onRequestPermissions) { Text(stringResource(R.string.action_allow)) } }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            val paused = telemetry.phase != WorkoutPhase.Active
                            val canStartOrResume = telemetry.phase == WorkoutPhase.Idle || telemetry.canResume
                            Button(
                                onClick = { send(WristCommand.SetPaused(!paused)) },
                                enabled = controlsEnabled && (!paused || canStartOrResume),
                            ) {
                                Text(
                                    stringResource(
                                        when {
                                            !paused -> R.string.action_pause
                                            telemetry.phase == WorkoutPhase.Idle -> R.string.action_start
                                            else -> R.string.action_resume
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = { send(WristCommand.StopBelt, failure = R.string.stop_failed) },
                            enabled = phoneReachable && telemetry.isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = StopRed, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_stop), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeartRate(bpm: Int?, zone: HeartRateZone?) {
    val color = zone?.let(::zoneColor) ?: Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = bpm?.toString() ?: stringResource(R.string.no_heart_rate),
            color = color,
            fontSize = 48.sp,
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            stringResource(zone?.let(::zoneName) ?: R.string.bpm),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun zoneName(zone: HeartRateZone) = when (zone) {
    HeartRateZone.Warmup -> R.string.zone_warmup
    HeartRateZone.Easy -> R.string.zone_easy
    HeartRateZone.Aerobic -> R.string.zone_aerobic
    HeartRateZone.Threshold -> R.string.zone_threshold
    HeartRateZone.Maximum -> R.string.zone_maximum
}

/** The phone app's zone palette, so both screens agree. */
private fun zoneColor(zone: HeartRateZone): Color = when (zone) {
    HeartRateZone.Warmup -> Color(0xFF8AB4F8)
    HeartRateZone.Easy -> Color(0xFF7BE0A0)
    HeartRateZone.Aerobic -> Color(0xFFD4FF3A)
    HeartRateZone.Threshold -> Color(0xFFFFB547)
    HeartRateZone.Maximum -> Color(0xFFFF6B5E)
}

private fun View.haptic(constant: Int) {
    performHapticFeedback(constant)
}

private fun formatSpeed(tenths: Int) = "${tenths / 10}.${tenths % 10}"

private fun formatKilometres(meters: Int) = "${meters / 1000}.${(meters % 1000 / 10).toString().padStart(2, '0')}"

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
