package com.github.nols1000.hashiru

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_dismiss
import com.github.nols1000.hashiru.resources.action_open_settings
import com.github.nols1000.hashiru.resources.action_pair_again
import com.github.nols1000.hashiru.resources.action_try_again
import com.github.nols1000.hashiru.resources.action_turn_on_bluetooth
import com.github.nols1000.hashiru.resources.error_bluetooth_denied_body
import com.github.nols1000.hashiru.resources.error_bluetooth_denied_title
import com.github.nols1000.hashiru.resources.error_bluetooth_off_body
import com.github.nols1000.hashiru.resources.error_bluetooth_off_title
import com.github.nols1000.hashiru.resources.error_bluetooth_unsupported_body
import com.github.nols1000.hashiru.resources.error_bluetooth_unsupported_title
import com.github.nols1000.hashiru.resources.error_code
import com.github.nols1000.hashiru.resources.error_connection_failed_body
import com.github.nols1000.hashiru.resources.error_connection_failed_title
import com.github.nols1000.hashiru.resources.error_not_a_walkingpad_body
import com.github.nols1000.hashiru.resources.error_not_a_walkingpad_title
import com.github.nols1000.hashiru.resources.error_pad_not_found_body
import com.github.nols1000.hashiru.resources.error_pad_not_found_title
import com.github.nols1000.hashiru.resources.error_pair_again_body
import com.github.nols1000.hashiru.resources.error_pair_again_title
import com.github.nols1000.hashiru.resources.error_speed_failed
import com.github.nols1000.hashiru.resources.error_start_failed
import com.github.nols1000.hashiru.resources.error_stop_failed_title
import com.github.nols1000.hashiru.resources.ic_bluetooth_disabled
import com.github.nols1000.hashiru.resources.ic_close
import com.github.nols1000.hashiru.resources.ic_link
import com.github.nols1000.hashiru.resources.ic_link_off
import com.github.nols1000.hashiru.resources.ic_search
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * Errors come in three weights. Blocking ones (the pad can't be reached) replace the connect screen's hero and turn
 * its main button into the fix. Interruptions during a walk get a banner on the live screen, or a snackbar when
 * nothing is at stake. Degraded features (health, watch) get a quiet [NoticeRow] on the screen that owns them.
 */

/** What fixes a blocking error; the connect screen's main button does it. */
internal enum class ErrorFix { TurnOnBluetooth, OpenSettings, TryAgain, PairAgain, None }

internal class BlockingError(
    val icon: DrawableResource,
    val title: StringResource,
    val body: StringResource,
    val fix: ErrorFix,
    /** The platform's own code, for support; never the headline. */
    val code: Int? = null,
)

/** Everything but a failed command stops the app from connecting, so it takes over the connect screen. */
internal fun TreadmillError.asBlocking(): BlockingError? = when (this) {
    TreadmillError.BluetoothOff -> BlockingError(
        Res.drawable.ic_bluetooth_disabled, Res.string.error_bluetooth_off_title, Res.string.error_bluetooth_off_body, ErrorFix.TurnOnBluetooth,
    )
    TreadmillError.BluetoothUnauthorized -> BlockingError(
        Res.drawable.ic_bluetooth_disabled, Res.string.error_bluetooth_denied_title, Res.string.error_bluetooth_denied_body, ErrorFix.OpenSettings,
    )
    TreadmillError.BluetoothUnsupported -> BlockingError(
        Res.drawable.ic_bluetooth_disabled, Res.string.error_bluetooth_unsupported_title, Res.string.error_bluetooth_unsupported_body, ErrorFix.None,
    )
    is TreadmillError.PadNotFound -> BlockingError(
        Res.drawable.ic_search, Res.string.error_pad_not_found_title, Res.string.error_pad_not_found_body, ErrorFix.TryAgain, code,
    )
    is TreadmillError.ConnectionFailed -> BlockingError(
        Res.drawable.ic_link_off, Res.string.error_connection_failed_title, Res.string.error_connection_failed_body, ErrorFix.TryAgain, code,
    )
    TreadmillError.NotAWalkingPad -> BlockingError(
        Res.drawable.ic_link, Res.string.error_not_a_walkingpad_title, Res.string.error_not_a_walkingpad_body, ErrorFix.PairAgain,
    )
    TreadmillError.PairAgain -> BlockingError(
        Res.drawable.ic_link, Res.string.error_pair_again_title, Res.string.error_pair_again_body, ErrorFix.PairAgain,
    )
    is TreadmillError.CommandFailed -> null
}

/** A failed stop is critical and gets a banner; other failed commands only need a snackbar. */
internal val TreadmillError.isFailedStop: Boolean
    get() = this == TreadmillError.CommandFailed(TreadmillCommand.StopBelt)

/** The snackbar text for an error that interrupts nothing; null for errors shown elsewhere. */
internal fun TreadmillError.snackbarMessage(): StringResource? = when (this) {
    is TreadmillError.CommandFailed -> when (command) {
        TreadmillCommand.StartBelt -> Res.string.error_start_failed
        TreadmillCommand.StopBelt -> Res.string.error_stop_failed_title
        is TreadmillCommand.SetSpeed -> Res.string.error_speed_failed
        TreadmillCommand.RequestStatusPing -> null
    }
    // Only shown as a snackbar while connected, where the connect screen is not on screen.
    else -> asBlocking()?.title
}

internal fun ErrorFix.label(): StringResource? = when (this) {
    ErrorFix.TurnOnBluetooth -> Res.string.action_turn_on_bluetooth
    ErrorFix.OpenSettings -> Res.string.action_open_settings
    ErrorFix.TryAgain -> Res.string.action_try_again
    ErrorFix.PairAgain -> Res.string.action_pair_again
    ErrorFix.None -> null
}

/** The connect screen's hero while a blocking error stands: icon in the error container, title, body and code. */
@Composable
internal fun BlockingErrorHero(error: BlockingError) {
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer) {
            Icon(painterResource(error.icon), contentDescription = null, modifier = Modifier.padding(24.dp).size(48.dp))
        }
        Text(
            stringResource(error.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            stringResource(error.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        error.code?.let {
            Text(
                stringResource(Res.string.error_code, it.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A feature that is off or failed, on the screen that owns it: icon, title, a line of body, and what to do about it. */
@Composable
internal fun NoticeRow(
    icon: DrawableResource,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    critical: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val container = if (critical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (critical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val tint = if (critical) content else MaterialTheme.colorScheme.primary
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                body?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = if (critical) content else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (action != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = if (critical) ButtonDefaults.textButtonColors(contentColor = content) else ButtonDefaults.textButtonColors(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(action) }
            }
            onDismiss?.let {
                IconButton(onClick = it) {
                    Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.action_dismiss))
                }
            }
        }
    }
}

/** A banner at the top of the live walk for something the walker has to deal with now. */
@Composable
internal fun WalkBanner(
    icon: DrawableResource,
    title: String,
    body: String,
    container: Color,
    content: Color,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 36.dp, top = 4.dp, end = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (secondary != null && onSecondary != null) {
                    TextButton(
                        onClick = onSecondary,
                        colors = ButtonDefaults.textButtonColors(contentColor = content),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(secondary) }
                }
                Button(
                    onClick = onPrimary,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = container),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(primary) }
            }
        }
    }
}
