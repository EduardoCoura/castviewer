package com.castviewer.sender

import com.castviewer.sender.model.CastConnectionState
import com.castviewer.sender.model.CastStateStore
import com.castviewer.sender.model.DeviceSource
import com.castviewer.sender.model.FireStickDevice
import com.castviewer.sender.model.IncomingTakeoverRequest
import com.castviewer.sender.model.KineticAnimType
import com.castviewer.sender.model.PerformancePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TakeoverAndAnimationStateTest {

    @Before
    fun resetStore() {
        CastStateStore.resetSessionToIdle()
        CastStateStore.updatePrefs(PerformancePrefs())
        CastStateStore.session.value.kineticAnim?.let {
            CastStateStore.clearKineticAnimation(it.id)
        }
    }

    @Test
    fun emitKineticAnimation_assignsMonotonicIds_andClearsOnlyMatchingId() {
        val first = CastStateStore.emitKineticAnimation(
            type = KineticAnimType.COLD_INTRO,
            badge = "SINAL PRONTO",
            title = "CASTVIEWER",
            subtitle = "Conexão Direta"
        )
        val second = CastStateStore.emitKineticAnimation(
            type = KineticAnimType.CAST_CONNECT,
            badge = "CONEXÃO DIRETA",
            title = "Transmitindo para Fire Stick",
            subtitle = "1080p 60 • Ultra Low"
        )

        assertTrue(second!!.id > first!!.id)
        assertEquals(second, CastStateStore.session.value.kineticAnim)

        // Stale dismiss for previous event must not clobber newer animation
        CastStateStore.clearKineticAnimation(first.id)
        assertNotNull(CastStateStore.session.value.kineticAnim)
        assertEquals(second.id, CastStateStore.session.value.kineticAnim?.id)

        CastStateStore.clearKineticAnimation(second.id)
        assertNull(CastStateStore.session.value.kineticAnim)
    }

    @Test
    fun animationsDisabled_blocksNewEmissions_andClearsPendingAnimation() {
        val pending = CastStateStore.emitKineticAnimation(
            type = KineticAnimType.COLD_INTRO,
            badge = "SINAL PRONTO",
            title = "CASTVIEWER",
            subtitle = "Conexão Direta"
        )
        assertNotNull(pending)
        assertNotNull(CastStateStore.session.value.kineticAnim)

        CastStateStore.updatePrefs(CastStateStore.prefs.value.copy(animationsEnabled = false))
        assertNull(CastStateStore.session.value.kineticAnim)

        val blocked = CastStateStore.emitKineticAnimation(
            type = KineticAnimType.CAST_CONNECT,
            badge = "CONEXÃO DIRETA",
            title = "Transmitindo",
            subtitle = "Teste"
        )
        assertNull(blocked)
        assertNull(CastStateStore.session.value.kineticAnim)
    }

    @Test
    fun normalizeClockCorner_acceptsFourCorners_andFallsBackToBottomRight() {
        assertEquals("tl", PerformancePrefs.normalizeClockCorner("TL"))
        assertEquals("tr", PerformancePrefs.normalizeClockCorner(" tr "))
        assertEquals("bl", PerformancePrefs.normalizeClockCorner("bl"))
        assertEquals("br", PerformancePrefs.normalizeClockCorner("br"))
        assertEquals("br", PerformancePrefs.normalizeClockCorner("center"))
        assertEquals("br", PerformancePrefs.normalizeClockCorner(null))
    }

    @Test
    fun incomingTakeover_setsAndClearsByMatchingRequestId_andClearsOnReset() {
        val req1 = IncomingTakeoverRequest(
            requestId = "req-101",
            requesterName = "PC-Sala",
            timeoutSec = 15,
            expiresAtMs = System.currentTimeMillis() + 15_000L
        )
        CastStateStore.setIncomingTakeover(req1)
        assertEquals(req1, CastStateStore.session.value.incomingTakeover)

        // Mismatched cancellation/response should not erase active request
        CastStateStore.clearIncomingTakeover("req-wrong")
        assertNotNull(CastStateStore.session.value.incomingTakeover)

        CastStateStore.clearIncomingTakeover("req-101")
        assertNull(CastStateStore.session.value.incomingTakeover)

        val req2 = IncomingTakeoverRequest(
            requestId = "req-202",
            requesterName = "Galaxy-Note",
            timeoutSec = 15,
            expiresAtMs = System.currentTimeMillis() + 15_000L
        )
        CastStateStore.setIncomingTakeover(req2)
        CastStateStore.resetSessionToIdle("Sessão encerrada")
        assertEquals(CastConnectionState.IDLE, CastStateStore.session.value.connectionState)
        assertNull(CastStateStore.session.value.incomingTakeover)
    }

    @Test
    fun markBusyOrPreempted_preservesTakeoverTargetAndClearsExplicitly() {
        val tv = FireStickDevice(
            id = "fire-1",
            name = "Fire TV Sala",
            ip = "192.168.0.2",
            port = 8765,
            source = DeviceSource.MDNS,
            isOnline = true
        )
        CastStateStore.markBusyOrPreempted(
            device = tv,
            activeSender = "DESKTOP-PC",
            detailMessage = null
        )

        val state = CastStateStore.session.value
        assertEquals(tv, state.busyHostDevice)
        assertEquals("DESKTOP-PC", state.busyActiveSender)
        assertTrue(state.statusMessage.contains("Assumir TV"))

        CastStateStore.clearBusyHostBanner()
        assertNull(CastStateStore.session.value.busyHostDevice)
        assertNull(CastStateStore.session.value.busyActiveSender)
    }
}
