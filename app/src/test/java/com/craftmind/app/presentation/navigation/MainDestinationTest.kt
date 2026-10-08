package com.craftmind.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Phase 15 §4: navigation is Home, Builds, Minecraft, Settings — in that order, with Home primary.
     *
     * Locking the information architecture in a test is deliberate. A later phase that needs a new top-level
     * destination must change this expectation consciously instead of quietly adding a fifth tab.
     */
    @Test
    fun navigationIsExactlyTheFourAgreedDestinationsInOrder() {
        assertEquals(
            listOf(
                MainDestination.HOME,
                MainDestination.BUILDS,
                MainDestination.MINECRAFT,
                MainDestination.SETTINGS,
            ),
            MainDestination.entries.toList(),
        )
        assertEquals(MainDestination.HOME, MainDestination.PRIMARY)
        assertEquals(0, MainDestination.PRIMARY.ordinal)
    }

    @Test
    fun everyDestinationHasAUniqueLowercaseRouteAndAStatedPurpose() {
        val routes = MainDestination.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
        MainDestination.entries.forEach { destination ->
            assertTrue(
                "${destination.name} route must be lowercase and free of separators",
                destination.route == destination.route.lowercase() &&
                    destination.route.none { it.isWhitespace() || it == '_' || it == '-' },
            )
            assertTrue("${destination.name} needs a navigation label", destination.label.isNotBlank())
            assertTrue(
                "${destination.name} label must stay short enough for a navigation item",
                destination.label.length <= 12,
            )
            assertTrue("${destination.name} must state its purpose", destination.purpose.isNotBlank())
            assertTrue(
                "${destination.name} purpose must be a single sentence, not a paragraph",
                destination.purpose.length <= 110 && destination.purpose.endsWith("."),
            )
        }
    }
}
