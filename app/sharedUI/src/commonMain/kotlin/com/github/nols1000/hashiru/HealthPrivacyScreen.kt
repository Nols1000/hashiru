package com.github.nols1000.hashiru

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.nols1000.hashiru.resources.Res
import com.github.nols1000.hashiru.resources.action_close
import com.github.nols1000.hashiru.resources.privacy_body
import com.github.nols1000.hashiru.resources.privacy_title
import com.github.nols1000.hashiru.theme.HashiruTheme
import org.jetbrains.compose.resources.stringResource

/** What hashiru does with health data; Health Connect links here from its permission screen. */
@Composable
fun HealthPrivacyScreen(onClose: () -> Unit) {
    HashiruTheme {
        Scaffold { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).widthIn(max = 560.dp).padding(24.dp),
            ) {
                Text(stringResource(Res.string.privacy_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.privacy_body), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.action_close))
                }
            }
        }
    }
}
