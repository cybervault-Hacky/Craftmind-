package com.craftmind.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.data.ai.AiBuildEngine
import com.craftmind.app.domain.buildplan.LocalBuildRepository

class HomeViewModelFactory(
    private val engine: AiBuildEngine,
    private val localBuilds: LocalBuildRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java))
        return HomeViewModel(engine, localBuilds) as T
    }
}
