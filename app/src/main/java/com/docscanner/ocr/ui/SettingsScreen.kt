package com.docscanner.ocr.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscanner.ocr.R
import com.docscanner.ocr.data.AppSettings
import com.docscanner.ocr.model.PreprocessMode
import com.docscanner.ocr.model.ThemeMode
import com.docscanner.ocr.ui.components.ChoiceChip

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeMode: (ThemeMode) -> Unit,
    onPreprocessMode: (PreprocessMode) -> Unit,
    onAutoStraighten: (Boolean) -> Unit,
    onShowBoxes: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsCard {
            SectionTitle(text = stringResource(R.string.settings_section_accuracy))
            Text(
                text = stringResource(R.string.settings_preprocess),
                style = MaterialTheme.typography.bodyMedium
            )
            ChipRow {
                PreprocessMode.values().forEach { option ->
                    ChoiceChip(
                        label = stringResource(option.labelRes),
                        selected = option == settings.preprocessMode,
                        onClick = { onPreprocessMode(option) }
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_straighten),
                subtitle = stringResource(R.string.settings_straighten_hint),
                checked = settings.autoStraighten,
                onCheckedChange = onAutoStraighten
            )
        }

        SettingsCard {
            SectionTitle(text = stringResource(R.string.settings_section_display))
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.bodyMedium
            )
            ChipRow {
                ThemeMode.values().forEach { option ->
                    ChoiceChip(
                        label = stringResource(option.labelRes),
                        selected = option == settings.themeMode,
                        onClick = { onThemeMode(option) }
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_boxes),
                subtitle = null,
                checked = settings.showBoxes,
                onCheckedChange = onShowBoxes
            )
        }

        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(R.string.settings_about_text, stringResource(R.string.app_version)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
