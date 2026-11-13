package org.webrtc

import com.castviewer.sender.cast.ReplayClipRecorder

/**
 * Lightweight zero-reencode tap around a hardware H.264 [VideoEncoder].
 * Forwards every encoded H.264 packet to WebRTC first and mirrors an asynchronous
 * copy into [ReplayClipRecorder] when replay clip buffering is enabled.
 */
class TeeReplayVideoEncoder(
    private val delegate: VideoEncoder
) : VideoEncoder {

    @Volatile
    private var currentWidth: Int = 0

    @Volatile
    private var currentHeight: Int = 0

    override fun createNativeVideoEncoder(): Long = delegate.createNativeVideoEncoder()

    override fun isHardwareEncoder(): Boolean = delegate.isHardwareEncoder()

    override fun initEncode(
        settings: VideoEncoder.Settings,
        callback: VideoEncoder.Callback
    ): VideoCodecStatus {
        currentWidth = settings.width
        currentHeight = settings.height
        val wrappedCallback = VideoEncoder.Callback { frame, info ->
            val rollingActive = ReplayClipRecorder.isCaptureActive()
            val keyframeOnlyActive = !rollingActive &&
                ReplayClipRecorder.isStreamingActive() &&
                frame.frameType == EncodedImage.FrameType.VideoFrameKey
            val h264Copy: ByteArray? = if (
                (rollingActive || keyframeOnlyActive) &&
                frame.frameType != EncodedImage.FrameType.EmptyFrame
            ) {
                val view = frame.buffer.duplicate()
                val len = view.remaining()
                if (len in 4..4_194_304) {
                    ByteArray(len).also { bytes -> view.get(bytes) }
                } else {
                    null
                }
            } else {
                null
            }

            callback.onEncodedFrame(frame, info)

            if (h264Copy != null) {
                ReplayClipRecorder.appendH264Frame(
                    data = h264Copy,
                    width = frame.encodedWidth.takeIf { it > 0 } ?: currentWidth,
                    height = frame.encodedHeight.takeIf { it > 0 } ?: currentHeight,
                    isKeyFrameFlag = frame.frameType == EncodedImage.FrameType.VideoFrameKey
                )
            }
        }
        return delegate.initEncode(settings, wrappedCallback)
    }

    override fun release(): VideoCodecStatus = delegate.release()

    override fun encode(
        frame: VideoFrame,
        encodeInfo: VideoEncoder.EncodeInfo
    ): VideoCodecStatus {
        val nextInfo = if (ReplayClipRecorder.consumeForceKeyframeRequest()) {
            VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey))
        } else {
            encodeInfo
        }
        return delegate.encode(frame, nextInfo)
    }

    override fun setRateAllocation(
        allocation: VideoEncoder.BitrateAllocation,
        framerate: Int
    ): VideoCodecStatus = delegate.setRateAllocation(allocation, framerate)

    override fun setRates(
        parameters: VideoEncoder.RateControlParameters
    ): VideoCodecStatus = delegate.setRates(parameters)

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = delegate.getScalingSettings()

    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> =
        delegate.getResolutionBitrateLimits()

    override fun getImplementationName(): String = delegate.getImplementationName()

    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = delegate.getEncoderInfo()
}
