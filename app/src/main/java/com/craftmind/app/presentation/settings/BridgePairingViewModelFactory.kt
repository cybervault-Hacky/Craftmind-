package com.craftmind.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver

class BridgePairingViewModelFactory(
    private val repository: MinecraftBridgePairingRepository,
    private val compatibilityResolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BridgePairingViewModel::class.java))
        return BridgePairingViewModel(repository, compatibilityResolver) as T
    }
}
