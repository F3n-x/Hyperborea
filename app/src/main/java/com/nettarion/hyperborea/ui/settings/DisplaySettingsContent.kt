package com.nettarion.hyperborea.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/**
 * "Display" tab — global units preference plus the screen-presentation toggles
 * (system overlay, immersive mode). Reachable from the dashboard via the gear
 * → AdminDrawer → "Open Settings" path, for guests and named profiles alike.
 */
@Composable
fun DisplaySettingsContent(
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current
    val useImperial by viewModel.useImperial.collectAsStateWithLifecycle()
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateWithLifecycle()
    val immersiveModeEnabled by viewModel.immersiveModeEnabled.collectAsStateWithLifecycle()

    Text(
        text = "Display",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(16.dp))

    // Units
    SettingsRow(
        title = "Units",
        subtitle = if (useImperial) "Imperial — mph, mi, lbs, ft" else "Metric — km/h, km, kg, cm",
    ) {
        UnitsToggle(
            useImperial = useImperial,
            onSelect = viewModel::setUseImperial,
        )
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))

    // System overlay
    SettingsRow(
        title = "System Overlay",
        subtitle = "Show exercise data over other apps",
    ) {
        ThemedSwitch(
            checked = overlayEnabled,
            onCheckedChange = viewModel::setOverlayEnabled,
        )
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))

    // Immersive mode
    SettingsRow(
        title = "Immersive Mode",
        subtitle = "Hide navigation and status bars",
    ) {
        ThemedSwitch(
            checked = immersiveModeEnabled,
            onCheckedChange = viewModel::setImmersiveModeEnabled,
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textHigh,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMedium,
            )
        }
        trailing()
    }
}

@Composable
private fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        ),
    )
}

@Composable
private fun UnitsToggle(
    useImperial: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !useImperial,
            onClick = { if (useImperial) onSelect(false) },
            label = { Text("Metric") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                selectedLabelColor = colors.electricBlue,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = colors.textLow,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = !useImperial,
                borderColor = colors.divider,
                selectedBorderColor = colors.electricBlue,
            ),
        )
        FilterChip(
            selected = useImperial,
            onClick = { if (!useImperial) onSelect(true) },
            label = { Text("Imperial") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                selectedLabelColor = colors.electricBlue,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = colors.textLow,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = useImperial,
                borderColor = colors.divider,
                selectedBorderColor = colors.electricBlue,
            ),
        )
    }
}
