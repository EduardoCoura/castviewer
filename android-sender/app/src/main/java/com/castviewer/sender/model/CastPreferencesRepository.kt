package com.castviewer.sender.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class CastPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadInitialPrefs(context: Context): PerformancePrefs {
        val hw = HardwareProfiler.inspectDeviceHardware(context)
        val userCustomized = prefs.getBoolean(KEY_USER_CUSTOMIZED, false)

        val defaultSenderName = hw.hardwareInfo.deviceModel.ifBlank { "Celular Android" }
        val senderName = prefs.getString(KEY_SENDER_NAME, defaultSenderName)
            ?.trim()
            .orEmpty()
            .ifBlank { defaultSenderName }

        val imageMode = ImageMode.fromKey(prefs.getString(KEY_IMAGE_MODE, ImageMode.MOTION.key))
        val latencyMode = LatencyMode.fromKey(prefs.getString(KEY_LATENCY_MODE, LatencyMode.ULTRA.key))
        val audioMode = AudioCaptureMode.fromKey(prefs.getString(KEY_AUDIO_MODE, AudioCaptureMode.INTERNAL.key))
        val replayClipEnabled = prefs.getBoolean(KEY_REPLAY_CLIP_ENABLED, true)
        val amoledSaverEnabled = prefs.getBoolean(KEY_AMOLED_SAVER, false)
        val floatingOverlayEnabled = prefs.getBoolean(KEY_FLOATING_OVERLAY, false)
        val gpuIntroMigrated = prefs.getBoolean(KEY_GPU_INTRO_MIGRATED, false)
        val animationsEnabled = if (!gpuIntroMigrated) {
            prefs.edit().putBoolean(KEY_GPU_INTRO_MIGRATED, true).apply()
            true
        } else if (prefs.contains(KEY_ANIMATIONS_ENABLED)) {
            prefs.getBoolean(KEY_ANIMATIONS_ENABLED, true)
        } else {
            true
        }
        val streamClockEnabled = prefs.getBoolean(KEY_STREAM_CLOCK_ENABLED, false)
        val streamClockCorner = PerformancePrefs.normalizeClockCorner(
            prefs.getString(KEY_STREAM_CLOCK_CORNER, "br")
        )

        var preset: VideoPreset
        var lowSpecMode: Boolean
        val lowRamMode: Boolean
        val compatMode: Boolean

        if (userCustomized) {
            preset = VideoPreset.fromKey(prefs.getString(KEY_PRESET, hw.recommendedPreset.key))
            lowSpecMode = prefs.getBoolean(KEY_LOW_SPEC, hw.lowSpecMode)
            lowRamMode = prefs.getBoolean(KEY_LOW_RAM, hw.lowRamMode)
            compatMode = prefs.getBoolean(KEY_COMPAT_MODE, hw.compatMode)
        } else {
            preset = hw.recommendedPreset
            lowSpecMode = hw.lowSpecMode
            lowRamMode = hw.lowRamMode
            compatMode = hw.compatMode
        }

        var finalUserCustomized = userCustomized
        val profile60Migrated = prefs.getBoolean(KEY_PROFILE_60FPS_MIGRATED, false)
        if (!profile60Migrated) {
            prefs.edit().putBoolean(KEY_PROFILE_60FPS_MIGRATED, true).apply()
            // Restore 60 FPS hardware profile once on mid-tier phones (e.g., Galaxy A30s Exynos 7904)
            // that previously had stale 720p30 + low_spec_mode persisted from older builds.
            if (!hw.lowSpecMode && hw.recommendedPreset.fps >= 60 && lowSpecMode && preset == VideoPreset.PRESET_720P30) {
                preset = hw.recommendedPreset
                lowSpecMode = false
                finalUserCustomized = false
            }
        }

        val loaded = PerformancePrefs(
            preset = preset,
            imageMode = imageMode,
            latencyMode = latencyMode,
            audioMode = audioMode,
            lowSpecMode = lowSpecMode,
            lowRamMode = lowRamMode,
            compatMode = compatMode,
            userCustomized = finalUserCustomized,
            recommendedPreset = hw.recommendedPreset,
            autoReason = hw.reason,
            senderName = senderName,
            replayClipEnabled = replayClipEnabled,
            amoledSaverEnabled = amoledSaverEnabled,
            floatingOverlayEnabled = floatingOverlayEnabled,
            animationsEnabled = animationsEnabled,
            streamClockEnabled = streamClockEnabled,
            streamClockCorner = streamClockCorner,
            hardwareInfo = hw.hardwareInfo
        )
        savePrefs(loaded)
        return loaded
    }

    fun savePrefs(value: PerformancePrefs) {
        prefs.edit()
            .putString(KEY_PRESET, value.preset.key)
            .putString(KEY_IMAGE_MODE, value.imageMode.key)
            .putString(KEY_LATENCY_MODE, value.latencyMode.key)
            .putString(KEY_AUDIO_MODE, value.audioMode.key)
            .putBoolean(KEY_LOW_SPEC, value.lowSpecMode)
            .putBoolean(KEY_LOW_RAM, value.lowRamMode)
            .putBoolean(KEY_COMPAT_MODE, value.compatMode)
            .putBoolean(KEY_USER_CUSTOMIZED, value.userCustomized)
            .putString(KEY_SENDER_NAME, value.senderName.trim().take(36))
            .putBoolean(KEY_REPLAY_CLIP_ENABLED, value.replayClipEnabled)
            .putBoolean(KEY_AMOLED_SAVER, value.amoledSaverEnabled)
            .putBoolean(KEY_FLOATING_OVERLAY, value.floatingOverlayEnabled)
            .putBoolean(KEY_ANIMATIONS_ENABLED, value.animationsEnabled)
            .putBoolean(KEY_STREAM_CLOCK_ENABLED, value.streamClockEnabled)
            .putString(KEY_STREAM_CLOCK_CORNER, PerformancePrefs.normalizeClockCorner(value.streamClockCorner))
            .apply()
    }

    fun resetToAutoRecommended(context: Context, current: PerformancePrefs): PerformancePrefs {
        val hw = HardwareProfiler.inspectDeviceHardware(context)
        val updated = current.copy(
            preset = hw.recommendedPreset,
            lowSpecMode = hw.lowSpecMode,
            lowRamMode = hw.lowRamMode,
            compatMode = hw.compatMode,
            userCustomized = false,
            recommendedPreset = hw.recommendedPreset,
            autoReason = hw.reason,
            animationsEnabled = current.animationsEnabled,
            hardwareInfo = hw.hardwareInfo
        )
        savePrefs(updated)
        return updated
    }

    fun loadSavedDevices(): List<FireStickDevice> {
        val raw = prefs.getString(KEY_SAVED_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ip = obj.optString("ip", "").trim()
                    if (!QrUriParser.isValidIpv4(ip)) continue
                    val port = obj.optInt("port", QrUriParser.DEFAULT_PORT)
                        .takeIf { it in 1..65535 } ?: QrUriParser.DEFAULT_PORT
                    val name = obj.optString("name", "Fire Stick ($ip)")
                        .trim()
                        .ifBlank { "Fire Stick ($ip)" }
                    add(
                        FireStickDevice(
                            id = "$ip:$port",
                            name = name,
                            ip = ip,
                            port = port,
                            source = DeviceSource.SAVED,
                            isOnline = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsertSavedDevice(device: FireStickDevice) {
        val existing = loadSavedDevices().filterNot { it.id == device.id }
        val savedCopy = device.copy(source = DeviceSource.SAVED, isOnline = false)
        val merged = (listOf(savedCopy) + existing).take(MAX_SAVED_DEVICES)
        val arr = JSONArray()
        for (item in merged) {
            arr.put(
                JSONObject()
                    .put("name", item.name)
                    .put("ip", item.ip)
                    .put("port", item.port)
            )
        }
        prefs.edit().putString(KEY_SAVED_DEVICES, arr.toString()).apply()
    }

    fun removeSavedDevice(deviceId: String) {
        val remaining = loadSavedDevices().filterNot { it.id == deviceId }
        val arr = JSONArray()
        for (item in remaining) {
            arr.put(
                JSONObject()
                    .put("name", item.name)
                    .put("ip", item.ip)
                    .put("port", item.port)
            )
        }
        prefs.edit().putString(KEY_SAVED_DEVICES, arr.toString()).apply()
    }

    fun getLastSelectedDeviceId(): String? =
        prefs.getString(KEY_LAST_DEVICE_ID, null)?.trim()?.ifBlank { null }

    fun setLastSelectedDeviceId(deviceId: String?) {
        prefs.edit().putString(KEY_LAST_DEVICE_ID, deviceId).apply()
    }

    companion object {
        private const val PREFS_NAME = "castviewer_sender_prefs"
        private const val MAX_SAVED_DEVICES = 8

        private const val KEY_PRESET = "preset"
        private const val KEY_IMAGE_MODE = "image_mode"
        private const val KEY_LATENCY_MODE = "latency_mode"
        private const val KEY_AUDIO_MODE = "audio_mode"
        private const val KEY_LOW_SPEC = "low_spec_mode"
        private const val KEY_LOW_RAM = "low_ram_mode"
        private const val KEY_COMPAT_MODE = "compat_mode"
        private const val KEY_USER_CUSTOMIZED = "user_customized"
        private const val KEY_SENDER_NAME = "sender_name"
        private const val KEY_REPLAY_CLIP_ENABLED = "replay_clip_enabled"
        private const val KEY_AMOLED_SAVER = "amoled_saver_enabled"
        private const val KEY_FLOATING_OVERLAY = "floating_overlay_enabled"
        private const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
        private const val KEY_GPU_INTRO_MIGRATED = "gpu_intro_v2_migrated"
        private const val KEY_PROFILE_60FPS_MIGRATED = "turbo_60fps_v3_migrated"
        private const val KEY_STREAM_CLOCK_ENABLED = "stream_clock_enabled"
        private const val KEY_STREAM_CLOCK_CORNER = "stream_clock_corner"
        private const val KEY_SAVED_DEVICES = "saved_devices_json"
        private const val KEY_LAST_DEVICE_ID = "last_device_id"
    }
}
