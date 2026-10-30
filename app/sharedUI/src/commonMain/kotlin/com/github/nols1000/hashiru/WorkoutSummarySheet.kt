package com.github.nols1000.hashiru

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_cancel
import com.github.nols1000.hashiru.resources.action_delete
import com.github.nols1000.hashiru.resources.action_delete_walk
import com.github.nols1000.hashiru.resources.action_done
import com.github.nols1000.hashiru.resources.action_try_again
import com.github.nols1000.hashiru.resources.delete_body
import com.github.nols1000.hashiru.resources.delete_title
import com.github.nols1000.hashiru.resources.distance_value
import com.github.nols1000.hashiru.resources.health_connect
import com.github.nols1000.hashiru.resources.health_not_now
import com.github.nols1000.hashiru.resources.health_open_settings
import com.github.nols1000.hashiru.resources.ic_check_circle
import com.github.nols1000.hashiru.resources.ic_error
import com.github.nols1000.hashiru.resources.ic_favorite
import com.github.nols1000.hashiru.resources.ic_phone_device
import com.github.nols1000.hashiru.resources.ic_schedule
import com.github.nols1000.hashiru.resources.metric_calories
import com.github.nols1000.hashiru.resources.metric_distance
import com.github.nols1000.hashiru.resources.metric_duration
import com.github.nols1000.hashiru.resources.metric_pace
import com.github.nols1000.hashiru.resources.metric_steps
import com.github.nols1000.hashiru.resources.priority_tip_body
import com.github.nols1000.hashiru.resources.priority_tip_title
import com.github.nols1000.hashiru.resources.summary_estimated
import com.github.nols1000.hashiru.resources.summary_heart_rate
import com.github.nols1000.hashiru.resources.summary_heart_rate_energy
import com.github.nols1000.hashiru.resources.summary_set_weight
import com.github.nols1000.hashiru.resources.summary_title
import com.github.nols1000.hashiru.resources.summary_total_kcal
import com.github.nols1000.hashiru.resources.sync_busy
import com.github.nols1000.hashiru.resources.sync_failed
import com.github.nols1000.hashiru.resources.sync_failed_details
import com.github.nols1000.hashiru.resources.sync_not_saved
import com.github.nols1000.hashiru.resources.sync_saved
import com.github.nols1000.hashiru.resources.sync_saving
import com.github.nols1000.hashiru.resources.sync_unavailable
import com.github.nols1000.hashiru.resources.sync_waiting
import com.github.nols1000.hashiru.treadmill.presentation.HealthUi
import com.github.nols1000.hashiru.treadmill.presentation.SyncUi
import com.github.nols1000.hashiru.treadmill.presentation.WorkoutSummaryUi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The finished walk, whether it reached Health Connect, and what to do if it did not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutSummarySheet(summary: WorkoutSummaryUi, health: HealthUi, actions: TreadmillActions, onEditWeight: () -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(onDismissRequest = actions.dismissSummary, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            Text(stringResource(Res.string.summary_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                formatTimeRange(summary.startEpochMs, summary.endEpochMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            MetricTile(
                stringResource(Res.string.metric_duration),
                formatDuration(summary.durationSeconds),
                Modifier.fillMaxWidth(),
                valueStyle = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    stringResource(Res.string.metric_distance),
                    stringResource(Res.string.distance_value, formatKilometres(summary.distanceMeters)),
                    Modifier.weight(1f),
                )
                MetricTile(stringResource(Res.string.metric_steps), summary.steps.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(stringResource(Res.string.metric_calories), summary.activeKcal.toString(), Modifier.weight(1f))
                MetricTile(stringResource(Res.string.metric_pace), formatPace(summary.paceSecondsPerKm), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.summary_total_kcal, summary.totalKcal.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.avgHeartRate != null && summary.maxHeartRate != null) {
                Text(
                    stringResource(Res.string.summary_heart_rate, summary.avgHeartRate.toString(), summary.maxHeartRate.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.energyFromHeartRate) {
                Text(
                    stringResource(Res.string.summary_heart_rate_energy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.weightEstimated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.summary_estimated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onEditWeight, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.summary_set_weight))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SyncStatus(summary, actions)
            if (health.showPriorityTip) {
                Spacer(Modifier.height(16.dp))
                PriorityTip(actions.openHealthSettings)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { close(actions.dismissSummary) },
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                Text(stringResource(Res.string.action_done))
            }
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.action_delete_walk))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.delete_title)) },
            text = { Text(stringResource(Res.string.delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        close(actions.deleteSummary)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncStatus(summary: WorkoutSummaryUi, actions: TreadmillActions) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (summary.sync) {
            SyncUi.Saved -> StatusLine(Res.drawable.ic_check_circle, stringResource(Res.string.sync_saved), MaterialTheme.colorScheme.primary)
            SyncUi.Saving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(24.dp))
                Text(stringResource(Res.string.sync_saving), style = MaterialTheme.typography.bodyMedium)
            }
            SyncUi.WaitingForAccess -> {
                StatusLine(Res.drawable.ic_favorite, stringResource(Res.string.sync_waiting), MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = actions.dismissHealthCard, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.health_not_now))
                    }
                    Button(onClick = actions.connectHealth, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.health_connect))
                    }
                }
            }
            SyncUi.Busy -> StatusLine(Res.drawable.ic_schedule, stringResource(Res.string.sync_busy))
            SyncUi.Failed -> NoticeRow(
                icon = Res.drawable.ic_error,
                title = stringResource(Res.string.sync_failed),
                // The store's own message is for support, not the headline.
                body = summary.syncMessage?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.sync_failed_details, it) },
                critical = true,
                action = stringResource(Res.string.action_try_again),
                onAction = actions.retrySync,
            )
            SyncUi.NotSaved -> StatusLine(Res.drawable.ic_phone_device, stringResource(Res.string.sync_not_saved))
            SyncUi.Unavailable -> StatusLine(Res.drawable.ic_phone_device, stringResource(Res.string.sync_unavailable))
        }
    }
}

@Composable
private fun StatusLine(icon: DrawableResource, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PriorityTip(onOpenHealthSettings: (() -> Unit)?) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.priority_tip_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(Res.string.priority_tip_body), style = MaterialTheme.typography.bodyMedium)
            onOpenHealthSettings?.let {
                TextButton(onClick = it, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.health_open_settings))
                }
            }
        }
    }
}
