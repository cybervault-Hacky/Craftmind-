package com.craftmind.app.presentation.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.data.ai.AiBuildEngine
import com.craftmind.app.domain.buildplan.LocalBuildRepository

class BuildRefinementViewModelFactory(
    private val engine: AiBuildEngine,
    private val history: LocalBuildRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(BuildRefinementViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return BuildRefinementViewModel(engine, history) as T
    }
}
