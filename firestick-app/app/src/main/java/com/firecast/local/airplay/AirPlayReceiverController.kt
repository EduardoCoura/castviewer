package com.firecast.local.airplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates native AirPlay 2 / RAOP server lifecycle, mDNS advertisement, direct-to-Surface
 * hardware video decoding, HDMI-safe audio playback, and clean session handoff with WebRTC.
 */
class AirPlayReceiverController(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val listener: Listener
) : AirPlayNativeBridge.NativeCallback {

    interface Listener {
        fun onAirPlayReady(serviceName: String, port: Int)
        fun onAirPlayUnavailable(reason: String)
        fun onAirPlaySessionStarted()
        fun onAirPlayFirstFrameRendered()
        fun onAirPlayVideoDimensionsChanged(width: Int, height: Int)
        fun onAirPlaySessionEnded()
        fun onAirPlayPinRequired(pin: String)
    }

    companion object {
        private const val TAG = "AirPlayController"
        private const val DEFAULT_AIRPLAY_PORT = 7000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdAdvertiser = AirPlayNsdAdvertiser(context)
    private val audioRenderer = AirPlayAudioRenderer()
    private val videoRenderer = AirPlayVideoRenderer(
        onVideoDimensionsChanged = { w, h ->
            mainHandler.post { listener.onAirPlayVideoDimensionsChanged(w, h) }
        },
        onFirstFrameRendered = {
            mainHandler.post { listener.onAirPlayFirstFrameRendered() }
        }
    )

    private val lifecycleLock = Any()
    private val sessionActive = AtomicBoolean(false)
    private val holderAttached = AtomicBoolean(false)

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var listeningPort: Int = 0

    @Volatile
    private var publishedName: String = ""

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            videoRenderer.setSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            videoRenderer.setSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            videoRenderer.setSurface(null)
        }
    }

    fun start(
        serviceName: String,
        displayWidth: Int,
        displayHeight: Int,
        refreshRateFps: Int
    ) {
        if (!AirPlayNativeBridge.isAvailable()) {
            val reason = AirPlayNativeBridge.getLoadError()?.message ?: "native library missing"
            Log.e(TAG, "AirPlay native engine unavailable: $reason")
            mainHandler.post { listener.onAirPlayUnavailable(reason) }
            return
        }

        synchronized(lifecycleLock) {
            stopLocked()
            attachSurfaceHolderLocked()

            val hwAddr = AirPlayNativeBridge.getOrCreateStableHardwareAddress(context)
            val keyPath = File(context.filesDir, "castviewer_airplay.key").absolutePath

            val handle = AirPlayNativeBridge.nativeInit(
                callback = this,
                hwAddr = hwAddr,
                name = serviceName,
                keyFile = keyPath,
                nohold = true,
                requirePin = false
            )
            if (handle == 0L) {
                mainHandler.post { listener.onAirPlayUnavailable("nativeInit failed") }
                return
            }

            AirPlayNativeBridge.nativeSetDisplaySize(
                handle,
                displayWidth.coerceAtLeast(1280),
                displayHeight.coerceAtLeast(720),
                refreshRateFps.coerceIn(24, 120)
            )
            val hevcSupported = AirPlayVideoRenderer.hasHardwareHevcDecoder()
            AirPlayNativeBridge.nativeSetH265Enabled(handle, hevcSupported)

            val port = AirPlayNativeBridge.nativeStart(handle, DEFAULT_AIRPLAY_PORT)
            if (port <= 0) {
                AirPlayNativeBridge.nativeDestroy(handle)
                mainHandler.post { listener.onAirPlayUnavailable("nativeStart failed ($port)") }
                return
            }

            nativeHandle = handle
            listeningPort = port
            publishedName = serviceName

            val airplayTxt = AirPlayNativeBridge.nativeGetAirplayTxtRecords(handle).orEmpty()
            val raopTxt = AirPlayNativeBridge.nativeGetRaopTxtRecords(handle).orEmpty()
            val raopServiceName = AirPlayNativeBridge.nativeGetRaopServiceName(handle)
                ?: "${AirPlayNativeBridge.formatMacHex(hwAddr).replace(":", "")}@$serviceName"

            nsdAdvertiser.start(
                serviceName = serviceName,
                raopServiceName = raopServiceName,
                port = port,
                airplayTxt = airplayTxt,
                raopTxt = raopTxt
            )

            Log.i(TAG, "AirPlay ready: name='$serviceName' port=$port hevc=$hevcSupported")
            mainHandler.post { listener.onAirPlayReady(serviceName, port) }
        }
    }

    fun isSessionActive(): Boolean = sessionActive.get()

    fun isRunning(): Boolean = nativeHandle != 0L && listeningPort > 0

    fun getListeningPort(): Int = listeningPort

    fun disconnectActiveSession() {
        val handle = nativeHandle
        if (handle != 0L) {
            try {
                AirPlayNativeBridge.nativeDisconnectActiveClient(handle)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeDisconnectActiveClient warning: ${t.message}")
            }
        }
        videoRenderer.resetSession()
        audioRenderer.flush()
        audioRenderer.setMuted(false)
        if (sessionActive.compareAndSet(true, false)) {
            mainHandler.post { listener.onAirPlaySessionEnded() }
        }
    }

    fun setMuted(muted: Boolean) {
        audioRenderer.setMuted(muted)
    }

    fun isMuted(): Boolean = audioRenderer.isMuted()

    fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
            videoRenderer.release()
            audioRenderer.release()
            detachSurfaceHolderLocked()
        }
    }

    private fun stopLocked() {
        nsdAdvertiser.stop()
        val handle = nativeHandle
        nativeHandle = 0L
        listeningPort = 0
        if (handle != 0L) {
            try {
                AirPlayNativeBridge.nativeStop(handle)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeStop error: ${t.message}")
            }
            try {
                AirPlayNativeBridge.nativeDestroy(handle)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeDestroy error: ${t.message}")
            }
        }
        videoRenderer.resetSession()
        audioRenderer.flush()
        sessionActive.set(false)
    }

    private fun attachSurfaceHolderLocked() {
        if (holderAttached.compareAndSet(false, true)) {
            val holder = surfaceView.holder
            holder.addCallback(surfaceCallback)
            holder.surface?.takeIf { it.isValid }?.let { valid ->
                videoRenderer.setSurface(valid)
            }
        }
    }

    private fun detachSurfaceHolderLocked() {
        if (holderAttached.compareAndSet(true, false)) {
            try {
                surfaceView.holder.removeCallback(surfaceCallback)
            } catch (_: Throwable) {
            }
        }
    }

    private fun markSessionStartedIfNeeded() {
        if (sessionActive.compareAndSet(false, true)) {
            mainHandler.post { listener.onAirPlaySessionStarted() }
        }
    }

    // ---- NativeCallback methods (invoked from native worker pthreads) ----

    override fun onVideoData(data: ByteArray, ptsUs: Long, isH265: Boolean) {
        markSessionStartedIfNeeded()
        videoRenderer.submitFrame(data, ptsUs, isH265)
    }

    override fun onVideoSize(isH265: Boolean, srcWidth: Int, srcHeight: Int, width: Int, height: Int) {
        val targetW = if (width > 0) width else srcWidth
        val targetH = if (height > 0) height else srcHeight
        videoRenderer.updateStreamSize(targetW, targetH)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        markSessionStartedIfNeeded()
        audioRenderer.configureFormat(ct, spf)
    }

    override fun onAudioData(data: ByteArray, ct: Int, ptsUs: Long) {
        markSessionStartedIfNeeded()
        audioRenderer.renderAudioData(data, ct, ptsUs)
    }

    override fun onAudioFlush() {
        audioRenderer.flush()
    }

    override fun onClientVolume(volumeDb: Float) {
        audioRenderer.setVolumeDb(volumeDb)
    }

    override fun onClientVolumeQuery(): Float = audioRenderer.getVolumeDb()

    override fun onConnectionInit() {
        Log.i(TAG, "AirPlay client connection initialized")
        markSessionStartedIfNeeded()
    }

    override fun onConnectionDestroy() {
        Log.i(TAG, "AirPlay client connection destroyed")
        videoRenderer.resetSession()
        audioRenderer.flush()
        audioRenderer.setMuted(false)
        if (sessionActive.compareAndSet(true, false)) {
            mainHandler.post { listener.onAirPlaySessionEnded() }
        }
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(TAG, "AirPlay client connection reset reason=$reason")
        videoRenderer.resetSession()
        audioRenderer.flush()
        if (sessionActive.compareAndSet(true, false)) {
            mainHandler.post { listener.onAirPlaySessionEnded() }
        }
    }

    override fun onVideoPause() {
        videoRenderer.pausePlayback()
    }

    override fun onVideoResume() {
        videoRenderer.resumePlayback()
    }

    override fun onDisplayPin(pin: String) {
        if (pin.isNotEmpty()) {
            mainHandler.post { listener.onAirPlayPinRequired(pin) }
        }
    }
}
