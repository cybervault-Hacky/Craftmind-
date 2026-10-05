package com.craftmind.app.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun eachThemePreferenceRoundTripsThroughItsStoredValue() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorageValue(mode.storageValue))
        }
    }

    @Test
    fun missingOrUnknownPreferenceSafelyFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageValue("unset"))
    }
}
