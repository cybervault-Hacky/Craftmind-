package com.craftmind.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository

class BridgePairingViewModelFactory(
    private val repository: MinecraftBridgePairingRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BridgePairingViewModel::class.java))
        return BridgePairingViewModel(repository) as T
    }
}
