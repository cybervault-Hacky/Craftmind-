package com.craftmind.app.data.builds

import androidx.test.core.app.ApplicationProvider
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicLocalBuildExecutionRepositoryTest {
    @Test
    fun persistsBridgeSnapshotsAtomicallyAndSeparatelyFromImmutablePlanHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionFile = File(context.noBackupFilesDir, "local-build-executions-v1.json")
        val planHistoryFile = File(context.noBackupFilesDir, "local-build-records-v1.json")
        executionFile.delete()
        try {
            val repository = AtomicLocalBuildExecutionRepository(context)
            repository.load()
            val prepared = record()
            repository.save(prepared)
            val running = prepared.copy(
                phase = MinecraftExecutionPhase.RUNNING,
                completedOperations = 1,
                eventSequence = 2,
                updatedAtEpochMillis = prepared.createdAtEpochMillis + 1,
            )
            repository.save(running)
            repository.save(prepared) // A late, older bridge response cannot roll history back.

            assertEquals(MinecraftExecutionPhase.RUNNING, repository.records.value.single().phase)
            assertEquals(1, repository.records.value.single().completedOperations)
            assertTrue(executionFile.exists())
            assertNotEquals(executionFile.canonicalPath, planHistoryFile.canonicalPath)

            val reopened = AtomicLocalBuildExecutionRepository(context)
            reopened.load()
            assertEquals(listOf(running), reopened.records.value)
        } finally {
            executionFile.delete()
        }
    }

    private fun record() = LocalBuildExecutionRecord(
        executionId = "123e4567-e89b-42d3-a456-426614174000",
        buildId = "build-demo",
        planRecordId = "build-demo-v1",
        planVersion = 1,
        bridgeId = "bridge-0123456789abcdef0123456789abcdef",
        phase = MinecraftExecutionPhase.PREPARED,
        completedOperations = 0,
        totalOperations = 2,
        eventSequence = 1,
        createdAtEpochMillis = 1_700_000_000_000,
        updatedAtEpochMillis = 1_700_000_000_000,
        dimensionId = "minecraft:overworld",
        worldSessionId = "8cbb1e95-3dae-4c3a-81e2-f1c6077e3097",
        resolvedOrigin = BlockPosition(-32, 64, 128),
    )
}
