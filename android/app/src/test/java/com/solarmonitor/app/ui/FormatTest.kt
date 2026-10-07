package com.solarmonitor.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun watts() {
        assertEquals("0 W", fmtW(0))
        assertEquals("749 W", fmtW(749))
        assertEquals("-83 W", fmtW(-83))
        assertEquals("1.10 kW", fmtW(1100))
        assertEquals("12.5 kW", fmtW(12500))
    }

    @Test fun energy() {
        assertEquals("999 Wh", fmtWh(999))
        assertEquals("1.20 kWh", fmtWh(1200))
        assertEquals("12.3 kWh", fmtWh(12300))
        assertEquals("123 kWh", fmtWh(123_000))
    }

    @Test fun durations() {
        assertEquals("0m", fmtDuration(0.0))
        assertEquals("59m", fmtDuration(59.0))
        assertEquals("1h 00m", fmtDuration(60.0))
        assertEquals("26h 05m", fmtDuration(26 * 60 + 5.0))
    }

    @Test fun dates() {
        assertEquals(20261007, ymd(dateOf(20261007)))
        assertEquals(20260301, ymd(dateOf(20260228).plusDays(1)))
        assertEquals(20280229, ymd(dateOf(20280228).plusDays(1)))
    }
}
