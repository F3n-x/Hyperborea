package com.nettarion.hyperborea.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.platform.update.TrackState
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun UpdateDialog(
    trackState: TrackState,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current

    when (trackState) {
        is TrackState.Available -> {
            AlertDialog(
                onDismissRequest = onLater,
                title = { Text("Update Available") },
                text = {
                    Column {
                        Text(
                            text = "Version ${trackState.info.version}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textHigh,
                        )
                        if (trackState.info.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = trackState.info.releaseNotes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMedium,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onUpdateNow) {
                        Text("Update Now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onLater) {
                        Text("Later")
                    }
                },
            )
        }
        is TrackState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Downloading Update") },
                text = {
                    Column {
                        Text(
                            text = "Downloading ${trackState.info.version}...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (trackState.progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (trackState.progress.bytesDownloaded.toFloat() / trackState.progress.totalBytes)
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = colors.divider,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = colors.divider,
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        }
        is TrackState.ReadyToInstall, is TrackState.Installing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Installing Update") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Installing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMedium,
                        )
                    }
                },
                confirmButton = {},
            )
        }
        is TrackState.Installed -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Update Installed") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Restarting...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMedium,
                        )
                    }
                },
                confirmButton = {},
            )
        }
        is TrackState.Error -> {
            AlertDialog(
                onDismissRequest = onDismissError,
                title = { Text("Update Error") },
                text = {
                    Text(
                        text = trackState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.statusError,
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss")
                    }
                },
            )
        }
        is TrackState.Idle -> {
            // No dialog
        }
    }
}
