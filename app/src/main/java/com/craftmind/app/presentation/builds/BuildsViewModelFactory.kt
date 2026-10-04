package com.craftmind.app.presentation.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.craftmind.app.domain.buildplan.LocalBuildRepository

class BuildsViewModelFactory(private val repository: LocalBuildRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BuildsViewModel::class.java))
        return BuildsViewModel(repository) as T
    }
}
