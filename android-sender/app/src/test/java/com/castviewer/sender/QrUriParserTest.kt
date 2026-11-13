package com.castviewer.sender.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrUriParserTest {

    @Test
    fun parsesRotatingFireStickQrUri() {
        val parsed = QrUriParser.parseFireStickTarget("ws://192.168.1.50:8765?t=982341")
        assertEquals("192.168.1.50", parsed?.ip)
        assertEquals(8765, parsed?.port)
    }

    @Test
    fun parsesBareIpv4WithDefaultPort() {
        val parsed = QrUriParser.parseFireStickTarget(" 10.0.0.15 ")
        assertEquals("10.0.0.15", parsed?.ip)
        assertEquals(8765, parsed?.port)
    }

    @Test
    fun parsesHostAndCustomPort() {
        val parsed = QrUriParser.parseFireStickTarget("192.168.15.22:9001")
        assertEquals("192.168.15.22", parsed?.ip)
        assertEquals(9001, parsed?.port)
    }

    @Test
    fun rejectsInvalidTargetsAndOutOfRangeOctets() {
        assertFalse(QrUriParser.isValidIpv4("256.168.1.1"))
        assertFalse(QrUriParser.isValidIpv4("192.168.1"))
        assertTrue(QrUriParser.isValidIpv4("192.168.0.105"))

        assertNull(QrUriParser.parseFireStickTarget(""))
        assertNull(QrUriParser.parseFireStickTarget("https://google.com"))
        assertNull(QrUriParser.parseFireStickTarget("ws://999.1.1.1:8765"))
        assertNull(QrUriParser.parseFireStickTarget("192.168.1.1:99999"))
    }
}
