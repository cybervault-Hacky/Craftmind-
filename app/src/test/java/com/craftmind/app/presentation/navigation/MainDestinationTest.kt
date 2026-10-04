package com.craftmind.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainDestinationTest {
    @Test
    fun everyPrimaryNavigationDestinationResolvesByRoute() {
        MainDestination.entries.forEach { destination ->
            assertEquals(destination, MainDestination.fromRoute(destination.route))
        }
    }

    @Test
    fun unknownOrMissingRouteFallsBackToHome() {
        assertEquals(MainDestination.HOME, MainDestination.fromRoute("not-a-destination"))
        assertEquals(MainDestination.HOME, MainDestination.fromRoute(null))
    }
}
