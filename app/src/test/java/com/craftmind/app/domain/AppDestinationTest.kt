package com.craftmind.app.domain

import com.craftmind.app.core.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDestinationTest {
    @Test
    fun routesResolveToTheirTopLevelDestination() {
        assertEquals(AppDestination.HOME, AppDestination.fromRoute("home"))
        assertEquals(AppDestination.BUILDS, AppDestination.fromRoute("builds"))
        assertEquals(AppDestination.SETTINGS, AppDestination.fromRoute("settings"))
    }

    @Test
    fun nestedRouteKeepsItsParentSelectedAndUnknownRouteIsNotMisclassified() {
        assertEquals(AppDestination.BUILDS, AppDestination.fromRoute("builds/detail/7"))
        assertNull(AppDestination.fromRoute("settings-extra"))
        assertNull(AppDestination.fromRoute(null))
    }
}
