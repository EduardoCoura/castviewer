package com.castviewer.sender.model

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import kotlin.math.roundToInt

object HardwareProfiler {

    data class HardwareRecommendation(
        val hardwareInfo: AndroidHardwareInfo,
        val recommendedPreset: VideoPreset,
        val lowSpecMode: Boolean,
        val lowRamMode: Boolean,
        val compatMode: Boolean,
        val reason: String
    )

    private val WEAK_SOC_REGEX = Regex(
        """\b(mt673\d|mt675\d|mt65\d\d|mt6761|mt6762|mt6765|helio\s*(a20|a22|a25|p22|g25)|sc98\d\d|sp77\d\d|msm89\d\d|sdm4\d\d|qm215|exynos\s*75\d\d|exynos\s*7870|universal75\d\d|universal7870)\b""",
        RegexOption.IGNORE_CASE
    )

    private val MID_SOC_REGEX = Regex(
        """\b(helio\s*(g35|g36|g37|g70|g80|g85|g88|g90|g95|g96|g99)|mt6768|mt6769|mt678\d|sdm6\d\d|sm4\d\d\d|sm6\d\d\d|sm71\d\d|trinket|bengal|exynos\s*(7884|7885|7904|850|96\d\d|9810|1280|1330|1380)|universal(7885|7904|9610|9611)|s5e\d{4}|unisoc\s*t6\d\d)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Pure classification logic so desktop JVM unit tests can validate every phone tier.
     */
    fun classifyHardware(
        deviceModel: String,
        socDescriptor: String,
        cpuCores: Int,
        is64Bit: Boolean,
        ramGb: Float,
        hasHwH264Encoder: Boolean,
        hwEncoderName: String,
        physicalShortEdgePx: Int = 1080,
        powerSaveActive: Boolean = false
    ): HardwareRecommendation {
        val lowRamMode = ramGb <= 4.2f
        val veryLowRam = ramGb <= 3.2f
        val isWeakSoc = WEAK_SOC_REGEX.containsMatchIn(socDescriptor) || !is64Bit || cpuCores <= 4
        val isMidSoc = MID_SOC_REGEX.containsMatchIn(socDescriptor) || cpuCores <= 6
        val hdNativeScreen = physicalShortEdgePx in 320..820

        val tier: HardwareTier
        val lowSpecMode: Boolean
        val compatMode: Boolean
        val recommendedPreset: VideoPreset
        val reason: String

        when {
            !hasHwH264Encoder -> {
                tier = HardwareTier.WEAK
                lowSpecMode = true
                compatMode = true
                recommendedPreset = VideoPreset.PRESET_720P30
                reason = "Sem encoder H.264 dedicado: Modo Celular Fraco + Compatibilidade (720p 30 FPS)."
            }
            veryLowRam || isWeakSoc -> {
                tier = HardwareTier.WEAK
                lowSpecMode = true
                compatMode = false
                recommendedPreset = VideoPreset.PRESET_720P30
                reason = "Hardware de entrada ($cpuCores núcleos, $ramGb GB RAM): 720p 30 FPS leve."
            }
            powerSaveActive -> {
                tier = HardwareTier.BALANCED
                lowSpecMode = true
                compatMode = false
                recommendedPreset = VideoPreset.PRESET_720P30
                reason = "Economia de bateria ativa no Android: 720p 30 FPS para evitar cortes térmicos."
            }
            lowRamMode || isMidSoc || hdNativeScreen -> {
                tier = HardwareTier.BALANCED
                lowSpecMode = false
                compatMode = false
                recommendedPreset = VideoPreset.PRESET_720P60
                reason = when {
                    hdNativeScreen && lowRamMode ->
                        "Tela HD+ ($physicalShortEdgePx px) e $ramGb GB RAM: 720p 60 FPS nativo com Modo Pouca RAM."
                    hdNativeScreen ->
                        "Tela HD+ ($physicalShortEdgePx px): 720p 60 FPS nativo (evita gasto extra com upscaling)."
                    lowRamMode ->
                        "RAM <= 4 GB ($ramGb GB): Modo Pouca RAM + 720p 60 FPS equilibrado."
                    else ->
                        "Aparelho intermediário: 720p 60 FPS fluido e econômico."
                }
            }
            else -> {
                tier = HardwareTier.HIGH
                lowSpecMode = false
                compatMode = false
                recommendedPreset = VideoPreset.PRESET_1080P60
                reason = "Hardware potente ($ramGb GB RAM, H.264 HW): pronto para 1080p 60 FPS."
            }
        }

        return HardwareRecommendation(
            hardwareInfo = AndroidHardwareInfo(
                deviceModel = deviceModel,
                cpuCores = cpuCores,
                is64Bit = is64Bit,
                ramGb = ramGb,
                hasHwH264Encoder = hasHwH264Encoder,
                hwEncoderName = hwEncoderName,
                tier = tier
            ),
            recommendedPreset = recommendedPreset,
            lowSpecMode = lowSpecMode,
            lowRamMode = lowRamMode,
            compatMode = compatMode,
            reason = reason
        )
    }

    fun inspectDeviceHardware(context: Context): HardwareRecommendation {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        val ramGb = if (am != null) {
            am.getMemoryInfo(memInfo)
            val rawGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            ((rawGb * 10.0).roundToInt() / 10.0f).coerceAtLeast(1.0f)
        } else {
            4.0f
        }

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val deviceModel = buildFriendlyDeviceName()
        val socDescriptor = buildString {
            append(Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(' ').append(Build.SOC_MODEL)
                append(' ').append(Build.SOC_MANUFACTURER)
            }
            append(' ').append(Build.BOARD)
        }

        var hwEncoderName = ""
        var hasHwAvc = false
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                val supportsAvc = info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
                if (!supportsAvc) continue

                val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    info.name.startsWith("OMX.google.", ignoreCase = true) ||
                        info.name.startsWith("c2.android.", ignoreCase = true)
                }

                if (!isSoftware) {
                    hasHwAvc = true
                    hwEncoderName = info.name
                    break
                }
            }
        } catch (_: Exception) {
            hasHwAvc = true
            hwEncoderName = "MediaCodec H.264"
        }

        val dm = context.resources.displayMetrics
        val shortEdgePx = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(320)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val powerSaveActive = pm?.isPowerSaveMode == true

        return classifyHardware(
            deviceModel = deviceModel,
            socDescriptor = socDescriptor,
            cpuCores = cores,
            is64Bit = is64Bit,
            ramGb = ramGb,
            hasHwH264Encoder = hasHwAvc,
            hwEncoderName = hwEncoderName.ifBlank { "Software Fallback" },
            physicalShortEdgePx = shortEdgePx,
            powerSaveActive = powerSaveActive
        )
    }

    fun buildFriendlyDeviceName(): String {
        val mfg = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val full = if (model.startsWith(mfg, ignoreCase = true)) {
            model
        } else {
            "$mfg $model".trim()
        }
        return full.replaceFirstChar { it.uppercaseChar() }.ifBlank { "Celular Android" }.take(36)
    }
}
