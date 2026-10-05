package com.craftmind.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.data.ai.AiBuildEngine

class ProviderSettingsViewModelFactory(private val engine: AiBuildEngine) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ProviderSettingsViewModel::class.java))
        return ProviderSettingsViewModel(engine) as T
    }
}
