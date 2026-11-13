package com.castviewer.sender.cast

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.castviewer.sender.R
import com.castviewer.sender.model.CastConnectionState
import com.castviewer.sender.model.CastStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Minimalist movable floating bubble over fullscreen apps/games (`SYSTEM_ALERT_WINDOW`).
 * Expands a compact quick-action bar with 1-tap Clip 30s, 1-tap Screenshot, and Pause/Resume.
 */
class CastFloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootLayout: LinearLayout? = null
    private var bubbleView: LinearLayout? = null
    private var menuBar: LinearLayout? = null
    private var pauseBtnText: TextView? = null
    private var clipBtnText: TextView? = null
    private var shotBtnText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private val autoCollapseRunnable = Runnable {
        setExpanded(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val state = CastStateStore.session.value.connectionState
        if (state != CastConnectionState.STREAMING && state != CastConnectionState.PAUSED) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureOverlayCreated()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        stateJob?.cancel()
        serviceScope.cancel()
        rootLayout?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        rootLayout = null
        super.onDestroy()
    }

    private fun ensureOverlayCreated() {
        if (rootLayout != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }
        layoutParams = params

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bubbleSize = dp(44)
        val orb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E614161D"))
                setStroke(dp(2), Color.parseColor("#FFFF5A1F"))
            }
            elevation = dp(6).toFloat()
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_tile_clip)
                setColorFilter(Color.parseColor("#FFFF6B2B"))
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
            }
            addView(icon, LinearLayout.LayoutParams(bubbleSize, bubbleSize))
        }
        bubbleView = orb

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F014161D"))
                setStroke(dp(1), Color.parseColor("#FF2E3342"))
            }
            elevation = dp(6).toFloat()
        }

        val clipBtn = createActionChip("Clip 30s", "#FF5A1F", "#FFFFFF") {
            CastProjectionService.saveReplayClip(this)
            setExpanded(false)
        }.also { clipBtnText = it }

        val shotBtn = createActionChip("Foto", "#252935", "#F3F4F6") {
            CastProjectionService.takeScreenshot(this)
            setExpanded(false)
        }.also { shotBtnText = it }

        val pauseBtn = createActionChip("Pausar", "#252935", "#FBBF24") {
            CastProjectionService.togglePause(this)
            scheduleAutoCollapse()
        }.also { pauseBtnText = it }

        val minimizeBtn = createActionChip("−", "#1A1D26", "#9CA3AF") {
            setExpanded(false)
        }
        val closeBtn = createActionChip("×", "#2E1518", "#FCA5A5") {
            setExpanded(false)
            CastProjectionService.disableFloatingOverlay(this)
        }

        bar.addView(clipBtn)
        bar.addView(shotBtn)
        bar.addView(pauseBtn)
        bar.addView(minimizeBtn)
        bar.addView(closeBtn)
        menuBar = bar

        val orbLp = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
        val barLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(8)
        }
        root.addView(orb, orbLp)
        root.addView(bar, barLp)

        attachDragAndTapListener(orb)

        wm.addView(root, params)
        rootLayout = root

        stateJob?.cancel()
        stateJob = serviceScope.launch {
            CastStateStore.session.collect { session ->
                val active = session.connectionState == CastConnectionState.STREAMING ||
                    session.connectionState == CastConnectionState.PAUSED
                if (!active) {
                    stopSelf()
                    return@collect
                }
                val isPaused = session.connectionState == CastConnectionState.PAUSED
                pauseBtnText?.text = if (isPaused) "Retomar" else "Pausar"
                pauseBtnText?.setTextColor(
                    Color.parseColor(if (isPaused) "#34D399" else "#FBBF24")
                )
                clipBtnText?.text = if (session.isSavingClip) "30s..." else "Clip 30s"
                shotBtnText?.text = if (session.isTakingScreenshot) "Foto..." else "Foto"
            }
        }
    }

    private fun createActionChip(
        label: String,
        bgHex: String,
        textHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(textHex))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun attachDragAndTapListener(target: View) {
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        val slop = dp(10)

        target.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        val dm = resources.displayMetrics
                        val maxX = (dm.widthPixels - 140).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - 140).coerceAtLeast(0)
                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn(0, maxY)
                        try {
                            windowManager?.updateViewLayout(rootLayout, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        setExpanded(!isExpanded)
                    } else if (!isExpanded) {
                        snapToNearestEdge(params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeMargin = dp(12)
        val bubbleW = bubbleView?.width?.takeIf { it > 0 } ?: dp(44)
        params.x = if (params.x + bubbleW / 2 > screenWidth / 2) {
            (screenWidth - bubbleW - edgeMargin).coerceAtLeast(0)
        } else {
            edgeMargin
        }
        try {
            windowManager?.updateViewLayout(rootLayout, params)
        } catch (_: Exception) {
        }
    }

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        val root = rootLayout
        val orb = bubbleView
        val bar = menuBar
        val params = layoutParams
        if (root != null && orb != null && bar != null && params != null) {
            val screenWidth = resources.displayMetrics.widthPixels
            val onRightHalf = params.x > screenWidth / 2
            root.removeAllViews()
            val orbSize = dp(44)
            val orbLp = LinearLayout.LayoutParams(orbSize, orbSize)
            val barLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = if (onRightHalf) 0 else dp(8)
                marginEnd = if (onRightHalf) dp(8) else 0
            }
            if (expanded) {
                bar.visibility = View.VISIBLE
                if (onRightHalf) {
                    root.addView(bar, barLp)
                    root.addView(orb, orbLp)
                    params.x = (screenWidth - dp(250)).coerceAtLeast(dp(8))
                } else {
                    root.addView(orb, orbLp)
                    root.addView(bar, barLp)
                }
            } else {
                bar.visibility = View.GONE
                root.addView(orb, orbLp)
                snapToNearestEdge(params)
            }
            try {
                windowManager?.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        } else {
            menuBar?.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (expanded) {
            scheduleAutoCollapse()
        }
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, 4500L)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        fun syncOverlayVisibility(context: Context, shouldShow: Boolean) {
            val appCtx = context.applicationContext
            if (shouldShow && Settings.canDrawOverlays(appCtx)) {
                try {
                    appCtx.startService(Intent(appCtx, CastFloatingOverlayService::class.java))
                } catch (_: Exception) {
                }
            } else {
                stopOverlay(appCtx)
            }
        }

        fun stopOverlay(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, CastFloatingOverlayService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
