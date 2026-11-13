package com.firecast.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverCoordinatorTest {

    private class FakeScheduler : TakeoverCoordinator.Scheduler {
        var lastDelayMs: Long = -1L
        var lastAction: Runnable? = null
        var cancelledCount: Int = 0

        override fun schedule(delayMs: Long, action: Runnable): TakeoverCoordinator.Cancellable {
            lastDelayMs = delayMs
            lastAction = action
            var cancelled = false
            return object : TakeoverCoordinator.Cancellable {
                override fun cancel() {
                    if (!cancelled) {
                        cancelled = true
                        cancelledCount++
                    }
                }
            }
        }

        fun fireLast() {
            lastAction?.run()
        }
    }

    @Test
    fun acceptByCurrentSenderApprovesChallengerAndCancelsTimer() {
        val scheduler = FakeScheduler()
        val coordinator = TakeoverCoordinator<String>(15_000L, scheduler)

        val begin = coordinator.beginTakeover(
            currentClient = "pc-1",
            currentSenderName = "PC Gamer",
            challenger = "phone-1",
            challengerName = "Galaxy A30s"
        ) {}

        assertEquals(15_000L, scheduler.lastDelayMs)
        val res = coordinator.resolveResponse("pc-1", begin.request.requestId, accepted = true)
        assertTrue(res is TakeoverCoordinator.ResolveResult.Approved)
        assertEquals(false, (res as TakeoverCoordinator.ResolveResult.Approved).timedOut)
        assertEquals("phone-1", res.request.challenger)
        assertEquals(1, scheduler.cancelledCount)
    }

    @Test
    fun denyByCurrentSenderRejectsChallengerAndKeepsSession() {
        val scheduler = FakeScheduler()
        val coordinator = TakeoverCoordinator<String>(15_000L, scheduler)

        val begin = coordinator.beginTakeover(
            currentClient = "pc-1",
            currentSenderName = "PC",
            challenger = "phone-2",
            challengerName = "Celular"
        ) {}

        val res = coordinator.resolveResponse("pc-1", begin.request.requestId, accepted = false)
        assertTrue(res is TakeoverCoordinator.ResolveResult.Denied)
        assertEquals(1, scheduler.cancelledCount)

        // Subsequent timeout fire must be ignored
        val expiredAfterDeny = coordinator.expireTimeout(begin.request.requestId)
        assertTrue(expiredAfterDeny is TakeoverCoordinator.ResolveResult.Ignored)
    }

    @Test
    fun timeoutAutoApprovesChallengerForForgottenDevice() {
        val scheduler = FakeScheduler()
        val coordinator = TakeoverCoordinator<String>(15_000L, scheduler)
        var timedOutReqId: String? = null

        val begin = coordinator.beginTakeover(
            currentClient = "forgotten-pc",
            currentSenderName = "Notebook",
            challenger = "phone-1",
            challengerName = "Celular"
        ) { reqId ->
            timedOutReqId = reqId
        }

        scheduler.fireLast()
        assertEquals(begin.request.requestId, timedOutReqId)

        val timeoutResult = coordinator.expireTimeout(timedOutReqId!!)
        assertTrue(timeoutResult is TakeoverCoordinator.ResolveResult.Approved)
        assertTrue((timeoutResult as TakeoverCoordinator.ResolveResult.Approved).timedOut)
    }

    @Test
    fun spoofedOrWrongRequestIdResponseIsIgnored() {
        val scheduler = FakeScheduler()
        val coordinator = TakeoverCoordinator<String>(15_000L, scheduler)

        val begin = coordinator.beginTakeover("pc-1", "PC", "phone-1", "Celular") {}

        assertTrue(coordinator.resolveResponse("phone-1", begin.request.requestId, true) is TakeoverCoordinator.ResolveResult.Ignored)
        assertTrue(coordinator.resolveResponse("pc-1", "wrong-id", true) is TakeoverCoordinator.ResolveResult.Ignored)
    }

    @Test
    fun currentDisconnectMidWaitAutoPromotesChallengerAndChallengerCloseNotifiesCurrent() {
        val scheduler = FakeScheduler()
        val coordinator = TakeoverCoordinator<String>(15_000L, scheduler)

        coordinator.beginTakeover("pc-1", "PC", "phone-1", "Celular") {}
        val closeEffect1 = coordinator.handleClientClosed("phone-1")
        assertTrue(closeEffect1 is TakeoverCoordinator.CloseEffect.ChallengerCancelled)
        assertEquals("pc-1", (closeEffect1 as TakeoverCoordinator.CloseEffect.ChallengerCancelled).request.currentClient)
        assertEquals(1, scheduler.cancelledCount)

        coordinator.beginTakeover("pc-1", "PC", "phone-2", "Celular 2") {}
        val closeEffect2 = coordinator.handleClientClosed("pc-1")
        assertTrue(closeEffect2 is TakeoverCoordinator.CloseEffect.AutoPromote)
        assertEquals("phone-2", (closeEffect2 as TakeoverCoordinator.CloseEffect.AutoPromote).request.challenger)
        assertNull(coordinator.handleClientClosed("other").let {
            if (it is TakeoverCoordinator.CloseEffect.None) null else it
        })
        assertNotNull(closeEffect2)
    }
}
