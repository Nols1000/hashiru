package com.github.nols1000.hashiru.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.github.nols1000.hashiru.workout.domain.HeartRateZone

/** Electric lime on near-black: readable at a glance from the treadmill, whatever the wallpaper or system theme. */
internal val WorkoutLime = Color(0xFFD4FF3A)

private val workoutColors = darkColorScheme(
    primary = WorkoutLime,
    onPrimary = Color(0xFF1B2400),
    primaryContainer = Color(0xFF2E3B00),
    onPrimaryContainer = WorkoutLime,
    secondary = Color(0xFFC5CBB0),
    onSecondary = Color(0xFF2E3320),
    secondaryContainer = Color(0xFF2B2E24),
    onSecondaryContainer = Color(0xFFE2E8CB),
    background = Color(0xFF050505),
    onBackground = Color(0xFFF2F2EE),
    surface = Color(0xFF050505),
    onSurface = Color(0xFFF2F2EE),
    onSurfaceVariant = Color(0xFFB8BBAE),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF111210),
    surfaceContainer = Color(0xFF171816),
    surfaceContainerHigh = Color(0xFF21221F),
    surfaceContainerHighest = Color(0xFF2B2C29),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    // Amber for warnings that are not failures, such as a pad that has gone quiet; nothing else uses tertiary here.
    tertiaryContainer = Color(0xFF4A3500),
    onTertiaryContainer = Color(0xFFFFDEA6),
)

/** Cool to hot, on the workout's near-black; the aerobic zone, where a brisk walk lands, gets the accent. */
internal fun zoneColor(zone: HeartRateZone): Color = when (zone) {
    HeartRateZone.Warmup -> Color(0xFF8AB4F8)
    HeartRateZone.Easy -> Color(0xFF7BE0A0)
    HeartRateZone.Aerobic -> WorkoutLime
    HeartRateZone.Threshold -> Color(0xFFFFB547)
    HeartRateZone.Maximum -> Color(0xFFFF6B5E)
}

/** The live workout's theme: always dark with a lime accent, like a sports watch face. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkoutTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = workoutColors, motionScheme = MotionScheme.expressive(), content = content)
}

/** Switches the status and navigation bar icons to light while in composition, for the always-dark workout screen. */
@Composable
internal expect fun LightSystemBarIcons()
