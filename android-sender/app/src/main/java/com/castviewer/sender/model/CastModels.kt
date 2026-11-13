package com.castviewer.sender.model

enum class VideoPreset(
    val key: String,
    val title: String,
    val shortLabel: String,
    val badge: String,
    val maxLongEdge: Int,
    val maxShortEdge: Int,
    val fps: Int,
    val bitrateBps: Int
) {
    PRESET_1080P60(
        key = "1080p60",
        title = "1080p 60 FPS",
        shortLabel = "1080p60",
        badge = "ULTRA",
        maxLongEdge = 1920,
        maxShortEdge = 1080,
        fps = 60,
        bitrateBps = 12_000_000
    ),
    PRESET_720P60(
        key = "720p60",
        title = "720p 60 FPS",
        shortLabel = "720p60",
        badge = "ÁGIL",
        maxLongEdge = 1280,
        maxShortEdge = 720,
        fps = 60,
        bitrateBps = 7_000_000
    ),
    PRESET_540P60(
        key = "540p60",
        title = "540p 60 FPS",
        shortLabel = "540p60",
        badge = "TURBO",
        maxLongEdge = 960,
        maxShortEdge = 544,
        fps = 60,
        bitrateBps = 4_800_000
    ),
    PRESET_1080P30(
        key = "1080p30",
        title = "1080p 30 FPS",
        shortLabel = "1080p30",
        badge = "NITIDEZ",
        maxLongEdge = 1920,
        maxShortEdge = 1080,
        fps = 30,
        bitrateBps = 6_000_000
    ),
    PRESET_720P30(
        key = "720p30",
        title = "720p 30 FPS",
        shortLabel = "720p30",
        badge = "LEVE",
        maxLongEdge = 1280,
        maxShortEdge = 720,
        fps = 30,
        bitrateBps = 3_500_000
    );

    companion object {
        fun fromKey(key: String?): VideoPreset =
            entries.firstOrNull { it.key == key } ?: PRESET_1080P60
    }
}

enum class ImageMode(val key: String, val label: String, val desc: String) {
    MOTION("motion", "Movimento", "Prioriza 60 FPS fluidos em vídeos e jogos"),
    DETAIL("detail", "Nitidez", "Prioriza texto e detalhes (+30% Mbps)");

    companion object {
        fun fromKey(key: String?): ImageMode =
            entries.firstOrNull { it.key == key } ?: MOTION
    }
}

enum class LatencyMode(val key: String, val label: String, val desc: String) {
    ULTRA("ultra", "Ultra 0 ms", "Menor atraso possível (Wi-Fi 5 GHz ou cabo)"),
    BALANCED("balanced", "Estável ~60 ms", "Suaviza oscilações em Wi-Fi distante");

    companion object {
        fun fromKey(key: String?): LatencyMode =
            entries.firstOrNull { it.key == key } ?: ULTRA
    }
}

enum class AudioCaptureMode(val key: String, val label: String, val subtitle: String) {
    INTERNAL("internal", "Áudio Interno", "Som de apps e jogos direto na TV (Android 10+)"),
    MIC("mic", "Microfone", "Voz / som ambiente captado pelo aparelho"),
    OFF("off", "Sem Áudio", "Espelha somente a imagem da tela");

    companion object {
        fun fromKey(key: String?): AudioCaptureMode =
            entries.firstOrNull { it.key == key } ?: INTERNAL
    }
}

enum class DeviceSource {
    MDNS, QR, MANUAL, SAVED
}

data class FireStickDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 8765,
    val source: DeviceSource = DeviceSource.MDNS,
    val isOnline: Boolean = true
)

enum class HardwareTier {
    HIGH, BALANCED, WEAK
}

data class AndroidHardwareInfo(
    val deviceModel: String,
    val cpuCores: Int,
    val is64Bit: Boolean,
    val ramGb: Float,
    val hasHwH264Encoder: Boolean,
    val hwEncoderName: String,
    val tier: HardwareTier
)

data class PerformancePrefs(
    val preset: VideoPreset = VideoPreset.PRESET_1080P60,
    val imageMode: ImageMode = ImageMode.MOTION,
    val latencyMode: LatencyMode = LatencyMode.ULTRA,
    val audioMode: AudioCaptureMode = AudioCaptureMode.INTERNAL,
    val lowSpecMode: Boolean = false,
    val lowRamMode: Boolean = false,
    val compatMode: Boolean = false,
    val userCustomized: Boolean = false,
    val recommendedPreset: VideoPreset = VideoPreset.PRESET_1080P60,
    val autoReason: String = "",
    val senderName: String = "Celular Android",
    val replayClipEnabled: Boolean = true,
    val amoledSaverEnabled: Boolean = false,
    val floatingOverlayEnabled: Boolean = false,
    val animationsEnabled: Boolean = true,
    val streamClockEnabled: Boolean = false,
    val streamClockCorner: String = "br",
    val hardwareInfo: AndroidHardwareInfo? = null
) {
    companion object {
        val VALID_CLOCK_CORNERS = setOf("tl", "tr", "bl", "br")

        fun normalizeClockCorner(raw: String?): String {
            val clean = raw?.trim()?.lowercase()
            return if (clean in VALID_CLOCK_CORNERS) clean!! else "br"
        }
    }
}

enum class CastConnectionState {
    IDLE, CONNECTING, STREAMING, PAUSED, ERROR
}

data class TelemetryStats(
    val rttMs: Int? = null,
    val fps: Int? = null,
    val bitrateMbps: Float? = null,
    val resolution: String = "",
    val codecLabel: String = "H.264"
)

enum class KineticAnimType {
    COLD_INTRO,
    CAST_CONNECT,
    CAST_DISCONNECT,
    PAUSE_STATE,
    SETTING_BADGE
}

data class KineticAnimEvent(
    val id: Long,
    val type: KineticAnimType,
    val badge: String,
    val title: String,
    val subtitle: String,
    val accentEmerald: Boolean = false,
    val isPausedState: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class IncomingTakeoverRequest(
    val requestId: String,
    val requesterName: String,
    val timeoutSec: Int,
    val expiresAtMs: Long
)

data class CastSessionState(
    val connectionState: CastConnectionState = CastConnectionState.IDLE,
    val statusMessage: String = "Pronto para espelhar",
    val activeDevice: FireStickDevice? = null,
    val activeAudioMode: AudioCaptureMode = AudioCaptureMode.INTERNAL,
    val telemetry: TelemetryStats = TelemetryStats(),
    val warningBanner: String? = null,
    val isSavingClip: Boolean = false,
    val clipFeedback: String? = null,
    val isTakingScreenshot: Boolean = false,
    val shotFeedback: String? = null,
    val busyHostDevice: FireStickDevice? = null,
    val busyActiveSender: String? = null,
    val incomingTakeover: IncomingTakeoverRequest? = null,
    val kineticAnim: KineticAnimEvent? = null,
    val mediaGeneration: Int = 0
)
