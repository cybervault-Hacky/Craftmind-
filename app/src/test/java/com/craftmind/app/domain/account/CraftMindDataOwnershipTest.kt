package com.craftmind.app.domain.account

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 §6: data-ownership classification.
 *
 * The classification is only worth anything if it matches the code, so the `storedBy` of every domain is checked
 * against the real source tree: a domain whose owner type does not exist would be fiction, and the scan fails instead.
 */
class CraftMindDataOwnershipTest {
    @Test
    fun everyDataDomainHasExactlyOneExplicitOwnership() {
        val domains = CraftMindDataDomain.entries

        assertTrue("expected the CraftMind data domains to be classified", domains.size >= 8)
        assertEquals(
            "a domain may not be classified twice",
            domains.size,
            domains.map { it.name }.toSet().size,
        )
        for (domain in domains) {
            assertTrue("$domain has no summary", domain.summary.isNotBlank())
            assertTrue("$domain does not name the type that stores it", domain.storedBy.isNotBlank())
        }
    }

    @Test
    fun theFourOwnershipCategoriesAreNamedExplicitly() {
        assertEquals(
            setOf(
                "LOCAL_ONLY",
                "ACCOUNT_SCOPED_FUTURE",
                "SECRET_LOCAL_ONLY",
                "MINECRAFT_RUNTIME_LOCAL_ONLY",
            ),
            CraftMindDataOwnership.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun minecraftRuntimeDataIsClassifiedAsRuntimeLocalOnlyInThePresentAndTheFuture() {
        val pairing = CraftMindDataDomain.MINECRAFT_BRIDGE_PAIRING
        val executions = CraftMindDataDomain.BUILD_EXECUTION_HISTORY

        assertEquals(CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY, pairing.ownership)
        assertEquals(CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY, pairing.futureOwnership)
        assertEquals(CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY, executions.ownership)
        assertEquals(CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY, executions.futureOwnership)
        assertFalse(
            "runtime state must never be proposed for cloud sync",
            craftMindDomainsSyncableInFuture().any { it == pairing || it == executions },
        )
    }

    @Test
    fun nothingIsAccountScopedYetBecauseNoCloudFeatureExists() {
        for (domain in CraftMindDataDomain.entries) {
            assertFalse(
                "${domain.name} must not claim to be account-scoped before any cloud feature exists",
                domain.ownership == CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE,
            )
        }
    }

    @Test
    fun theDomainsThatWillBecomeAccountScopedInFutureAreNamedExplicitly() {
        val future = CraftMindDataDomain.entries
            .filter { it.futureOwnership == CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("BUILD_HISTORY", "BUILD_PLAN_VERSIONS", "APP_SETTINGS"),
            future,
        )
    }

    @Test
    fun theTwoSecretStoresAreSeparateNamespacesAndSeparateTypes() {
        val providerKeys = CraftMindDataDomain.AI_PROVIDER_CREDENTIALS
        val accountSession = CraftMindDataDomain.ACCOUNT_SESSION_CREDENTIALS

        assertEquals(CraftMindDataOwnership.SECRET_LOCAL_ONLY, providerKeys.ownership)
        assertEquals(CraftMindDataOwnership.SECRET_LOCAL_ONLY, accountSession.ownership)
        assertNotEquals(
            "an account session must never share a store with a provider API key",
            providerKeys.storedBy,
            accountSession.storedBy,
        )
    }

    @Test
    fun theStoresAndSecretsStayLocalEvenAfterCloudFeaturesArrive() {
        val secrets = craftMindSecretDomains()

        assertEquals(
            setOf(
                CraftMindDataDomain.AI_PROVIDER_CREDENTIALS,
                CraftMindDataDomain.ACCOUNT_SESSION_CREDENTIALS,
            ),
            secrets.toSet(),
        )
        for (domain in secrets) {
            assertEquals(
                "${domain.name} must stay secret and local in both classifications",
                CraftMindDataOwnership.SECRET_LOCAL_ONLY,
                domain.futureOwnership,
            )
        }
    }

    @Test
    fun onlyTheLocalDomainsAreEverCandidatesForCloudSync() {
        val syncable = craftMindDomainsSyncableInFuture().map { it.name }.toSet()

        assertEquals(setOf("BUILD_HISTORY", "BUILD_PLAN_VERSIONS", "APP_SETTINGS"), syncable)
        assertFalse(
            "runtime-bound data must never be proposed for sync",
            syncable.contains("MINECRAFT_BRIDGE_PAIRING"),
        )
    }

    @Test
    fun everyNamedStoreTypeExistsInTheProductionSourcesSoTheClassificationCannotDriftIntoFiction() {
        val sourceRoot = File("src/main/java")
        assertTrue(
            "expected the app source root at ${sourceRoot.absolutePath} (run from the app module directory)",
            sourceRoot.isDirectory,
        )
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        for (domain in CraftMindDataDomain.entries) {
            assertTrue(
                "${domain.name} names ${domain.storedBy}, which does not exist in the app sources",
                sources.contains(domain.storedBy),
            )
        }
    }

    @Test
    fun ownershipClassificationDoesNotPromiseSynchronisation() {
        for (domain in CraftMindDataDomain.entries) {
            val text = domain.summary.lowercase()
            assertFalse(
                "${domain.name} must not claim data is synchronised",
                text.contains("synchronis") || text.contains("synchroniz") || text.contains("synced"),
            )
        }
    }
}
