package com.joey.aireadingteacher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.joey.aireadingteacher.settings.TutorSettings

@Composable
fun SettingsScreen(
    initialSettings: TutorSettings,
    apiKeyConfigured: Boolean,
    saving: Boolean,
    errorMessage: String?,
    overlayPermissionGranted: Boolean,
    onSave: (TutorSettings, String) -> Unit,
    onClearApiKey: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onBack: () -> Unit,
) {
    var model by remember(initialSettings) { mutableStateOf(initialSettings.model) }
    var baseUrl by remember(initialSettings) { mutableStateOf(initialSettings.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var globalInstructions by remember(initialSettings) {
        mutableStateOf(initialSettings.globalInstructions)
    }
    var floatingSubtitlesEnabled by remember(initialSettings) {
        mutableStateOf(initialSettings.floatingSubtitlesEnabled)
    }
    val fieldsValid = model.isNotBlank() && baseUrl.isNotBlank() &&
        globalInstructions.length <= 4_000

    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Credentials stay encrypted on this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack, enabled = !saving) { Text("Back") }
            }

            OutlinedTextField(
                value = TutorSettings.DEFAULT_PROVIDER,
                onValueChange = {},
                label = { Text("Provider") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Realtime model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Default: https://api.openai.com/v1") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("OpenAI API key") },
                placeholder = {
                    Text(if (apiKeyConfigured) "Leave blank to keep saved key" else "Required")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = {
                    Text("Encrypted with an Android Keystore key; never written to source code.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = globalInstructions,
                onValueChange = { globalInstructions = it.take(4_000) },
                label = { Text("Global answer preferences") },
                supportingText = {
                    Text("Applied after the built-in reading rules · ${globalInstructions.length}/4000")
                },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    globalInstructions = TutorSettings.DEFAULT_GLOBAL_INSTRUCTIONS
                },
                enabled = !saving,
            ) { Text("Reset answer preferences") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating subtitles", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Show draggable AI speech text over the reading app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = floatingSubtitlesEnabled,
                    onCheckedChange = { floatingSubtitlesEnabled = it },
                    enabled = !saving,
                )
            }
            if (floatingSubtitlesEnabled && !overlayPermissionGranted) {
                OutlinedButton(
                    onClick = onRequestOverlayPermission,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant floating-window permission") }
            }

            if (errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    onSave(
                        TutorSettings(
                            provider = TutorSettings.DEFAULT_PROVIDER,
                            model = model.trim(),
                            baseUrl = baseUrl.trim(),
                            globalInstructions = globalInstructions.trim(),
                            floatingSubtitlesEnabled = floatingSubtitlesEnabled,
                        ),
                        apiKey,
                    )
                },
                enabled = !saving && fieldsValid && (apiKeyConfigured || apiKey.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Saving…" else "Save") }

            OutlinedButton(
                onClick = onClearApiKey,
                enabled = !saving && apiKeyConfigured,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear saved API key") }

            Text(
                "The MVP connects directly from the tablet. Treat the tablet as a trusted device and revoke the key if it is lost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
