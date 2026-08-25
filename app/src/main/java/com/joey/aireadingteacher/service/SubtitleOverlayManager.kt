package com.joey.aireadingteacher.service

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

class SubtitleOverlayManager(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var subtitleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        mainHandler.post {
            if (subtitleView != null || !Settings.canDrawOverlays(appContext)) return@post

            val view = TextView(appContext).apply {
                text = ""
                setTextColor(Color.WHITE)
                textSize = 18f
                setLineSpacing(0f, 1.1f)
                includeFontPadding = false
                maxLines = 5
                ellipsize = TextUtils.TruncateAt.END
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
                minWidth = 180.dp
                maxWidth = 620.dp
                elevation = 8.dp.toFloat()
                background = GradientDrawable().apply {
                    setColor(0xE6202020.toInt())
                    cornerRadius = 14.dp.toFloat()
                    setStroke(1.dp, 0x44FFFFFF)
                }
                visibility = View.INVISIBLE
                contentDescription = "AI Reading Teacher subtitles. Drag to move; tap to hide."
                setOnClickListener { visibility = View.INVISIBLE }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24.dp
                y = 96.dp
            }
            installDragGesture(view, params)
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    subtitleView = view
                    layoutParams = params
                    Log.i(TAG, "SUBTITLE_OVERLAY_SHOWN")
                }
                .onFailure { Log.e(TAG, "SUBTITLE_OVERLAY_FAILED", it) }
        }
    }

    fun updateText(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            val view = subtitleView ?: return@post
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    fun hide() {
        mainHandler.post {
            subtitleView?.let { view -> runCatching { windowManager.removeView(view) } }
            subtitleView = null
            layoutParams = null
            Log.i(TAG, "SUBTITLE_OVERLAY_HIDDEN")
        }
    }

    private fun installDragGesture(
        view: TextView,
        params: WindowManager.LayoutParams,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        var moved = false
        val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = params.x
                    downWindowY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                        kotlin.math.abs(event.rawY - downRawY) > touchSlop
                    ) {
                        moved = true
                    }
                    val screenSize = currentScreenSize()
                    params.x = (downWindowX + event.rawX - downRawX).roundToInt()
                        .coerceIn(0, (screenSize.x - view.width).coerceAtLeast(0))
                    params.y = (downWindowY + event.rawY - downRawY).roundToInt()
                        .coerceIn(0, (screenSize.y - view.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentScreenSize(): Point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
    } else {
        Point().also { windowManager.defaultDisplay.getSize(it) }
    }

    private val Int.dp: Int
        get() = (this * appContext.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "AIReadingTeacher"
    }
}
