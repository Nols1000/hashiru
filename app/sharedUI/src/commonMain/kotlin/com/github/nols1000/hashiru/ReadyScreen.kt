package com.github.nols1000.hashiru

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_cancel
import com.github.nols1000.hashiru.resources.action_install
import com.github.nols1000.hashiru.resources.birth_year_range
import com.github.nols1000.hashiru.resources.action_clear
import com.github.nols1000.hashiru.resources.action_disconnect
import com.github.nols1000.hashiru.resources.action_save
import com.github.nols1000.hashiru.resources.health_access_off_body
import com.github.nols1000.hashiru.resources.health_access_off_title
import com.github.nols1000.hashiru.resources.health_card_body
import com.github.nols1000.hashiru.resources.health_card_title
import com.github.nols1000.hashiru.resources.health_connect
import com.github.nols1000.hashiru.resources.health_install_body
import com.github.nols1000.hashiru.resources.health_install_title
import com.github.nols1000.hashiru.resources.health_not_now
import com.github.nols1000.hashiru.resources.health_open_settings
import com.github.nols1000.hashiru.resources.ic_download
import com.github.nols1000.hashiru.resources.ic_favorite
import com.github.nols1000.hashiru.resources.ic_schedule
import com.github.nols1000.hashiru.resources.ic_walk
import com.github.nols1000.hashiru.resources.ic_watch
import com.github.nols1000.hashiru.resources.profile_birth_year
import com.github.nols1000.hashiru.resources.profile_body
import com.github.nols1000.hashiru.resources.profile_row
import com.github.nols1000.hashiru.resources.profile_title
import com.github.nols1000.hashiru.resources.profile_value
import com.github.nols1000.hashiru.resources.ready_body
import com.github.nols1000.hashiru.resources.ready_title
import com.github.nols1000.hashiru.resources.sex_female
import com.github.nols1000.hashiru.resources.sex_male
import com.github.nols1000.hashiru.resources.too_short_body
import com.github.nols1000.hashiru.resources.too_short_title
import com.github.nols1000.hashiru.resources.watch_tip_body
import com.github.nols1000.hashiru.resources.watch_tip_title
import com.github.nols1000.hashiru.resources.weight_body
import com.github.nols1000.hashiru.resources.weight_label
import com.github.nols1000.hashiru.resources.weight_not_set
import com.github.nols1000.hashiru.resources.weight_range
import com.github.nols1000.hashiru.resources.weight_row
import com.github.nols1000.hashiru.resources.weight_title
import com.github.nols1000.hashiru.resources.weight_value
import com.github.nols1000.hashiru.treadmill.presentation.TreadmillUiState
import com.github.nols1000.hashiru.workout.presentation.WorkoutUiState
import com.github.nols1000.hashiru.workout.domain.Sex
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Connected with the belt idle: start a walk, and offer health sync the first time. */
@Composable
internal fun ColumnScope.ReadyContent(
    state: TreadmillUiState,
    workout: WorkoutUiState,
    actions: TreadmillActions,
    onEditWeight: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val health = workout.health
    Spacer(Modifier.weight(1f))
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(painterResource(Res.drawable.ic_walk), contentDescription = null, modifier = Modifier.padding(24.dp).size(48.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(Res.string.ready_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.ready_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (workout.tooShort) {
        Spacer(Modifier.height(24.dp))
        NoticeRow(
            icon = Res.drawable.ic_schedule,
            title = stringResource(Res.string.too_short_title),
            body = stringResource(Res.string.too_short_body, workout.minSeconds, workout.minMeters),
            onDismiss = actions.dismissTooShort,
        )
    }
    if (health.showConnectCard) {
        Spacer(Modifier.height(24.dp))
        HealthConnectCard(onConnect = actions.connectHealth, onNotNow = actions.dismissHealthCard)
    } else if (health.showInstallCard && actions.installHealthConnect != null) {
        Spacer(Modifier.height(24.dp))
        NoticeRow(
            icon = Res.drawable.ic_download,
            title = stringResource(Res.string.health_install_title),
            body = stringResource(Res.string.health_install_body),
            action = stringResource(Res.string.action_install),
            onAction = actions.installHealthConnect,
            onDismiss = actions.dismissHealthCard,
        )
    } else if (health.available && health.accessDenied) {
        Spacer(Modifier.height(24.dp))
        NoticeRow(
            icon = Res.drawable.ic_favorite,
            title = stringResource(Res.string.health_access_off_title),
            body = stringResource(Res.string.health_access_off_body),
            action = actions.openHealthSettings?.let { stringResource(Res.string.health_open_settings) },
            onAction = actions.openHealthSettings,
        )
    }
    if (workout.showWatchTip && actions.installWatchApp != null) {
        Spacer(Modifier.height(12.dp))
        NoticeRow(
            icon = Res.drawable.ic_watch,
            title = stringResource(Res.string.watch_tip_title),
            body = stringResource(Res.string.watch_tip_body),
            action = stringResource(Res.string.action_install),
            onAction = actions.installWatchApp,
            onDismiss = actions.dismissWatchTip,
        )
    }
    Spacer(Modifier.weight(1f))
    val weight = workout.manualWeightKg?.let { stringResource(Res.string.weight_value, formatWeight(it)) }
        ?: stringResource(Res.string.weight_not_set)
    TextButton(onClick = onEditWeight, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(Res.string.weight_row, weight))
    }
    // Only asked for what the health store does not provide.
    if (!workout.ageFromHealth || !workout.sexFromHealth) {
        val year = workout.manualBirthYear
        val sex = workout.manualSex
        val profile = if (year == null && sex == null) {
            stringResource(Res.string.weight_not_set)
        } else {
            stringResource(Res.string.profile_value, year?.toString() ?: "–", sex?.let { stringResource(sexName(it)) } ?: "–")
        }
        TextButton(onClick = onEditProfile, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.profile_row, profile))
        }
    }
    Spacer(Modifier.height(8.dp))
    BeltButton(isRunning = state.isRunning, onToggleBelt = actions.toggleBelt)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = actions.disconnect, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(Res.string.action_disconnect))
    }
}

private fun sexName(sex: Sex): StringResource = when (sex) {
    Sex.Female -> Res.string.sex_female
    Sex.Male -> Res.string.sex_male
}

/** Birth year and sex for heart-rate calories and zones; either may be left empty. */
@Composable
internal fun ProfileDialog(initialBirthYear: Int?, initialSex: Sex?, onDismiss: () -> Unit, onSave: (Int?, Sex?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialBirthYear?.toString().orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(initialSex) }
    val year = text.trim().toIntOrNull()?.takeIf { it in 1900..2100 }
    val valid = text.isBlank() || year != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.profile_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(Res.string.profile_birth_year)) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = if (valid) null else { { Text(stringResource(Res.string.birth_year_range)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in Sex.entries) {
                        FilterChip(
                            selected = sex == option,
                            onClick = { sex = if (sex == option) null else option },
                            label = { Text(stringResource(sexName(option))) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(year, sex) }, enabled = valid) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HealthConnectCard(onConnect: () -> Unit, onNotNow: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(Res.drawable.ic_favorite), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.health_card_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(Res.string.health_card_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.health_not_now)) }
                Button(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.health_connect)) }
            }
        }
    }
}

/** Edits the fallback weight for calories; [onSave] gets null when the user clears it. */
@Composable
internal fun WeightDialog(initialKg: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialKg?.let(::formatWeight).orEmpty()) }
    val parsed = parseWeight(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.weight_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.weight_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.weight_label)) },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = if (text.isNotBlank() && parsed == null) { { Text(stringResource(Res.string.weight_range)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parsed) }, enabled = parsed != null) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (initialKg != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(Res.string.action_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            }
        },
    )
}
