package com.solarmonitor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiSetupTest {
    @Test fun scanning() = assertNull(WifiSetup.parseScan("""{"scanning":true}"""))

    @Test fun strongestFirstNoRepeatsNoHidden() {
        val n = WifiSetup.parseScan("""{"nets":[{"ssid":"A","rssi":-80,"open":false},{"ssid":"B","rssi":-50,"open":true},{"ssid":"A","rssi":-60,"open":false},{"ssid":"","rssi":-40,"open":false}]}""")!!
        assertEquals(listOf("B", "A"), n.map { it.ssid })
        assertEquals(-60, n[1].rssi)
        assertEquals("strong", n[0].signal)
        assertEquals("good", n[1].signal)
    }

    @Test fun form() {
        assertNotNull(WifiSetup.formError("", "password1", false, "x"))
        assertNotNull(WifiSetup.formError("N", "short", false, "x"))
        assertNotNull(WifiSetup.formError("N", "password1", false, ""))
        assertNotNull(WifiSetup.formError("N".repeat(33), "password1", false, "x"))
        assertNotNull(WifiSetup.formError("N", "p".repeat(64), false, "x"))
        assertNull(WifiSetup.formError("N", "password1", false, "x"))
        assertNull(WifiSetup.formError("N", "", true, "x"))
        assertNull(WifiSetup.formError("N", "", false, "x"))   // typed name of an open network
    }

    @Test fun joined() {
        assertEquals(true to "10.0.0.5", WifiSetup.joined("""{"connected":true,"ssid":"N","ip":"10.0.0.5"}""", "N"))
        assertEquals(false, WifiSetup.joined("""{"connected":true,"ssid":"Old","ip":"10.0.0.5"}""", "N").first)
        assertEquals(false, WifiSetup.joined("""{"connected":false,"ssid":"N","ip":"0.0.0.0"}""", "N").first)
    }
}
