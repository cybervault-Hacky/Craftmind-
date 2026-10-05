package com.craftmind.app.presentation.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRepository
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository

class BuildExecutionViewModelFactory(
    private val bridge: MinecraftBridgePairingRepository,
    private val builds: LocalBuildRepository,
    private val executions: LocalBuildExecutionRepository,
    private val executionIdFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(BuildExecutionViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return BuildExecutionViewModel(bridge, builds, executions, executionIdFactory) as T
    }
}
