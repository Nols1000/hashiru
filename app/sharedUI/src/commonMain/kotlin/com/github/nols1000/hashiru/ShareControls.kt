package com.github.nols1000.hashiru

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_cancel
import com.github.nols1000.hashiru.resources.action_done
import com.github.nols1000.hashiru.resources.action_share
import com.github.nols1000.hashiru.resources.action_share_link
import com.github.nols1000.hashiru.resources.action_share_workout
import com.github.nols1000.hashiru.resources.action_stop_sharing
import com.github.nols1000.hashiru.resources.ic_check_circle
import com.github.nols1000.hashiru.resources.ic_share
import com.github.nols1000.hashiru.resources.ic_stop
import com.github.nols1000.hashiru.resources.share_ended
import com.github.nols1000.hashiru.resources.share_heart_rate_not_shared
import com.github.nols1000.hashiru.resources.share_heart_rate_shared
import com.github.nols1000.hashiru.resources.share_include_heart_rate
import com.github.nols1000.hashiru.resources.share_no_watchers
import com.github.nols1000.hashiru.resources.share_reconnecting
import com.github.nols1000.hashiru.resources.share_unavailable_body
import com.github.nols1000.hashiru.resources.share_unavailable_title
import com.github.nols1000.hashiru.resources.share_watchers
import com.github.nols1000.hashiru.share.presentation.ShareUi
import com.github.nols1000.hashiru.share.presentation.ShareUiPhase
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Live sharing during a walk: an offer to share, then who is watching and the link while live, and the link once
 * more after the share ended. The heart-rate switch starts from the choice of the last share.
 */
@Composable
internal fun ShareControls(share: ShareUi, actions: TreadmillActions) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    when (share.phase) {
        ShareUiPhase.Off, ShareUiPhase.Failed -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (share.phase == ShareUiPhase.Failed) {
                Text(stringResource(Res.string.share_unavailable_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.share_unavailable_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            ShareButton(Res.string.action_share_workout, Res.drawable.ic_share, onClick = { choosing = true }, Modifier.fillMaxWidth())
        }
        ShareUiPhase.Live -> ShareCard {
            Text(
                if (share.watchers == 0) {
                    stringResource(Res.string.share_no_watchers)
                } else {
                    pluralStringResource(Res.plurals.share_watchers, share.watchers, share.watchers)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ShareNote(if (share.includeHeartRate) Res.string.share_heart_rate_shared else Res.string.share_heart_rate_not_shared)
            if (share.offline) ShareNote(Res.string.share_reconnecting)
            Spacer(Modifier.size(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareLinkButton(share.link, actions, Modifier.weight(1f))
                ShareButton(
                    Res.string.action_stop_sharing,
                    Res.drawable.ic_stop,
                    onClick = actions.stopShare,
                    Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                )
            }
        }
        ShareUiPhase.Ended -> ShareCard {
            Text(stringResource(Res.string.share_ended), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareLinkButton(share.link, actions, Modifier.weight(1f))
                ShareButton(Res.string.action_done, Res.drawable.ic_check_circle, onClick = actions.dismissShareEnded, Modifier.weight(1f))
            }
        }
    }
    if (choosing) {
        ShareDialog(
            initialHeartRate = share.heartRateByDefault,
            onDismiss = { choosing = false },
            onShare = {
                actions.startShare(it)
                choosing = false
            },
        )
    }
}

@Composable
private fun ShareCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun ShareNote(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Hands the link to the system's share sheet. */
@Composable
private fun ShareLinkButton(link: String?, actions: TreadmillActions, modifier: Modifier) {
    val shareLink = actions.shareLink
    if (link == null || shareLink == null) return
    ShareButton(Res.string.action_share_link, Res.drawable.ic_share, onClick = { shareLink(link) }, modifier)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShareButton(
    label: StringResource,
    icon: DrawableResource,
    onClick: () -> Unit,
    modifier: Modifier,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = colors,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(label), maxLines = 1)
    }
}

/** Whether viewers see heart rate too; asked each time a share starts. */
@Composable
private fun ShareDialog(initialHeartRate: Boolean, onDismiss: () -> Unit, onShare: (includeHeartRate: Boolean) -> Unit) {
    var includeHeartRate by rememberSaveable { mutableStateOf(initialHeartRate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.action_share_workout)) },
        text = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(includeHeartRate, role = Role.Switch, onValueChange = { includeHeartRate = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.share_include_heart_rate), Modifier.weight(1f))
                Switch(checked = includeHeartRate, onCheckedChange = null)
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(includeHeartRate) }) { Text(stringResource(Res.string.action_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
