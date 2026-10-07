package com.craftmind.app.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 §11: the local ownership migration must be idempotent, backward compatible, interrupt-safe, and must never
 * be able to lose data. The marker it writes is metadata about *where* data lives; it carries no user content.
 */
class LocalOwnershipMigrationTest {
    private class FakeMarkerStore(
        var existing: LocalOwnershipMarker? = null,
    ) : LocalOwnershipMarkerStore {
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var writes: Int = 0
            private set
        var lastWritten: LocalOwnershipMarker? = null
            private set

        override fun read(): LocalOwnershipMarker? {
            readFailure?.let { throw it }
            return existing
        }

        override fun write(marker: LocalOwnershipMarker) {
            writes++
            writeFailure?.let { throw it }
            lastWritten = marker
            existing = marker
        }
    }

    private val clock = FixedAccountClock(1_700_000_000_000L)

    @Test
    fun aFreshInstallRecordsTheCurrentSchemaExactlyOnce() {
        val store = FakeMarkerStore()

        val outcome = LocalOwnershipMigration(store, clock).ensureMarker()

        assertTrue(outcome is OwnershipMarkerOutcome.Recorded)
        assertEquals(1, store.writes)
        assertEquals(LocalOwnershipMarker.CURRENT_SCHEMA_VERSION, store.lastWritten?.schemaVersion)
        assertEquals(clock.nowMillis(), store.lastWritten?.markedAtEpochMillis)
    }

    @Test
    fun runningItAgainChangesNothingWhichIsWhatMakesItIdempotent() {
        val store = FakeMarkerStore()
        val migration = LocalOwnershipMigration(store, clock)
        val first = migration.ensureMarker()

        val second = migration.ensureMarker()

        assertTrue(first is OwnershipMarkerOutcome.Recorded)
        assertTrue(second is OwnershipMarkerOutcome.AlreadyCurrent)
        assertEquals("a second run must not rewrite the marker", 1, store.writes)
    }

    @Test
    fun aMarkerFromANewerSchemaIsLeftUntouchedSoDowngradesDoNotCorruptIt() {
        val newer = LocalOwnershipMarker(
            schemaVersion = LocalOwnershipMarker.CURRENT_SCHEMA_VERSION + 3,
            markedAtEpochMillis = 42L,
        )
        val store = FakeMarkerStore(existing = newer)

        val outcome = LocalOwnershipMigration(store, clock).ensureMarker()

        assertEquals(OwnershipMarkerOutcome.AlreadyCurrent(newer), outcome)
        assertEquals(0, store.writes)
        assertEquals(newer, store.existing)
    }

    @Test
    fun anUnreadableMarkerIsRepairedInsteadOfFailingTheApp() {
        val store = FakeMarkerStore()
        store.readFailure = IllegalStateException("corrupted marker")

        val outcome = LocalOwnershipMigration(store, clock).ensureMarker()

        assertTrue(outcome is OwnershipMarkerOutcome.Recorded)
        assertEquals(1, store.writes)
    }

    @Test
    fun anInterruptedWriteReportsUnavailableAndThenSucceedsOnTheNextLaunch() {
        val store = FakeMarkerStore()
        store.writeFailure = java.io.IOException("power loss")
        val migration = LocalOwnershipMigration(store, clock)

        val interrupted = migration.ensureMarker()
        store.writeFailure = null
        val retried = migration.ensureMarker()

        assertEquals(OwnershipMarkerOutcome.StorageUnavailable, interrupted)
        assertTrue("the next launch must complete the marker", retried is OwnershipMarkerOutcome.Recorded)
        assertEquals(LocalOwnershipMarker.CURRENT_SCHEMA_VERSION, store.existing?.schemaVersion)
    }

    @Test
    fun theMarkerCarriesNoUserContent() {
        val store = FakeMarkerStore()

        LocalOwnershipMigration(store, clock).ensureMarker()

        val marker = store.lastWritten
        val instanceFields = LocalOwnershipMarker::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.name == "Companion" }
        assertEquals(
            "the marker records a schema version and a timestamp, nothing else",
            listOf("markedAtEpochMillis", "schemaVersion"),
            instanceFields.map { it.name }.sorted(),
        )
        assertTrue(marker!!.schemaVersion > 0)
        assertTrue(
            "the marker must not be able to hold user content",
            instanceFields.none { it.type == String::class.java },
        )
    }

    @Test
    fun aMarkerThatIsAlreadyCurrentDoesNotMoveItsTimestamp() {
        val existing = LocalOwnershipMarker(LocalOwnershipMarker.CURRENT_SCHEMA_VERSION, 5L)
        val store = FakeMarkerStore(existing = existing)

        LocalOwnershipMigration(store, FixedAccountClock(999_999L)).ensureMarker()

        assertEquals("re-running the migration must not restamp anything", 5L, store.existing?.markedAtEpochMillis)
    }
}
