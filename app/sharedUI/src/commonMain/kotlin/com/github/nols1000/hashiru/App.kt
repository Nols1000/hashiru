package com.github.nols1000.hashiru

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_connect
import com.github.nols1000.hashiru.resources.action_faster
import com.github.nols1000.hashiru.resources.action_forget
import com.github.nols1000.hashiru.resources.action_pair
import com.github.nols1000.hashiru.resources.action_slower
import com.github.nols1000.hashiru.resources.action_start
import com.github.nols1000.hashiru.resources.action_stop
import com.github.nols1000.hashiru.resources.empty_body
import com.github.nols1000.hashiru.resources.empty_body_unpaired
import com.github.nols1000.hashiru.resources.empty_title
import com.github.nols1000.hashiru.resources.ic_add
import com.github.nols1000.hashiru.resources.ic_bluetooth
import com.github.nols1000.hashiru.resources.ic_bluetooth_connected
import com.github.nols1000.hashiru.resources.ic_play
import com.github.nols1000.hashiru.resources.ic_remove
import com.github.nols1000.hashiru.resources.ic_stop
import com.github.nols1000.hashiru.resources.speed_description
import com.github.nols1000.hashiru.resources.speed_unit
import com.github.nols1000.hashiru.resources.status_connected
import com.github.nols1000.hashiru.resources.status_connecting
import com.github.nols1000.hashiru.resources.status_disconnected
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.share.presentation.ShareUi
import com.github.nols1000.hashiru.share.presentation.ShareUiPhase
import com.github.nols1000.hashiru.share.presentation.ShareViewModel
import com.github.nols1000.hashiru.theme.HashiruTheme
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.treadmill.presentation.TreadmillUiState
import com.github.nols1000.hashiru.treadmill.presentation.TreadmillViewModel
import com.github.nols1000.hashiru.workout.presentation.HealthUi
import com.github.nols1000.hashiru.workout.presentation.SyncUi
import com.github.nols1000.hashiru.workout.presentation.WorkoutSummaryUi
import com.github.nols1000.hashiru.workout.presentation.WorkoutUiState
import com.github.nols1000.hashiru.workout.presentation.WorkoutViewModel
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Everything the screens can ask for. The defaults do nothing, which suits previews. */
@Immutable
class TreadmillActions(
    val connect: () -> Unit = {},
    val pair: () -> Unit = {},
    val forget: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val toggleBelt: () -> Unit = {},
    val stopBelt: () -> Unit = {},
    val increaseSpeed: () -> Unit = {},
    val decreaseSpeed: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val pauseWorkout: () -> Unit = {},
    val resumeWorkout: () -> Unit = {},
    val endWorkout: () -> Unit = {},
    val dismissSummary: () -> Unit = {},
    val deleteSummary: () -> Unit = {},
    val connectHealth: () -> Unit = {},
    val dismissHealthCard: () -> Unit = {},
    val dismissWatchTip: () -> Unit = {},
    val dismissTooShort: () -> Unit = {},
    val retrySync: () -> Unit = {},
    val setWeight: (Double?) -> Unit = {},
    val setProfile: (birthYear: Int?, sex: Sex?) -> Unit = { _, _ -> },
    /** Opens Health Connect's settings; null where it cannot be opened. */
    val openHealthSettings: (() -> Unit)? = null,
    /** Asks the system to turn Bluetooth on; null where the app cannot. */
    val enableBluetooth: (() -> Unit)? = null,
    /** Opens this app's system settings, where permissions are granted. */
    val openAppSettings: (() -> Unit)? = null,
    /** Opens the store listing of the health store; null where there is none to install. */
    val installHealthConnect: (() -> Unit)? = null,
    /** Opens the store listing of the watch app. */
    val installWatchApp: (() -> Unit)? = null,
    val startShare: (includeHeartRate: Boolean) -> Unit = {},
    val stopShare: () -> Unit = {},
    val dismissShareEnded: () -> Unit = {},
    /** Offers the share's link to other apps; null where the platform has no way to. */
    val shareLink: ((String) -> Unit)? = null,
)

/** What only the host platform can do; each is null where it cannot. */
@Immutable
class PlatformActions(
    val openHealthSettings: (() -> Unit)? = null,
    val enableBluetooth: (() -> Unit)? = null,
    val openAppSettings: (() -> Unit)? = null,
    val installHealthConnect: (() -> Unit)? = null,
    val installWatchApp: (() -> Unit)? = null,
    val shareLink: ((String) -> Unit)? = null,
)

@Composable
fun App(
    session: TreadmillSession,
    workouts: WorkoutRecorder,
    relay: WatchRelay,
    share: LiveShareEngine,
    platform: PlatformActions = PlatformActions(),
) {
    val treadmill = viewModel { TreadmillViewModel(session) }
    val workout = viewModel { WorkoutViewModel(session.connectionState, workouts, relay) }
    val sharing = viewModel { ShareViewModel(share) }
    val treadmillState by treadmill.uiState.collectAsStateWithLifecycle()
    val workoutState by workout.uiState.collectAsStateWithLifecycle()
    val shareState by sharing.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(workout) {
        workout.onForeground()
        onPauseOrDispose {}
    }
    val actions = remember(treadmill, workout, sharing, platform) {
        TreadmillActions(
            connect = treadmill::connect,
            pair = treadmill::pair,
            forget = treadmill::forget,
            disconnect = treadmill::disconnect,
            toggleBelt = treadmill::toggleBelt,
            stopBelt = treadmill::stopBelt,
            increaseSpeed = treadmill::increaseSpeed,
            decreaseSpeed = treadmill::decreaseSpeed,
            dismissError = treadmill::dismissError,
            pauseWorkout = workout::pauseWorkout,
            resumeWorkout = workout::resumeWorkout,
            endWorkout = workout::endWorkout,
            dismissSummary = workout::dismissSummary,
            deleteSummary = workout::deleteSummary,
            connectHealth = workout::connectHealth,
            dismissHealthCard = workout::dismissHealthCard,
            dismissWatchTip = workout::dismissWatchTip,
            dismissTooShort = workout::dismissTooShort,
            retrySync = workout::retrySync,
            setWeight = workout::setWeight,
            setProfile = workout::setProfile,
            openHealthSettings = platform.openHealthSettings,
            enableBluetooth = platform.enableBluetooth,
            openAppSettings = platform.openAppSettings,
            installHealthConnect = platform.installHealthConnect,
            installWatchApp = platform.installWatchApp,
            startShare = sharing::startShare,
            stopShare = sharing::stopShare,
            dismissShareEnded = sharing::dismissShareEnded,
            shareLink = platform.shareLink,
        )
    }
    HashiruTheme {
        TreadmillScreen(treadmillState, workoutState, actions, shareState)
    }
}

/**
 * Not connected, ready, or walking: a walk in progress takes the whole screen, even while the connection
 * is being restored, and the summary of a finished walk slides up over whatever is below.
 */
@Composable
fun TreadmillScreen(state: TreadmillUiState, workout: WorkoutUiState, actions: TreadmillActions, share: ShareUi = ShareUi()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    // Blocking errors take over the connect screen and a failed stop gets a banner during a walk; the rest is a snackbar.
    val snackbarError = state.error?.takeIf {
        when {
            it.isFailedStop -> !workout.isInProgress
            it is TreadmillError.CommandFailed -> true
            else -> state.isConnected || workout.isInProgress
        }
    }
    LaunchedEffect(snackbarError) {
        val error = snackbarError ?: return@LaunchedEffect
        val message = error.snackbarMessage()
        if (message == null) {
            actions.dismissError()
            return@LaunchedEffect
        }
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        snackbarHostState.showSnackbar(getString(message), withDismissAction = true)
        actions.dismissError()
    }
    var editingWeight by rememberSaveable { mutableStateOf(false) }
    var editingProfile by rememberSaveable { mutableStateOf(false) }

    if (workout.isInProgress) {
        LiveWorkoutScreen(state, workout, share, actions, snackbarHostState)
    } else {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            // Fill the viewport so weighted spacers work, but scroll when large font scales overflow it.
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
                    ConnectionStatus(state)
                    if (state.isConnected) {
                        ReadyContent(state, workout, actions, onEditWeight = { editingWeight = true }, onEditProfile = { editingProfile = true })
                    } else {
                        NotConnectedContent(state, actions)
                    }
                }
            }
        }
    }

    workout.summary?.let { summary ->
        WorkoutSummarySheet(summary, workout.health, actions, onEditWeight = { editingWeight = true })
    }
    if (editingWeight) {
        WeightDialog(
            initialKg = workout.manualWeightKg,
            onDismiss = { editingWeight = false },
            onSave = {
                actions.setWeight(it)
                editingWeight = false
            },
        )
    }
    if (editingProfile) {
        ProfileDialog(
            initialBirthYear = workout.manualBirthYear,
            initialSex = workout.manualSex,
            onDismiss = { editingProfile = false },
            onSave = { year, sex ->
                actions.setProfile(year, sex)
                editingProfile = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionStatus(state: TreadmillUiState) {
    val label = when {
        state.isConnected -> stringResource(Res.string.status_connected)
        state.isConnecting -> stringResource(Res.string.status_connecting)
        else -> stringResource(Res.string.status_disconnected)
    }
    Surface(
        shape = CircleShape,
        color = if (state.isConnected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isConnecting) {
                LoadingIndicator(Modifier.size(20.dp))
            } else {
                Icon(
                    painterResource(if (state.isConnected) Res.drawable.ic_bluetooth_connected else Res.drawable.ic_bluetooth),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.NotConnectedContent(state: TreadmillUiState, actions: TreadmillActions) {
    // Without system pairing (Android 7), connecting scans for the first WalkingPad nearby instead.
    val needsPairing = state.canPair && !state.isPaired
    val blocking = state.error?.asBlocking()?.takeUnless { state.isConnecting }
    // The fix the platform can carry out; without a way to turn Bluetooth on, trying again is the next best thing.
    val fix = when (val wanted = blocking?.fix) {
        ErrorFix.TurnOnBluetooth -> if (actions.enableBluetooth != null) wanted else ErrorFix.TryAgain
        ErrorFix.OpenSettings -> if (actions.openAppSettings != null) wanted else ErrorFix.TryAgain
        else -> wanted
    }
    Spacer(Modifier.weight(1f))
    if (blocking != null) {
        BlockingErrorHero(blocking)
    } else {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painterResource(Res.drawable.ic_bluetooth),
                contentDescription = null,
                modifier = Modifier.padding(24.dp).size(48.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(Res.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (needsPairing) Res.string.empty_body_unpaired else Res.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.weight(1f))
    val connectOrPair = if (needsPairing) actions.pair else actions.connect
    val onClick: () -> Unit = when (fix) {
        ErrorFix.TurnOnBluetooth -> { { actions.dismissError(); actions.enableBluetooth?.invoke() } }
        ErrorFix.OpenSettings -> { { actions.dismissError(); actions.openAppSettings?.invoke() } }
        ErrorFix.PairAgain -> actions.pair
        else -> connectOrPair
    }
    val label = when {
        state.isConnecting -> Res.string.status_connecting
        fix != null -> fix.label()
        needsPairing -> Res.string.action_pair
        else -> Res.string.action_connect
    }
    val height = ButtonDefaults.MediumContainerHeight
    if (label != null) {
        Button(
            onClick = onClick,
            enabled = !state.isConnecting,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.fillMaxWidth().heightIn(min = height),
        ) {
            Text(stringResource(label), style = ButtonDefaults.textStyleFor(height))
        }
    }
    if (state.isPaired) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = actions.forget, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.action_forget))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpeedControl(state: TreadmillUiState, onIncreaseSpeed: () -> Unit, onDecreaseSpeed: () -> Unit) {
    val speedTenths = (state.speedKmh * 10).roundToInt()
    val speedText = formatSpeed(speedTenths)
    val speedDescription = stringResource(Res.string.speed_description, speedText)
    val heroStyle = MaterialTheme.typography.displayMediumEmphasized.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepButton(Res.string.action_slower, onDecreaseSpeed, enabled = state.canDecreaseSpeed, increase = false)
        Column(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = speedDescription },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = speedTenths,
                transitionSpec = {
                    val faster = targetState > initialState
                    (slideInVertically { if (faster) it else -it } + fadeIn())
                        .togetherWith(slideOutVertically { if (faster) -it else it } + fadeOut())
                        .using(SizeTransform(clip = false))
                },
            ) { tenths ->
                Text(formatSpeed(tenths), style = heroStyle, maxLines = 1)
            }
            Text(
                stringResource(Res.string.speed_unit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StepButton(Res.string.action_faster, onIncreaseSpeed, enabled = state.canIncreaseSpeed, increase = true)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepButton(label: StringResource, onClick: () -> Unit, enabled: Boolean, increase: Boolean) {
    val haptic = LocalHapticFeedback.current
    FilledTonalIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
    ) {
        Icon(
            painterResource(if (increase) Res.drawable.ic_add else Res.drawable.ic_remove),
            contentDescription = stringResource(label),
            modifier = Modifier.size(IconButtonDefaults.largeIconSize),
        )
    }
}

/** A value over its label, read out as one. [valueStyle] defaults to the compact tile size. */
@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    valueStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                value,
                style = valueStyle.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BeltButton(isRunning: Boolean, onToggleBelt: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val height = ButtonDefaults.LargeContainerHeight
    Button(
        onClick = {
            haptic.performHapticFeedback(if (isRunning) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggleBelt()
        },
        shapes = ButtonDefaults.shapes(),
        colors = if (isRunning) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
        Icon(
            painterResource(if (isRunning) Res.drawable.ic_stop else Res.drawable.ic_play),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(
            stringResource(if (isRunning) Res.string.action_stop else Res.string.action_start),
            style = ButtonDefaults.textStyleFor(height),
        )
    }
}

@Composable
private fun PreviewScreen(
    state: TreadmillUiState,
    workout: WorkoutUiState = WorkoutUiState(),
    share: ShareUi = ShareUi(),
    darkTheme: Boolean = false,
) {
    HashiruTheme(darkTheme = darkTheme, dynamicColor = false) {
        TreadmillScreen(state, workout, TreadmillActions(shareLink = {}), share)
    }
}

private val walkingPreview = WorkoutUiState(
    phase = WorkoutPhase.Active,
    durationSeconds = 1_254,
    distanceMeters = 1_220,
    steps = 1_742,
    activeKcal = 43,
    paceSecondsPerKm = 1_028,
)

private val runningPreviewState = TreadmillUiState(
    isConnected = true,
    speedKmh = 3.5,
    isRunning = true,
    elapsedSeconds = 1_254,
    distanceMeters = 1_220,
    steps = 1_742,
)

private val summaryPreview = WorkoutSummaryUi(
    startEpochMs = 1_761_732_000_000,
    endEpochMs = 1_761_734_100_000,
    durationSeconds = 1_980,
    distanceMeters = 1_930,
    steps = 2_710,
    activeKcal = 68,
    totalKcal = 108,
    paceSecondsPerKm = 1_026,
    weightEstimated = true,
    sync = SyncUi.Saved,
    syncMessage = null,
)

@Preview
@Composable
fun TreadmillScreenWalkingPreview() = PreviewScreen(runningPreviewState, walkingPreview)

@Preview
@Composable
fun TreadmillScreenSharingPreview() = PreviewScreen(
    runningPreviewState,
    walkingPreview,
    ShareUi(phase = ShareUiPhase.Live, link = "https://hashiru.tw-esport.de/s/abc#k", watchers = 2, includeHeartRate = true),
)

@Preview
@Composable
fun TreadmillScreenSharingOfflinePreview() = PreviewScreen(
    runningPreviewState,
    walkingPreview,
    ShareUi(phase = ShareUiPhase.Live, link = "https://hashiru.tw-esport.de/s/abc#k", offline = true),
    darkTheme = true,
)

@Preview
@Composable
fun TreadmillScreenSharingFailedPreview() =
    PreviewScreen(runningPreviewState, walkingPreview, ShareUi(phase = ShareUiPhase.Failed))

@Preview
@Composable
fun TreadmillScreenPausedPreview() = PreviewScreen(
    runningPreviewState.copy(isRunning = false, speedKmh = 0.0),
    walkingPreview.copy(phase = WorkoutPhase.Paused, pauseReason = PauseReason.BeltStopped, pausedSeconds = 42),
)

@Preview
@Composable
fun TreadmillScreenReconnectingPreview() = PreviewScreen(
    TreadmillUiState(isConnecting = true),
    walkingPreview.copy(phase = WorkoutPhase.Paused, pauseReason = PauseReason.Disconnected, pausedSeconds = 12),
)

@Preview
@Composable
fun TreadmillScreenReadyPreview() = PreviewScreen(
    TreadmillUiState(isConnected = true, isPaired = true),
    WorkoutUiState(health = HealthUi(available = true, showConnectCard = true)),
)

@Preview
@Composable
fun TreadmillScreenReadyDarkPreview() = PreviewScreen(TreadmillUiState(isConnected = true, isPaired = true), darkTheme = true)

@Preview
@Composable
fun TreadmillScreenSummaryPreview() = PreviewScreen(
    TreadmillUiState(isConnected = true),
    WorkoutUiState(summary = summaryPreview, health = HealthUi(available = true, showPriorityTip = true)),
)

@Preview
@Composable
fun TreadmillScreenConnectingPreview() = PreviewScreen(TreadmillUiState(isConnecting = true))

@Preview
@Composable
fun TreadmillScreenDisconnectedDarkPreview() = PreviewScreen(TreadmillUiState(), darkTheme = true)

@Preview
@Composable
fun TreadmillScreenPairedPreview() = PreviewScreen(TreadmillUiState(isPaired = true))

@Preview
@Composable
fun TreadmillScreenBluetoothOffPreview() = PreviewScreen(TreadmillUiState(error = TreadmillError.BluetoothOff))

@Preview
@Composable
fun TreadmillScreenPadNotFoundPreview() = PreviewScreen(TreadmillUiState(isPaired = true, error = TreadmillError.PadNotFound(2)))

@Preview
@Composable
fun TreadmillScreenConnectionFailedDarkPreview() =
    PreviewScreen(TreadmillUiState(isPaired = true, error = TreadmillError.ConnectionFailed(133)), darkTheme = true)

@Preview
@Composable
fun TreadmillScreenNotAWalkingPadPreview() = PreviewScreen(TreadmillUiState(isPaired = true, error = TreadmillError.NotAWalkingPad))

@Preview
@Composable
fun TreadmillScreenConnectionLostPreview() = PreviewScreen(
    TreadmillUiState(isConnecting = true),
    walkingPreview.copy(phase = WorkoutPhase.Paused, pauseReason = PauseReason.Disconnected, pausedSeconds = 45),
)

@Preview
@Composable
fun TreadmillScreenStopFailedPreview() =
    PreviewScreen(runningPreviewState.copy(error = TreadmillError.CommandFailed(TreadmillCommand.StopBelt)), walkingPreview)

@Preview
@Composable
fun TreadmillScreenReadyNoticesPreview() = PreviewScreen(
    TreadmillUiState(isConnected = true, isPaired = true),
    WorkoutUiState(tooShort = true, showWatchTip = true, health = HealthUi(available = true, accessDenied = true)),
)

@Preview
@Composable
fun TreadmillScreenSyncFailedPreview() = PreviewScreen(
    TreadmillUiState(isConnected = true),
    WorkoutUiState(
        summary = summaryPreview.copy(sync = SyncUi.Failed, syncMessage = "HealthConnectException: Rate limited"),
        health = HealthUi(available = true),
    ),
)
