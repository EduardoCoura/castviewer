package com.castviewer.sender.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared reactive state store bridging CastProjectionService and Jetpack Compose UI
 * cleanly within the same application process.
 */
object CastStateStore {

    private val _session = MutableStateFlow(CastSessionState())
    val session: StateFlow<CastSessionState> = _session.asStateFlow()

    private val _prefs = MutableStateFlow(PerformancePrefs())
    val prefs: StateFlow<PerformancePrefs> = _prefs.asStateFlow()

    private val _devices = MutableStateFlow<List<FireStickDevice>>(emptyList())
    val devices: StateFlow<List<FireStickDevice>> = _devices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    private val _mdnsScanning = MutableStateFlow(false)
    val mdnsScanning: StateFlow<Boolean> = _mdnsScanning.asStateFlow()

    private val mdnsDevices = LinkedHashMap<String, FireStickDevice>()
    private val manualOrQrDevices = LinkedHashMap<String, FireStickDevice>()
    private val savedDevices = LinkedHashMap<String, FireStickDevice>()
    private val animIdCounter = AtomicLong(0L)

    fun updatePrefs(newPrefs: PerformancePrefs) {
        _prefs.value = newPrefs
        if (!newPrefs.animationsEnabled && _session.value.kineticAnim != null) {
            _session.update { it.copy(kineticAnim = null) }
        }
    }

    fun setMdnsScanning(active: Boolean) {
        _mdnsScanning.value = active
    }

    fun setSavedDevices(items: List<FireStickDevice>, preferredId: String? = null) {
        synchronized(this) {
            savedDevices.clear()
            for (d in items) {
                savedDevices[d.id] = d
            }
            if (preferredId != null && _selectedDeviceId.value == null) {
                _selectedDeviceId.value = preferredId
            }
            rebuildDeviceListLocked()
        }
    }

    fun upsertMdnsDevice(device: FireStickDevice) {
        synchronized(this) {
            mdnsDevices[device.id] = device.copy(source = DeviceSource.MDNS, isOnline = true)
            rebuildDeviceListLocked()
        }
    }

    fun removeMdnsDeviceByNameOrId(serviceName: String) {
        synchronized(this) {
            val iter = mdnsDevices.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key == serviceName || entry.value.name.equals(serviceName, ignoreCase = true)) {
                    iter.remove()
                }
            }
            rebuildDeviceListLocked()
        }
    }

    fun upsertManualOrQrDevice(device: FireStickDevice) {
        synchronized(this) {
            manualOrQrDevices[device.id] = device.copy(isOnline = true)
            _selectedDeviceId.value = device.id
            rebuildDeviceListLocked()
        }
    }

    fun removeSavedOrManualDevice(deviceId: String) {
        synchronized(this) {
            manualOrQrDevices.remove(deviceId)
            savedDevices.remove(deviceId)
            rebuildDeviceListLocked()
        }
    }

    fun updateSavedDeviceOnlineStatus(deviceId: String, online: Boolean) {
        synchronized(this) {
            var changed = false
            savedDevices[deviceId]?.let { existing ->
                if (existing.isOnline != online) {
                    savedDevices[deviceId] = existing.copy(isOnline = online)
                    changed = true
                }
            }
            if (changed) {
                rebuildDeviceListLocked()
            }
        }
    }

    fun selectDevice(deviceId: String?) {
        _selectedDeviceId.value = deviceId
    }

    fun selectedDevice(): FireStickDevice? {
        val list = _devices.value
        val id = _selectedDeviceId.value
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun updateSession(transform: (CastSessionState) -> CastSessionState) {
        _session.update(transform)
    }

    fun emitKineticAnimation(
        type: KineticAnimType,
        badge: String,
        title: String,
        subtitle: String = "",
        accentEmerald: Boolean = false,
        isPausedState: Boolean = false,
        createdAtMs: Long = System.currentTimeMillis()
    ): KineticAnimEvent? {
        if (!_prefs.value.animationsEnabled) {
            return null
        }
        val ev = KineticAnimEvent(
            id = animIdCounter.incrementAndGet(),
            type = type,
            badge = badge,
            title = title,
            subtitle = subtitle,
            accentEmerald = accentEmerald,
            isPausedState = isPausedState,
            createdAtMs = createdAtMs
        )
        _session.update { it.copy(kineticAnim = ev) }
        return ev
    }

    fun clearKineticAnimation(eventId: Long) {
        _session.update { state ->
            if (state.kineticAnim?.id == eventId) state.copy(kineticAnim = null) else state
        }
    }

    fun setIncomingTakeover(request: IncomingTakeoverRequest?) {
        _session.update { it.copy(incomingTakeover = request) }
    }

    fun clearIncomingTakeover(requestId: String? = null) {
        _session.update { state ->
            val current = state.incomingTakeover
            if (current != null && (requestId == null || current.requestId == requestId)) {
                state.copy(incomingTakeover = null)
            } else {
                state
            }
        }
    }

    fun resetSessionToIdle(message: String = "Pronto para espelhar", warning: String? = null) {
        _session.update {
            it.copy(
                connectionState = CastConnectionState.IDLE,
                statusMessage = message,
                activeDevice = null,
                telemetry = TelemetryStats(),
                warningBanner = warning,
                isSavingClip = false,
                clipFeedback = null,
                incomingTakeover = null
            )
        }
    }

    fun markBusyOrPreempted(device: FireStickDevice, activeSender: String?, detailMessage: String? = null) {
        val cleanOwner = activeSender?.trim()?.ifBlank { null } ?: "outro dispositivo"
        val cleanMsg = detailMessage?.trim()?.ifBlank { null }
            ?: "$cleanOwner assumiu a TV. Toque em \"Assumir TV\" para voltar a transmitir."
        _session.update {
            it.copy(
                busyHostDevice = device,
                busyActiveSender = cleanOwner,
                statusMessage = cleanMsg,
                incomingTakeover = null
            )
        }
    }

    fun clearBusyHostBanner() {
        _session.update {
            it.copy(busyHostDevice = null, busyActiveSender = null)
        }
    }

    private fun rebuildDeviceListLocked() {
        val merged = LinkedHashMap<String, FireStickDevice>()

        // 1. Live mDNS devices first (freshest online status and friendly Fire Stick name)
        for ((id, d) in mdnsDevices) {
            merged[id] = d
        }
        // 2. QR / Manual endpoints next (preserve friendly mDNS name if already resolved)
        for ((id, d) in manualOrQrDevices) {
            val existing = merged[id]
            merged[id] = if (existing != null) existing else d
        }
        // 3. Previously saved devices offline/fallback at the end
        for ((id, d) in savedDevices) {
            if (!merged.containsKey(id)) {
                merged[id] = d
            }
        }

        val ordered = merged.values.toList()
        _devices.value = ordered

        val currentSel = _selectedDeviceId.value
        if (currentSel == null || ordered.none { it.id == currentSel }) {
            _selectedDeviceId.value = ordered.firstOrNull()?.id
        }
    }
}
