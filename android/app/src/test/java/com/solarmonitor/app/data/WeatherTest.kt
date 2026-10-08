package com.solarmonitor.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as the web's weather.test.ts. */
class WeatherTest {
    @Test fun skyFromCodes() {
        assertEquals(Weather.Sky.Clear, Weather.skyOf(0, 5))
        assertEquals(Weather.Sky.Partly, Weather.skyOf(2, 40))
        assertEquals(Weather.Sky.Cloudy, Weather.skyOf(3, 100))
        assertEquals(Weather.Sky.Cloudy, Weather.skyOf(1, 80))
        assertEquals(Weather.Sky.Rain, Weather.skyOf(61, 90))
        assertEquals(Weather.Sky.Rain, Weather.skyOf(95, 90))
    }
}
