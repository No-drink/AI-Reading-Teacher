package com.joey.aireadingteacher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.joey.aireadingteacher.service.TeacherRuntime
import com.joey.aireadingteacher.service.TeacherService
import com.joey.aireadingteacher.settings.SecureApiKeyStore
import com.joey.aireadingteacher.settings.SettingsRepository
import com.joey.aireadingteacher.settings.TutorSettings
import com.joey.aireadingteacher.ui.AIReadingTeacherTheme
import com.joey.aireadingteacher.ui.MainScreen
import com.joey.aireadingteacher.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AIReadingTeacherTheme { TeacherApp() } }
    }
}

@Composable
private fun TeacherApp() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { SettingsRepository(applicationContext) }
    val secureApiKeyStore = remember { SecureApiKeyStore(applicationContext) }
    val settings by settingsRepository.settings.collectAsState(initial = TutorSettings())
    var apiKeyConfigured by remember { mutableStateOf(false) }
    var credentialCheckFinished by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var savingSettings by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var overlayPermissionGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(applicationContext))
    }

    LaunchedEffect(Unit) {
        runCatching { secureApiKeyStore.isConfigured() }
            .onSuccess { apiKeyConfigured = it }
            .onFailure { settingsError = it.message ?: "Unable to read encrypted API key" }
        credentialCheckFinished = true
    }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val projectionData = result.data
        if (result.resultCode == Activity.RESULT_OK && projectionData != null) {
            TeacherService.start(context, result.resultCode, projectionData)
        } else {
            TeacherRuntime.markPermissionDenied("Screen-sharing permission was not granted.")
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayPermissionGranted = Settings.canDrawOverlays(applicationContext)
        if (!overlayPermissionGranted) {
            settingsError = "Floating subtitles need permission to appear over the reading app."
        } else {
            settingsError = null
        }
    }

    fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun requestScreenProjection() {
        val notificationPermissionMissing =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        if (notificationPermissionMissing) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestScreenProjection()
        else TeacherRuntime.markPermissionDenied("Microphone permission is required for voice tutoring.")
    }

    fun startTeacher() {
        scope.launch {
            val configured = runCatching { secureApiKeyStore.isConfigured() }
                .getOrElse {
                    TeacherRuntime.markError(it.message ?: "Unable to read encrypted API key")
                    false
                }
            apiKeyConfigured = configured
            credentialCheckFinished = true
            if (!configured) {
                settingsError = "Save an OpenAI API key before starting the Teacher."
                showSettings = true
                return@launch
            }
            overlayPermissionGranted = Settings.canDrawOverlays(applicationContext)
            if (settings.floatingSubtitlesEnabled && !overlayPermissionGranted) {
                settingsError = "Grant floating-window permission before starting the Teacher."
                showSettings = true
                return@launch
            }

            val microphonePermissionMissing = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
            if (microphonePermissionMissing) {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                requestScreenProjection()
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            initialSettings = settings,
            apiKeyConfigured = apiKeyConfigured,
            saving = savingSettings,
            errorMessage = settingsError,
            overlayPermissionGranted = overlayPermissionGranted,
            onSave = { newSettings, newApiKey ->
                scope.launch {
                    savingSettings = true
                    settingsError = null
                    try {
                        settingsRepository.save(newSettings)
                        if (newApiKey.isNotBlank()) secureApiKeyStore.save(newApiKey)
                        apiKeyConfigured = secureApiKeyStore.isConfigured()
                        showSettings = false
                    } catch (exception: Exception) {
                        settingsError = exception.message ?: "Unable to save settings"
                    } finally {
                        savingSettings = false
                    }
                }
            },
            onClearApiKey = {
                scope.launch {
                    savingSettings = true
                    settingsError = null
                    try {
                        secureApiKeyStore.clear()
                        apiKeyConfigured = false
                    } catch (exception: Exception) {
                        settingsError = exception.message ?: "Unable to clear API key"
                    } finally {
                        savingSettings = false
                    }
                }
            },
            onRequestOverlayPermission = ::requestOverlayPermission,
            onBack = {
                settingsError = null
                showSettings = false
            },
        )
    } else {
        MainScreen(
            statusFlow = TeacherRuntime.status,
            settings = settings,
            apiKeyConfigured = credentialCheckFinished && apiKeyConfigured,
            onStart = ::startTeacher,
            onStop = { TeacherService.stop(context) },
            onSettings = {
                settingsError = null
                showSettings = true
            },
        )
    }
}
