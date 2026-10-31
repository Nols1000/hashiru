package com.github.nols1000.hashiru

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_end
import com.github.nols1000.hashiru.resources.action_end_description
import com.github.nols1000.hashiru.resources.action_end_walk
import com.github.nols1000.hashiru.resources.action_keep_waiting
import com.github.nols1000.hashiru.resources.action_try_again
import com.github.nols1000.hashiru.resources.action_dismiss
import com.github.nols1000.hashiru.resources.error_connection_lost_body
import com.github.nols1000.hashiru.resources.error_connection_lost_title
import com.github.nols1000.hashiru.resources.error_stop_failed_body
import com.github.nols1000.hashiru.resources.error_stop_failed_title
import com.github.nols1000.hashiru.resources.ic_link_off
import com.github.nols1000.hashiru.resources.action_pause
import com.github.nols1000.hashiru.resources.action_resume
import com.github.nols1000.hashiru.resources.distance_value
import com.github.nols1000.hashiru.resources.heart_rate_none
import com.github.nols1000.hashiru.resources.heart_rate_value
import com.github.nols1000.hashiru.resources.ic_favorite
import com.github.nols1000.hashiru.resources.ic_pause
import com.github.nols1000.hashiru.resources.ic_play
import com.github.nols1000.hashiru.resources.ic_stop
import com.github.nols1000.hashiru.resources.ic_walk
import com.github.nols1000.hashiru.resources.metric_calories
import com.github.nols1000.hashiru.resources.metric_calories_estimated
import com.github.nols1000.hashiru.resources.metric_distance
import com.github.nols1000.hashiru.resources.metric_duration
import com.github.nols1000.hashiru.resources.metric_heart_rate
import com.github.nols1000.hashiru.resources.metric_pace
import com.github.nols1000.hashiru.resources.metric_steps
import com.github.nols1000.hashiru.resources.watch_unreachable
import com.github.nols1000.hashiru.resources.workout_paused
import com.github.nols1000.hashiru.resources.workout_reconnecting
import com.github.nols1000.hashiru.resources.workout_walking
import com.github.nols1000.hashiru.resources.zone_aerobic
import com.github.nols1000.hashiru.resources.zone_easy
import com.github.nols1000.hashiru.resources.zone_maximum
import com.github.nols1000.hashiru.resources.zone_threshold
import com.github.nols1000.hashiru.resources.zone_warmup
import com.github.nols1000.hashiru.theme.LightSystemBarIcons
import com.github.nols1000.hashiru.theme.WorkoutTheme
import com.github.nols1000.hashiru.theme.zoneColor
import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.treadmill.presentation.TreadmillUiState
import com.github.nols1000.hashiru.workout.presentation.WorkoutUiState
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val HOLD_TO_END_MS = 900

/**
 * The walk in progress, readable from the treadmill: the duration as the hero, the rest in pills, and
 * large controls at the bottom. Ending takes a press and hold so a stray tap never ends a walk.
 */
@Composable
internal fun LiveWorkoutScreen(
    state: TreadmillUiState,
    workout: WorkoutUiState,
    actions: TreadmillActions,
    snackbarHostState: SnackbarHostState,
) {
    LightSystemBarIcons()
    WorkoutTheme {
        Scaffold(
            modifier = if (workout.phase == WorkoutPhase.Active) Modifier.keepScreenOn() else Modifier,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .widthIn(max = 480.dp)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WorkoutStatusChip(workout)
                    WalkBanners(state, workout, actions)
                    Spacer(Modifier.weight(1f))
                    DurationHero(workout)
                    Spacer(Modifier.height(32.dp))
                    MetricGrid(workout)
                    if (workout.heartRateBpm != null || workout.companion in setOf(CompanionState.Reachable, CompanionState.Unreachable)) {
                        Spacer(Modifier.height(12.dp))
                        HeartRateTile(workout)
                    }
                    if (state.isConnected) {
                        Spacer(Modifier.height(32.dp))
                        SpeedControl(state, actions.increaseSpeed, actions.decreaseSpeed)
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(24.dp))
                    WorkoutControls(workout, actions)
                }
            }
        }
    }
}

/**
 * A failed stop, which the walker must handle at once, or a pad that has been gone for a while. The latter can be
 * waved away; it comes back the next time the connection is lost.
 */
@Composable
private fun WalkBanners(state: TreadmillUiState, workout: WorkoutUiState, actions: TreadmillActions) {
    var lostDismissed by rememberSaveable(workout.connectionLost) { mutableStateOf(false) }
    when {
        state.error?.isFailedStop == true -> {
            Spacer(Modifier.height(16.dp))
            WalkBanner(
                icon = Res.drawable.ic_stop,
                title = stringResource(Res.string.error_stop_failed_title),
                body = stringResource(Res.string.error_stop_failed_body),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                primary = stringResource(Res.string.action_try_again),
                onPrimary = actions.stopBelt,
                secondary = stringResource(Res.string.action_dismiss),
                onSecondary = actions.dismissError,
            )
        }
        workout.connectionLost && !lostDismissed -> {
            Spacer(Modifier.height(16.dp))
            WalkBanner(
                icon = Res.drawable.ic_link_off,
                title = stringResource(Res.string.error_connection_lost_title),
                body = stringResource(Res.string.error_connection_lost_body),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                primary = stringResource(Res.string.action_end_walk),
                onPrimary = actions.endWorkout,
                secondary = stringResource(Res.string.action_keep_waiting),
                onSecondary = { lostDismissed = true },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkoutStatusChip(workout: WorkoutUiState) {
    val active = workout.phase == WorkoutPhase.Active
    val reconnecting = workout.pauseReason == PauseReason.Disconnected
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                reconnecting -> LoadingIndicator(Modifier.size(20.dp))
                else -> Icon(
                    painterResource(if (active) Res.drawable.ic_walk else Res.drawable.ic_pause),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                when {
                    active -> stringResource(Res.string.workout_walking)
                    reconnecting -> stringResource(Res.string.workout_reconnecting)
                    else -> stringResource(Res.string.workout_paused, formatDuration(workout.pausedSeconds))
                },
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DurationHero(workout: WorkoutUiState) {
    val label = stringResource(Res.string.metric_duration)
    val duration = formatDuration(workout.durationSeconds)
    // Paused, the time pulses like a stopped stopwatch.
    val alpha = if (workout.phase == WorkoutPhase.Paused) {
        val pulse = rememberInfiniteTransition()
        pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse)).value
    } else {
        1f
    }
    Column(
        Modifier.clearAndSetSemantics { contentDescription = "$label: $duration" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            duration,
            style = MaterialTheme.typography.displayLargeEmphasized.copy(
                fontSize = 88.sp,
                lineHeight = 96.sp,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        )
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricGrid(workout: WorkoutUiState) {
    val shape = RoundedCornerShape(28.dp)
    val style = MaterialTheme.typography.headlineMedium
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                stringResource(Res.string.metric_distance),
                stringResource(Res.string.distance_value, formatKilometres(workout.distanceMeters)),
                Modifier.weight(1f), shape, style,
            )
            MetricTile(stringResource(Res.string.metric_steps), workout.steps.toString(), Modifier.weight(1f), shape, style)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                stringResource(if (workout.weightEstimated) Res.string.metric_calories_estimated else Res.string.metric_calories),
                workout.activeKcal.toString(),
                Modifier.weight(1f), shape, style,
            )
            MetricTile(stringResource(Res.string.metric_pace), formatPace(workout.paceSecondsPerKm), Modifier.weight(1f), shape, style)
        }
    }
}

/** Heart rate from the watch with its zone; "--" while no recent reading, and a note when the watch is out of reach. */
@Composable
private fun HeartRateTile(workout: WorkoutUiState) {
    val bpm = workout.heartRateBpm
    val zone = workout.heartRateZone
    val value = if (bpm != null) stringResource(Res.string.heart_rate_value, bpm.toString()) else stringResource(Res.string.heart_rate_none)
    val label = when {
        bpm == null && workout.companion == CompanionState.Unreachable -> stringResource(Res.string.watch_unreachable)
        zone != null -> stringResource(zoneName(zone))
        else -> stringResource(Res.string.metric_heart_rate)
    }
    val accent = zone?.let(::zoneColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "$value, $label"
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(Res.drawable.ic_favorite), contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            Column {
                Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1)
            }
        }
    }
}

internal fun zoneName(zone: HeartRateZone) = when (zone) {
    HeartRateZone.Warmup -> Res.string.zone_warmup
    HeartRateZone.Easy -> Res.string.zone_easy
    HeartRateZone.Aerobic -> Res.string.zone_aerobic
    HeartRateZone.Threshold -> Res.string.zone_threshold
    HeartRateZone.Maximum -> Res.string.zone_maximum
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkoutControls(workout: WorkoutUiState, actions: TreadmillActions) {
    val haptic = LocalHapticFeedback.current
    val height = ButtonDefaults.MediumContainerHeight
    val active = workout.phase == WorkoutPhase.Active
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                haptic.performHapticFeedback(if (active) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                if (active) actions.pauseWorkout() else actions.resumeWorkout()
            },
            // The pad has to be reachable to resume.
            enabled = active || workout.pauseReason != PauseReason.Disconnected,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.LargeContainerHeight),
        ) {
            Icon(
                painterResource(if (active) Res.drawable.ic_pause else Res.drawable.ic_play),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
            )
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
            Text(
                stringResource(if (active) Res.string.action_pause else Res.string.action_resume),
                style = ButtonDefaults.textStyleFor(height),
                maxLines = 1,
            )
        }
        HoldToEndButton(actions.endWorkout, Modifier.weight(1f).heightIn(min = ButtonDefaults.LargeContainerHeight))
    }
}

/** Fills while held and ends the walk once full; screen readers get a plain button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HoldToEndButton(onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val currentOnEnd by rememberUpdatedState(onEnd)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val description = stringResource(Res.string.action_end_description)
    val fill = MaterialTheme.colorScheme.error
    val height = ButtonDefaults.MediumContainerHeight
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(label = description) {
                    currentOnEnd()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val hold = scope.launch {
                            // The fill is only a hint; the hold is timed on the clock, since with animations
                            // turned off in the system settings the fill completes at once.
                            launch { progress.animateTo(1f, tween(HOLD_TO_END_MS, easing = LinearEasing)) }
                            delay(HOLD_TO_END_MS.toLong())
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            currentOnEnd()
                        }
                        tryAwaitRelease()
                        if (!hold.isCompleted) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f) }
                        }
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind { drawRect(fill.copy(alpha = 0.6f), size = Size(size.width * progress.value, size.height)) },
            )
            Row(
                Modifier.padding(ButtonDefaults.contentPaddingFor(height)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_stop),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
                )
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
                Text(stringResource(Res.string.action_end), style = ButtonDefaults.textStyleFor(height), maxLines = 1)
            }
        }
    }
}
