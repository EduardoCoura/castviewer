package com.castviewer.sender.network

import com.castviewer.sender.model.ImageMode
import com.castviewer.sender.model.LatencyMode
import com.castviewer.sender.model.VideoPreset
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SignalingClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onStatus(state: String, message: String, activeSender: String?, timeoutSec: Int? = null)
        fun onTakeoverRequest(requestId: String, requesterName: String, timeoutSec: Int, message: String) {}
        fun onTakeoverCancelled(requestId: String) {}
        fun onPreempted(message: String, bySender: String?)
        fun onAnswer(sdp: String)
        fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onRemotePause(paused: Boolean)
        fun onBye()
        fun onDisconnected(reason: String, remote: Boolean)
        fun onError(error: String)
    }

    private var socket: WebSocketClient? = null
    private val closedManually = AtomicBoolean(false)

    fun connect(timeoutMs: Long = 6000L): Boolean {
        close()
        closedManually.set(false)
        val uri = URI("ws://$host:$port")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                listener.onConnected()
            }

            override fun onMessage(message: String?) {
                if (!message.isNullOrBlank()) {
                    handleIncomingMessage(message)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (!closedManually.get()) {
                    listener.onDisconnected(reason?.ifBlank { "Conexão encerrada ($code)" } ?: "Conexão encerrada", remote)
                }
            }

            override fun onError(ex: Exception?) {
                if (!closedManually.get()) {
                    listener.onError(ex?.message ?: "Erro de rede no WebSocket")
                }
            }
        }
        client.isTcpNoDelay = true
        client.connectionLostTimeout = 15
        socket = client

        return try {
            client.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            listener.onError(e.message ?: "Não foi possível conectar ao Fire Stick")
            false
        }
    }

    fun sendTakeover(senderName: String) {
        val payload = JSONObject()
            .put("type", "takeover")
            .put("senderName", senderName)
        sendJson(payload)
    }

    fun sendTakeoverResponse(requestId: String, accepted: Boolean) {
        val payload = JSONObject()
            .put("type", "takeover-response")
            .put("requestId", requestId)
            .put("accepted", accepted)
        sendJson(payload)
    }

    fun sendOffer(
        sdp: String,
        senderName: String,
        imageMode: ImageMode,
        latencyMode: LatencyMode,
        preset: VideoPreset,
        width: Int,
        height: Int,
        audioEnabled: Boolean,
        forceTakeover: Boolean = false
    ) {
        val videoObj = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("fps", preset.fps)

        val payload = JSONObject()
            .put("type", "offer")
            .put("sdp", sdp)
            .put("senderName", senderName)
            .put("imageMode", imageMode.key)
            .put("latencyMode", latencyMode.key)
            .put("video", videoObj)
            .put("audio", audioEnabled)
        if (forceTakeover) {
            payload.put("forceTakeover", true)
        }

        sendJson(payload)
    }

    fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val candidateObj = JSONObject()
            .put("candidate", candidate)
            .put("sdpMid", sdpMid ?: "0")
            .put("sdpMLineIndex", sdpMLineIndex)

        val payload = JSONObject()
            .put("type", "ice-candidate")
            .put("candidate", candidateObj)

        sendJson(payload)
    }

    fun sendStreamPause(paused: Boolean) {
        sendJson(
            JSONObject()
                .put("type", "stream-pause")
                .put("paused", paused)
        )
    }

    fun sendImageMode(mode: ImageMode) {
        sendJson(
            JSONObject()
                .put("type", "image-mode")
                .put("mode", mode.key)
        )
    }

    fun sendVideoProfile(preset: VideoPreset) {
        sendJson(
            JSONObject()
                .put("type", "video-profile")
                .put("preset", preset.key)
                .put("label", preset.shortLabel)
                .put("name", preset.title)
        )
    }

    fun sendLatencyMode(mode: LatencyMode) {
        sendJson(
            JSONObject()
                .put("type", "latency-mode")
                .put("mode", mode.key)
        )
    }

    fun sendClockConfig(enabled: Boolean, corner: String) {
        sendJson(
            JSONObject()
                .put("type", "clock-config")
                .put("enabled", enabled)
                .put("corner", corner)
        )
    }

    fun sendBye() {
        sendJson(JSONObject().put("type", "bye"))
    }

    fun close() {
        closedManually.set(true)
        val current = socket
        socket = null
        try {
            if (current != null && current.isOpen) {
                current.send(JSONObject().put("type", "bye").toString())
            }
        } catch (_: Exception) {
        }
        try {
            current?.close()
        } catch (_: Exception) {
        }
    }

    private fun sendJson(obj: JSONObject) {
        val client = socket ?: return
        if (!client.isOpen) return
        try {
            client.send(obj.toString())
        } catch (_: Exception) {
        }
    }

    internal fun dispatchMessageForTest(raw: String) {
        handleIncomingMessage(raw)
    }

    private fun handleIncomingMessage(raw: String) {
        try {
            val obj = JSONObject(raw)
            when (obj.optString("type")) {
                "status" -> {
                    val state = obj.optString("state", "")
                    val message = obj.optString("message", "")
                    val activeSender = obj.optString("activeSender", "").trim().ifBlank { null }
                    val timeoutSec = if (obj.has("timeoutSec")) {
                        obj.optInt("timeoutSec", 15).coerceAtLeast(5)
                    } else {
                        null
                    }
                    if (state.equals("preempted", ignoreCase = true)) {
                        listener.onPreempted(
                            message.ifBlank { "Outro dispositivo assumiu a TV." },
                            activeSender
                        )
                    } else {
                        listener.onStatus(state, message, activeSender, timeoutSec)
                    }
                }
                "takeover-request" -> {
                    val requestId = obj.optString("requestId", "").trim()
                    if (requestId.isNotBlank()) {
                        val requesterName = obj.optString("requesterName", "Outro dispositivo")
                            .trim()
                            .ifBlank { "Outro dispositivo" }
                        val timeoutSec = obj.optInt("timeoutSec", 15).coerceIn(5, 60)
                        val message = obj.optString("message", "")
                            .ifBlank { "$requesterName quer assumir a TV." }
                        listener.onTakeoverRequest(requestId, requesterName, timeoutSec, message)
                    }
                }
                "takeover-cancelled" -> {
                    val requestId = obj.optString("requestId", "").trim()
                    listener.onTakeoverCancelled(requestId)
                }
                "preempted" -> {
                    val bySender = obj.optString("by", "").trim().ifBlank { null }
                    val message = obj.optString("message", "").ifBlank {
                        bySender?.let { "$it assumiu a TV." } ?: "Outro dispositivo assumiu a TV."
                    }
                    listener.onPreempted(message, bySender)
                }
                "answer" -> {
                    val sdp = obj.optString("sdp", "")
                    if (sdp.isNotBlank()) {
                        listener.onAnswer(sdp)
                    }
                }
                "ice-candidate" -> {
                    val cand = obj.optJSONObject("candidate") ?: return
                    val candidateStr = cand.optString("candidate", "")
                    val sdpMid = cand.optString("sdpMid", "0")
                    val mLineIndex = cand.optInt("sdpMLineIndex", 0)
                    if (candidateStr.isNotBlank()) {
                        listener.onIceCandidate(candidateStr, sdpMid, mLineIndex)
                    }
                }
                "remote-pause" -> {
                    val paused = obj.optBoolean("paused", false)
                    listener.onRemotePause(paused)
                }
                "bye" -> listener.onBye()
            }
        } catch (_: Exception) {
        }
    }
}
