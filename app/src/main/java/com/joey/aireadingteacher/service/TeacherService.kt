package com.joey.aireadingteacher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.joey.aireadingteacher.MainActivity
import com.joey.aireadingteacher.R
import com.joey.aireadingteacher.capture.ScreenCaptureManager
import com.joey.aireadingteacher.provider.openai.OpenAIWebRtcProvider
import com.joey.aireadingteacher.settings.SecureApiKeyStore
import com.joey.aireadingteacher.settings.SettingsRepository
import com.joey.aireadingteacher.tutor.TutorConfig
import com.joey.aireadingteacher.tutor.TutorSession
import com.joey.aireadingteacher.tutor.VoiceSessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TeacherService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureManager: ScreenCaptureManager? = null
    private var tutorSession: TutorSession? = null
    private var subtitleOverlay: SubtitleOverlayManager? = null
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTeacher(intent)
            ACTION_STOP -> stopTeacher()
            else -> {
                Log.w(TAG, "Ignoring service start without a supported action")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        captureManager?.stop()
        captureManager = null
        runBlocking { runCatching { tutorSession?.stop() } }
        tutorSession = null
        subtitleOverlay?.hide()
        subtitleOverlay = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        TeacherRuntime.markStopped()
        Log.i(TAG, "TEACHER_STOPPED")
        super.onDestroy()
    }

    private fun startTeacher(intent: Intent) {
        if (starting || captureManager != null) {
            Log.i(TAG, "Teacher is already active; duplicate start ignored")
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.parcelableIntentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            failAndStop("Screen-sharing permission data is missing.")
            return
        }

        starting = true
        TeacherRuntime.markStarting()
        startInForeground()
        serviceScope.launch {
            try {
                val settings = SettingsRepository(this@TeacherService).settings.first()
                val apiKey = SecureApiKeyStore(this@TeacherService).read()
                    ?.takeIf { it.isNotBlank() }
                    ?: error("OpenAI API key is not configured. Open Settings and save a key.")
                TeacherRuntime.markConfigured(settings)
                if (settings.floatingSubtitlesEnabled && Settings.canDrawOverlays(this@TeacherService)) {
                    subtitleOverlay = SubtitleOverlayManager(this@TeacherService).also { it.show() }
                }

                val session = TutorSession(
                    provider = OpenAIWebRtcProvider(applicationContext),
                    listener = tutorListener,
                )
                tutorSession = session

                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error("Android did not return a MediaProjection instance")
                val manager = ScreenCaptureManager(
                    context = this@TeacherService,
                    mediaProjection = mediaProjection,
                    listener = captureListener,
                )
                captureManager = manager
                manager.start()
                starting = false

                session.start(
                    TutorConfig(
                        provider = settings.provider,
                        model = settings.model,
                        apiKey = apiKey,
                        baseUrl = settings.baseUrl,
                        globalInstructions = settings.globalInstructions,
                    ),
                )
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to start teacher", exception)
                failAndStop(exception.message ?: "Unable to start Teacher")
            }
        }
    }

    private val captureListener = object : ScreenCaptureManager.Listener {
        override fun onCaptureStarted() {
            TeacherRuntime.markScreenActive()
        }

        override fun onFrameSaved(path: String) {
            TeacherRuntime.markFrameSaved(path)
            tutorSession?.updateReadingContext(File(path))
        }

        override fun onProjectionStopped() {
            TeacherRuntime.markError("Android stopped screen sharing.")
            stopSelf()
        }

        override fun onCaptureError(message: String, cause: Throwable?) {
            Log.e(TAG, message, cause)
            failAndStop(message)
        }
    }

    private val tutorListener = object : TutorSession.Listener {
        override fun onVoiceStateChanged(state: VoiceSessionState) {
            TeacherRuntime.markVoiceState(state)
        }

        override fun onImageSent() {
            TeacherRuntime.markImageSent()
        }

        override fun onAssistantTranscriptDelta(id: String?, delta: String) {
            TeacherRuntime.appendAssistantTranscript(id, delta)
            TeacherRuntime.status.value.assistantTranscripts.lastOrNull()?.text?.let {
                subtitleOverlay?.updateText(it)
            }
        }

        override fun onAssistantTranscriptDone(id: String?, transcript: String) {
            TeacherRuntime.finishAssistantTranscript(id, transcript)
            TeacherRuntime.status.value.assistantTranscripts.lastOrNull()?.text?.let {
                subtitleOverlay?.updateText(it)
            }
        }

        override fun onRecoverableError(message: String, cause: Throwable?) {
            Log.w(TAG, message, cause)
            TeacherRuntime.markWarning(message)
        }

        override fun onFatalError(message: String, cause: Throwable?) {
            Log.e(TAG, message, cause)
            serviceScope.launch { failAndStop(message) }
        }
    }

    private fun stopTeacher() {
        Log.i(TAG, "Stop requested")
        captureManager?.stop()
        captureManager = null
        stopSelf()
    }

    private fun failAndStop(message: String) {
        starting = false
        TeacherRuntime.markError(message)
        captureManager?.stop()
        captureManager = null
        stopSelf()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, TeacherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_teacher_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_teacher_notification,
                getString(R.string.stop_teacher),
                stopPendingIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AIReadingTeacher"
        private const val NOTIFICATION_CHANNEL_ID = "teacher_capture"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 1002
        private const val REQUEST_STOP = 1003
        private const val ACTION_START = "com.joey.aireadingteacher.action.START"
        private const val ACTION_STOP = "com.joey.aireadingteacher.action.STOP"
        private const val EXTRA_RESULT_CODE = "projection_result_code"
        private const val EXTRA_RESULT_DATA = "projection_result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val serviceIntent = Intent(context, TeacherService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TeacherService::class.java))
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelableIntentExtra(key: String): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        getParcelableExtra(key)
    }
