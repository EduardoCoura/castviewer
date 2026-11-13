package com.firecast.local

import java.util.UUID

/**
 * Stateful, lock-safe coordinator for 15-second mutual takeover approval.
 * When a challenger asks to assume the TV while an active sender is streaming,
 * the active sender receives a confirmation prompt and has 15s to Accept or Refuse.
 * If the old sender does not answer before the timeout (e.g. forgotten device),
 * ownership transfers automatically to the challenger.
 */
class TakeoverCoordinator<T : Any>(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val scheduler: Scheduler = NoOpScheduler
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 15_000L
    }

    interface Cancellable {
        fun cancel()
    }

    interface Scheduler {
        fun schedule(delayMs: Long, action: Runnable): Cancellable
    }

    object NoOpScheduler : Scheduler {
        override fun schedule(delayMs: Long, action: Runnable): Cancellable = object : Cancellable {
            override fun cancel() {}
        }
    }

    data class PendingRequest<T : Any>(
        val requestId: String,
        val currentClient: T,
        val currentSenderName: String,
        val challenger: T,
        val challengerName: String,
        val pendingOfferSdp: String? = null,
        val pendingLatencyMode: String? = null
    )

    data class BeginResult<T : Any>(
        val request: PendingRequest<T>,
        val replacedPrevious: PendingRequest<T>? = null
    )

    sealed class ResolveResult<T : Any> {
        data class Approved<T : Any>(val request: PendingRequest<T>, val timedOut: Boolean = false) : ResolveResult<T>()
        data class Denied<T : Any>(val request: PendingRequest<T>) : ResolveResult<T>()
        class Ignored<T : Any> : ResolveResult<T>()
    }

    sealed class CloseEffect<T : Any> {
        data class AutoPromote<T : Any>(val request: PendingRequest<T>) : CloseEffect<T>()
        data class ChallengerCancelled<T : Any>(val request: PendingRequest<T>) : CloseEffect<T>()
        class None<T : Any> : CloseEffect<T>()
    }

    private var activePending: PendingRequest<T>? = null
    private var activeTimer: Cancellable? = null

    fun beginTakeover(
        currentClient: T,
        currentSenderName: String,
        challenger: T,
        challengerName: String,
        pendingOfferSdp: String? = null,
        pendingLatencyMode: String? = null,
        onTimeoutElapsed: (requestId: String) -> Unit
    ): BeginResult<T> {
        val replaced = synchronized(this) {
            val prev = activePending
            activeTimer?.cancel()
            activeTimer = null

            val request = PendingRequest(
                requestId = UUID.randomUUID().toString(),
                currentClient = currentClient,
                currentSenderName = currentSenderName,
                challenger = challenger,
                challengerName = challengerName,
                pendingOfferSdp = pendingOfferSdp,
                pendingLatencyMode = pendingLatencyMode
            )
            activePending = request
            activeTimer = scheduler.schedule(timeoutMs, Runnable {
                onTimeoutElapsed(request.requestId)
            })
            val previousChallenger = if (prev != null && prev.challenger != challenger) prev else null
            BeginResult(request = request, replacedPrevious = previousChallenger)
        }
        return replaced
    }

    fun resolveResponse(responder: T, requestId: String, accepted: Boolean): ResolveResult<T> {
        return synchronized(this) {
            val pending = activePending ?: return@synchronized ResolveResult.Ignored()
            if (pending.currentClient != responder || pending.requestId != requestId) {
                return@synchronized ResolveResult.Ignored()
            }
            activePending = null
            activeTimer?.cancel()
            activeTimer = null
            if (accepted) {
                ResolveResult.Approved(pending, timedOut = false)
            } else {
                ResolveResult.Denied(pending)
            }
        }
    }

    fun expireTimeout(requestId: String): ResolveResult<T> {
        return synchronized(this) {
            val pending = activePending ?: return@synchronized ResolveResult.Ignored()
            if (pending.requestId != requestId) {
                return@synchronized ResolveResult.Ignored()
            }
            activePending = null
            activeTimer = null
            ResolveResult.Approved(pending, timedOut = true)
        }
    }

    fun handleClientClosed(client: T): CloseEffect<T> {
        return synchronized(this) {
            val pending = activePending ?: return@synchronized CloseEffect.None()
            when (client) {
                pending.currentClient -> {
                    activePending = null
                    activeTimer?.cancel()
                    activeTimer = null
                    CloseEffect.AutoPromote(pending)
                }
                pending.challenger -> {
                    activePending = null
                    activeTimer?.cancel()
                    activeTimer = null
                    CloseEffect.ChallengerCancelled(pending)
                }
                else -> CloseEffect.None()
            }
        }
    }

    fun cancelAll() {
        synchronized(this) {
            activePending = null
            activeTimer?.cancel()
            activeTimer = null
        }
    }
}
