package com.firecast.local

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Embedded WebSocket server implementing the FireCast local signaling protocol (PROTOCOL.md).
 * Listens on port 8765 and coordinates WebRTC SDP exchange and ICE candidates.
 */
class SignalingServer(port: Int = DEFAULT_PORT) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "SignalingServer"
        const val DEFAULT_PORT = 8765
    }

    interface Listener {
        fun onOfferReceived(sdp: String, senderName: String)
        fun onIceCandidateReceived(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onSourceSwitchReceived(sourceName: String, isWindow: Boolean)
        fun onStreamPauseReceived(paused: Boolean)
        fun onImageModeChanged(mode: String)
        fun onVideoProfileChanged(preset: String, label: String, name: String)
        fun onLatencyModeChanged(mode: String)
        fun onClockConfigChanged(enabled: Boolean, corner: String) {}
        fun onByeReceived()
        fun onSessionDisconnected(reason: String)
        fun onRejectedBusyAttempt(attemptedSender: String, activeSender: String) {}
        fun onTakeoverRequested(challengerName: String, activeSender: String) {}
        fun onTakeoverDenied(challengerName: String, activeSender: String) {}
        fun onSessionTakeover(newSenderName: String) {}
        fun onServerStarted(port: Int)
        fun onServerError(error: Exception)
    }

    var listener: Listener? = null

    // Track single active casting session
    @Volatile
    private var activeSessionClient: WebSocket? = null
    @Volatile
    private var activeSenderName: String = ""
    private val dispatchOrderLock = Any()

    private val takeoverExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastViewer-TakeoverTimer").apply { isDaemon = true }
    }

    private val takeoverCoordinator = TakeoverCoordinator<WebSocket>(
        timeoutMs = TakeoverCoordinator.DEFAULT_TIMEOUT_MS,
        scheduler = object : TakeoverCoordinator.Scheduler {
            override fun schedule(delayMs: Long, action: Runnable): TakeoverCoordinator.Cancellable {
                val future = takeoverExecutor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
                return object : TakeoverCoordinator.Cancellable {
                    override fun cancel() {
                        future.cancel(false)
                    }
                }
            }
        }
    )

    init {
        // Allow immediate rebinding to the port across app restarts
        isReuseAddr = true
        isTcpNoDelay = true
    }

    override fun onStart() {
        Log.i(TAG, "Signaling server started on port: $port")
        listener?.onServerStarted(port)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val remoteAddr = conn.remoteSocketAddress?.address
        val remoteHost = remoteAddr?.hostAddress ?: "unknown"
        val isLocalLan =
            remoteAddr == null ||
                remoteAddr.isSiteLocalAddress ||
                remoteAddr.isLoopbackAddress ||
                remoteAddr.isLinkLocalAddress
        if (!isLocalLan) {
            Log.w(TAG, "Rejecting non-LAN client from $remoteHost")
            conn.close(1008, "Apenas rede local (LAN) permitida")
            return
        }
        Log.i(TAG, "Client connected from: $remoteHost")

        synchronized(this) {
            val current = activeSessionClient
            if (current == null || !current.isOpen) {
                activeSenderName = ""
                // Device is ready to receive
                sendStatus(conn, state = "ready", message = "CastViewer pronto para transmitir")
            } else if (current != conn) {
                val activeWho = activeSenderName.ifBlank { "outro dispositivo" }
                Log.w(TAG, "Rejecting new client $remoteHost because session is busy with $activeWho.")
                sendStatus(
                    conn,
                    state = "busy",
                    message = "Já conectado em outro dispositivo ($activeWho)",
                    activeSender = activeWho
                )
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Client disconnected: code=$code, reason=$reason, remote=$remote")
        handleSocketClosed(conn, reason.ifBlank { "Desconectado" }, isBye = false)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket server error (conn: $conn)", ex)
        if (conn != null) {
            handleSocketClosed(conn, "Erro de conexão: ${ex.message}", isBye = false)
        }
        if (conn == null) {
            listener?.onServerError(ex)
        }
    }

    override fun stop(timeout: Int) {
        takeoverCoordinator.cancelAll()
        try {
            takeoverExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        super.stop(timeout)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (message.length > 65536) {
            Log.w(TAG, "Ignoring oversized WebSocket message (${message.length} chars > 65536 limit)")
            return
        }
        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            when (type) {
                "offer" -> handleOffer(conn, json)
                "takeover" -> handleTakeover(conn, json)
                "takeover-response" -> handleTakeoverResponse(conn, json)
                "ice-candidate" -> handleIceCandidate(conn, json)
                "source-switch" -> {
                    val sourceName = json.optString("sourceName", "Tela").take(64)
                    val sourceKind = json.optString("sourceKind", "screen")
                    listener?.onSourceSwitchReceived(sourceName, sourceKind == "window")
                }
                "stream-pause", "remote-pause" -> {
                    val paused = json.optBoolean("paused", false)
                    listener?.onStreamPauseReceived(paused)
                }
                "image-mode" -> {
                    val mode = json.optString("mode", "motion")
                    listener?.onImageModeChanged(mode)
                }
                "video-profile" -> {
                    val preset = json.optString("preset", "custom").take(32)
                    val label = json.optString("label", preset).take(24)
                    val name = json.optString("name", label).take(64)
                    listener?.onVideoProfileChanged(preset, label, name)
                }
                "latency-mode" -> {
                    val mode = json.optString("mode", "ultra").take(32)
                    listener?.onLatencyModeChanged(mode)
                }
                "clock-config" -> {
                    val enabled = json.optBoolean("enabled", false)
                    val corner = json.optString("corner", "br").take(8)
                    listener?.onClockConfigChanged(enabled, corner)
                }
                "bye" -> handleBye(conn)
                else -> {
                    Log.w(TAG, "Received unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming WebSocket message: $message", e)
        }
    }

    private fun handleTakeover(conn: WebSocket, json: JSONObject) {
        val newSenderName = json.optString("senderName", "Novo Dispositivo").take(64)
        initiateOrExecuteTakeover(conn, newSenderName, pendingOfferSdp = null, pendingLatencyMode = null)
    }

    private fun handleTakeoverResponse(conn: WebSocket, json: JSONObject) {
        val requestId = json.optString("requestId", "")
        val accepted = json.optBoolean("accepted", false)
        if (requestId.isBlank()) return

        when (val result = takeoverCoordinator.resolveResponse(conn, requestId, accepted)) {
            is TakeoverCoordinator.ResolveResult.Approved -> {
                promotePendingTakeover(result.request, timedOut = false)
            }
            is TakeoverCoordinator.ResolveResult.Denied -> {
                val activeWho = result.request.currentSenderName.ifBlank { "dispositivo atual" }
                sendStatus(
                    result.request.challenger,
                    state = "takeover-denied",
                    message = "$activeWho recusou transferir a TV.",
                    activeSender = activeWho
                )
                listener?.onTakeoverDenied(result.request.challengerName, activeWho)
            }
            is TakeoverCoordinator.ResolveResult.Ignored -> Unit
        }
    }

    private fun initiateOrExecuteTakeover(
        conn: WebSocket,
        newSenderName: String,
        pendingOfferSdp: String?,
        pendingLatencyMode: String?
    ) {
        var startedRequest: TakeoverCoordinator.BeginResult<WebSocket>? = null
        var activeWho = "outro dispositivo"
        var previousClient: WebSocket? = null

        synchronized(this) {
            val current = activeSessionClient
            if (current != null && current.isOpen && current != conn) {
                previousClient = current
                activeWho = activeSenderName.ifBlank { "outro dispositivo" }
                startedRequest = takeoverCoordinator.beginTakeover(
                    currentClient = current,
                    currentSenderName = activeWho,
                    challenger = conn,
                    challengerName = newSenderName,
                    pendingOfferSdp = pendingOfferSdp,
                    pendingLatencyMode = pendingLatencyMode
                ) { reqId ->
                    val expired = takeoverCoordinator.expireTimeout(reqId)
                    if (expired is TakeoverCoordinator.ResolveResult.Approved) {
                        promotePendingTakeover(expired.request, timedOut = true)
                    }
                }
            } else {
                activeSessionClient = conn
                activeSenderName = newSenderName
            }
        }

        val pending = startedRequest
        val targetCurrent = previousClient
        if (pending != null && targetCurrent != null) {
            pending.replacedPrevious?.let { displaced ->
                sendStatus(
                    displaced.challenger,
                    state = "takeover-denied",
                    message = "Outra solicitação de conexão substituiu a sua.",
                    activeSender = activeWho
                )
            }

            val reqJson = JSONObject().apply {
                put("type", "takeover-request")
                put("requestId", pending.request.requestId)
                put("requesterName", newSenderName)
                put("timeoutSec", (TakeoverCoordinator.DEFAULT_TIMEOUT_MS / 1000L).toInt())
                put("message", "$newSenderName quer assumir a TV.")
            }
            try {
                targetCurrent.send(reqJson.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify current client for takeover, promoting immediately: ${e.message}")
                val immediate = takeoverCoordinator.resolveResponse(targetCurrent, pending.request.requestId, true)
                if (immediate is TakeoverCoordinator.ResolveResult.Approved) {
                    promotePendingTakeover(immediate.request, timedOut = false)
                }
                return
            }

            sendStatus(
                conn,
                state = "takeover-pending",
                message = "Aguardando $activeWho aceitar (troca automática em 15s)...",
                activeSender = activeWho,
                requestId = pending.request.requestId,
                timeoutSec = (TakeoverCoordinator.DEFAULT_TIMEOUT_MS / 1000L).toInt()
            )
            listener?.onTakeoverRequested(newSenderName, activeWho)
            return
        }

        if (pendingOfferSdp != null) {
            dispatchAcceptedOffer(conn, pendingOfferSdp, newSenderName, pendingLatencyMode)
        } else {
            sendStatus(conn, state = "ready", message = "Assumindo TV para $newSenderName")
        }
    }

    private fun promotePendingTakeover(
        request: TakeoverCoordinator.PendingRequest<WebSocket>,
        timedOut: Boolean
    ) {
        synchronized(dispatchOrderLock) {
            if (!request.challenger.isOpen) {
                sendTakeoverCancelled(request.currentClient, request.requestId)
                return
            }

            val reason = if (timedOut) {
                "${request.challengerName} assumiu a TV após 15s sem resposta."
            } else {
                "Você transferiu a TV para ${request.challengerName}."
            }

            synchronized(this) {
                if (activeSessionClient == request.currentClient && request.currentClient.isOpen) {
                    sendPreemptedAndClose(request.currentClient, request.challengerName, reason)
                }
                activeSessionClient = request.challenger
                activeSenderName = request.challengerName
            }

            listener?.onSessionTakeover(request.challengerName)
            if (!request.pendingOfferSdp.isNullOrBlank()) {
                dispatchAcceptedOffer(
                    request.challenger,
                    request.pendingOfferSdp,
                    request.challengerName,
                    request.pendingLatencyMode
                )
            } else {
                sendStatus(request.challenger, state = "ready", message = "Assumindo TV para ${request.challengerName}")
            }
        }
    }

    private fun sendTakeoverCancelled(target: WebSocket, requestId: String) {
        if (!target.isOpen) return
        try {
            val payload = JSONObject().apply {
                put("type", "takeover-cancelled")
                put("requestId", requestId)
            }
            target.send(payload.toString())
        } catch (_: Exception) {
        }
    }

    private fun handleSocketClosed(conn: WebSocket, reason: String, isBye: Boolean) {
        synchronized(dispatchOrderLock) {
            var promoteReq: TakeoverCoordinator.PendingRequest<WebSocket>? = null
            var notifySessionLost = false

            synchronized(this) {
                when (val effect = takeoverCoordinator.handleClientClosed(conn)) {
                    is TakeoverCoordinator.CloseEffect.AutoPromote -> {
                        if (effect.request.challenger.isOpen) {
                            promoteReq = effect.request
                            activeSessionClient = effect.request.challenger
                            activeSenderName = effect.request.challengerName
                        } else if (conn == activeSessionClient) {
                            activeSessionClient = null
                            activeSenderName = ""
                            notifySessionLost = true
                        }
                    }
                    is TakeoverCoordinator.CloseEffect.ChallengerCancelled -> {
                        sendTakeoverCancelled(effect.request.currentClient, effect.request.requestId)
                    }
                    is TakeoverCoordinator.CloseEffect.None -> Unit
                }

                if (promoteReq == null && !notifySessionLost && conn == activeSessionClient) {
                    activeSessionClient = null
                    activeSenderName = ""
                    notifySessionLost = true
                }
            }

            val promoted = promoteReq
            if (promoted != null) {
                listener?.onSessionTakeover(promoted.challengerName)
                if (!promoted.pendingOfferSdp.isNullOrBlank()) {
                    dispatchAcceptedOffer(
                        promoted.challenger,
                        promoted.pendingOfferSdp,
                        promoted.challengerName,
                        promoted.pendingLatencyMode
                    )
                } else {
                    sendStatus(promoted.challenger, state = "ready", message = "Assumindo TV para ${promoted.challengerName}")
                }
                return
            }

            if (notifySessionLost) {
                if (isBye) {
                    listener?.onByeReceived()
                } else {
                    listener?.onSessionDisconnected(reason)
                }
            }
        }
    }

    private fun sendPreemptedAndClose(
        previous: WebSocket,
        newSenderName: String,
        customMessage: String? = null
    ) {
        if (!previous.isOpen) return
        val msg = customMessage ?: "$newSenderName assumiu a TV."
        try {
            val preemptJson = JSONObject().apply {
                put("type", "preempted")
                put("by", newSenderName)
                put("message", msg)
            }
            previous.send(preemptJson.toString())
            sendStatus(
                previous,
                state = "preempted",
                message = msg,
                activeSender = newSenderName
            )
            previous.close(1000, "Preempted")
        } catch (e: Exception) {
            Log.w(TAG, "Error Closing preempted socket: ${e.message}")
        }
    }

    private fun handleOffer(conn: WebSocket, json: JSONObject) {
        val sdp = json.getString("sdp")
        val senderName = json.optString("senderName", "Dispositivo Desconhecido").take(64)
        val latencyMode = json.optString("latencyMode", "").take(32)
        val forceTakeover = json.optBoolean("forceTakeover", false)

        synchronized(dispatchOrderLock) {
            var busyWith: String? = null
            var needsTakeoverPrompt = false

            synchronized(this) {
                val current = activeSessionClient
                if (current != null && current.isOpen && current != conn) {
                    if (!forceTakeover) {
                        busyWith = activeSenderName.ifBlank { "outro dispositivo" }
                    } else {
                        needsTakeoverPrompt = true
                    }
                } else {
                    activeSessionClient = conn
                    activeSenderName = senderName
                    Log.i(TAG, "Accepted offer from sender: '$senderName' (forceTakeover=$forceTakeover)")
                }
            }

            if (busyWith != null) {
                val activeWho = busyWith!!
                Log.w(TAG, "Rejecting offer from $senderName. Session is busy with $activeWho.")
                sendStatus(
                    conn,
                    state = "busy",
                    message = "Já conectado em outro dispositivo ($activeWho)",
                    activeSender = activeWho
                )
                listener?.onRejectedBusyAttempt(senderName, activeWho)
                return
            }

            if (needsTakeoverPrompt) {
                initiateOrExecuteTakeover(conn, senderName, pendingOfferSdp = sdp, pendingLatencyMode = latencyMode)
                return
            }

            dispatchAcceptedOffer(conn, sdp, senderName, latencyMode)
        }
    }

    private fun dispatchAcceptedOffer(
        conn: WebSocket,
        sdp: String,
        senderName: String,
        latencyMode: String?
    ) {
        sendStatus(conn, state = "streaming", message = "Iniciando recepção de $senderName")
        if (!latencyMode.isNullOrBlank()) {
            listener?.onLatencyModeChanged(latencyMode)
        }
        listener?.onOfferReceived(sdp, senderName)
    }

    private fun handleIceCandidate(conn: WebSocket, json: JSONObject) {
        synchronized(dispatchOrderLock) {
            synchronized(this) {
                if (conn != activeSessionClient) {
                    Log.w(TAG, "Ignoring ICE candidate from non-active client")
                    return
                }
            }

            // PROTOCOL.md candidate object:
            // { "type": "ice-candidate", "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 } }
            val candidateObj = json.optJSONObject("candidate")
            val candidateStr = candidateObj?.optString("candidate") ?: json.optString("candidate")
            val sdpMid = candidateObj?.optString("sdpMid") ?: json.optString("sdpMid", "0")
            val sdpMLineIndex = candidateObj?.optInt("sdpMLineIndex", 0) ?: json.optInt("sdpMLineIndex", 0)

            if (candidateStr.isNotBlank()) {
                listener?.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidateStr)
            }
        }
    }

    private fun handleBye(conn: WebSocket) {
        Log.i(TAG, "Received BYE from client")
        handleSocketClosed(conn, "Cliente desconectou", isBye = true)
    }

    /**
     * Sends SDP Answer to the active client.
     */
    fun sendAnswer(sdp: String) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        }
        sendToActiveClient(json.toString())
    }

    /**
     * Sends an ICE Candidate to the active client.
     */
    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val candObj = JSONObject().apply {
            put("candidate", candidate)
            put("sdpMid", sdpMid ?: "0")
            put("sdpMLineIndex", sdpMLineIndex)
        }
        val json = JSONObject().apply {
            put("type", "ice-candidate")
            put("candidate", candObj)
        }
        sendToActiveClient(json.toString())
    }

    /**
     * Sends remote Play/Pause toggle state initiated by the Fire TV remote to the active PC sender.
     */
    fun sendRemotePauseState(paused: Boolean) {
        val json = JSONObject().apply {
            put("type", "remote-pause")
            put("paused", paused)
        }
        sendToActiveClient(json.toString())
    }

    /**
     * Sends a status update message to a specific connection or active client.
     */
    fun sendStatus(
        target: WebSocket?,
        state: String,
        message: String,
        activeSender: String? = null,
        requestId: String? = null,
        timeoutSec: Int? = null
    ) {
        try {
            val json = JSONObject().apply {
                put("type", "status")
                put("state", state)
                put("message", message)
                if (!activeSender.isNullOrBlank()) {
                    put("activeSender", activeSender)
                }
                if (!requestId.isNullOrBlank()) {
                    put("requestId", requestId)
                }
                if (timeoutSec != null) {
                    put("timeoutSec", timeoutSec)
                }
            }
            if (target != null && target.isOpen) {
                target.send(json.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending status message", e)
        }
    }

    /**
     * Sends BYE to active client and resets current session.
     */
    fun disconnectActiveSession(reason: String = "Sessão encerrada") {
        synchronized(this) {
            takeoverCoordinator.cancelAll()
            val client = activeSessionClient
            if (client != null && client.isOpen) {
                try {
                    val byeJson = JSONObject().apply {
                        put("type", "bye")
                    }
                    client.send(byeJson.toString())
                    sendStatus(client, state = "ready", message = reason)
                    client.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error cleanly closing client socket: ${e.message}")
                }
            }
            activeSessionClient = null
            activeSenderName = ""
        }
    }

    private fun sendToActiveClient(message: String) {
        synchronized(this) {
            val client = activeSessionClient
            if (client != null && client.isOpen) {
                try {
                    client.send(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending payload to active client", e)
                }
            } else {
                Log.w(TAG, "Cannot send message, activeSessionClient is null or closed")
            }
        }
    }

    fun isSessionActive(): Boolean {
        return activeSessionClient?.isOpen == true
    }
}
