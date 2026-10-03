package com.craftmind.app.domain

import com.craftmind.app.domain.settings.AppearanceMode
import com.craftmind.app.domain.settings.resolveDarkTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceModeTest {
    @Test
    fun systemModeFollowsTheDeviceWhileExplicitModesDoNot() {
        assertTrue(AppearanceMode.SYSTEM.resolveDarkTheme(systemIsDark = true))
        assertFalse(AppearanceMode.SYSTEM.resolveDarkTheme(systemIsDark = false))
        assertTrue(AppearanceMode.DARK.resolveDarkTheme(systemIsDark = false))
        assertFalse(AppearanceMode.LIGHT.resolveDarkTheme(systemIsDark = true))
    }

    @Test
    fun unknownStoredValueSafelyFallsBackToSystem() {
        assertTrue(AppearanceMode.fromStorage("unexpected").resolveDarkTheme(systemIsDark = true))
    }
}
