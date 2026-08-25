package com.joey.aireadingteacher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joey.aireadingteacher.service.TeacherPhase
import com.joey.aireadingteacher.service.TeacherStatus
import com.joey.aireadingteacher.settings.TutorSettings
import com.joey.aireadingteacher.tutor.VoiceSessionState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MainScreen(
    statusFlow: StateFlow<TeacherStatus>,
    settings: TutorSettings,
    apiKeyConfigured: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
) {
    val status by statusFlow.collectAsState()
    val isRunning = status.phase == TeacherPhase.STARTING || status.phase == TeacherPhase.ACTIVE

    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "AI Reading Teacher",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Read normally. Ask questions whenever needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSettings, enabled = !isRunning) { Text("Settings") }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)
                    StatusRow("Provider", settings.provider)
                    StatusRow("Model", settings.model)
                    StatusRow("API key", if (apiKeyConfigured) "Saved securely" else "Required")
                    StatusRow(
                        "Floating subtitles",
                        if (settings.floatingSubtitlesEnabled) "Enabled" else "Disabled",
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    StatusRow("Screen", status.screenLabel)
                    StatusRow("Voice", status.voiceLabel)
                    StatusRow("Teacher", status.teacherLabel)
                    status.lastCaptureEpochMs?.let {
                        StatusRow("Last stable frame", it.asTime())
                    }
                    status.lastImageSentEpochMs?.let {
                        StatusRow("Last context sent", it.asTime())
                    }
                    status.message?.let { message ->
                        Surface(
                            color = if (status.phase == TeacherPhase.ERROR) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Live AI transcript", style = MaterialTheme.typography.titleMedium)
                    if (status.assistantTranscripts.isEmpty()) {
                        Text(
                            "The teacher's spoken answers will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                status.assistantTranscripts.takeLast(5).forEach { transcript ->
                                    Text(
                                        text = transcript.text + if (transcript.isFinal) "" else " ▌",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onStart,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Start Teacher") }

            OutlinedButton(
                onClick = onStop,
                enabled = isRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Stop Teacher") }

            Text(
                text = "New screens update context silently. The teacher speaks only after you do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private val TeacherStatus.screenLabel: String
    get() = when (phase) {
        TeacherPhase.ACTIVE -> "Active"
        TeacherPhase.STARTING -> "Starting"
        TeacherPhase.ERROR, TeacherPhase.STOPPED -> "Inactive"
    }

private val TeacherStatus.voiceLabel: String
    get() = when (voiceState) {
        VoiceSessionState.DISCONNECTED -> if (phase == TeacherPhase.STARTING) "Connecting" else "Disconnected"
        VoiceSessionState.CONNECTED -> "Connected"
        VoiceSessionState.LISTENING -> "Listening"
        VoiceSessionState.SPEAKING -> "Speaking"
    }

private val TeacherStatus.teacherLabel: String
    get() = when {
        phase == TeacherPhase.ERROR -> "Error"
        phase == TeacherPhase.STOPPED -> "Stopped"
        phase == TeacherPhase.STARTING -> "Starting"
        voiceState == VoiceSessionState.SPEAKING -> "Answering"
        voiceState == VoiceSessionState.LISTENING -> "Silent / ready"
        else -> "Connecting"
    }

private fun Long.asTime(): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(this))
