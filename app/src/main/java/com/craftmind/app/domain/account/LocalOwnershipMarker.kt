package com.craftmind.app.domain.account

/**
 * A one-time marker recording that the local CraftMind data on this device predates accounts (Phase 16 §12).
 *
 * Ownership itself is **derived** from [CraftMindDataDomain], not stamped onto every build record: rewriting user data
 * to add a field nobody reads yet would be a pointless, irreversible migration. What a future cloud phase does need is
 * the ability to tell "this device has data from before accounts existed" apart from "this device has data written by a
 * signed-in build", so exactly that — a schema version and a timestamp — is recorded, once.
 */
data class LocalOwnershipMarker(
    val schemaVersion: Int,
    val markedAtEpochMillis: Long,
) {
    companion object {
        /** Bumped only if the meaning of the marker changes; the migration treats any version >= this as current. */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Storage boundary for the marker.
 *
 * The marker contains no user data and no secret, so it does not need encryption — but it must be written atomically:
 * an implementation replaces the record in one step, so an interrupted write can never leave a half-written marker.
 */
interface LocalOwnershipMarkerStore {
    fun read(): LocalOwnershipMarker?
    fun write(marker: LocalOwnershipMarker)
}

/** What [LocalOwnershipMigration.ensureMarker] did. */
sealed interface OwnershipMarkerOutcome {
    /** The marker was written by this call. */
    data class Recorded(val marker: LocalOwnershipMarker) : OwnershipMarkerOutcome

    /** The marker was already current; nothing was written. */
    data class AlreadyCurrent(val marker: LocalOwnershipMarker) : OwnershipMarkerOutcome

    /** The marker could not be written. Local data is untouched and CraftMind keeps working. */
    data object StorageUnavailable : OwnershipMarkerOutcome
}

/**
 * Writes the ownership marker exactly once (Phase 16 §12).
 *
 * Guarantees, each covered by a test:
 * * **idempotent** — running it again on an up-to-date marker performs no write;
 * * **non-destructive** — it never deletes, rewrites, or truncates user data; it only adds a marker beside it;
 * * **backward compatible** — an absent marker is a valid state meaning "legacy local data", which is treated as
 *   device-local, so an app that never runs this migration behaves correctly;
 * * **interruption-safe** — a failed write leaves the previous state in place and a later run completes the work.
 */
class LocalOwnershipMigration(
    private val store: LocalOwnershipMarkerStore,
    private val clock: AccountClock = AccountClock.System,
) {
    fun ensureMarker(): OwnershipMarkerOutcome {
        val existing = try {
            store.read()
        } catch (_: Exception) {
            null
        }
        if (existing != null && existing.schemaVersion >= LocalOwnershipMarker.CURRENT_SCHEMA_VERSION) {
            return OwnershipMarkerOutcome.AlreadyCurrent(existing)
        }
        val marker = LocalOwnershipMarker(
            schemaVersion = LocalOwnershipMarker.CURRENT_SCHEMA_VERSION,
            markedAtEpochMillis = clock.nowMillis(),
        )
        return try {
            store.write(marker)
            OwnershipMarkerOutcome.Recorded(marker)
        } catch (_: Exception) {
            OwnershipMarkerOutcome.StorageUnavailable
        }
    }
}
